package com.bookshelf.myBooks;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Live book discovery. Subject filtering is remote; exact averages are checked here. */
@Service
public class OpenLibraryService {
    public static final int PAGE_SIZE = 24;
    private static final Logger log = LoggerFactory.getLogger(OpenLibraryService.class);
    private static final Map<String, String> SUBJECTS = Map.of(
            "Kids", BookAudience.kidsQuery(),
            "Fantasy", "(subject:\"fantasy\" OR subject:\"magic\")",
            "Mystery", "(subject:\"mystery\" OR subject:\"detective and mystery stories\" OR subject:\"detective fiction\")",
            "Science Fiction", "(subject:\"science fiction\" OR subject:\"space travel\")",
            "Romance", "(subject:\"romance\" OR subject:\"love stories\")",
            "Classics", "(subject:\"classics\" OR subject:\"classic literature\")",
            "Literary Fiction", "(subject:\"literary fiction\" OR subject:\"literature\")",
            "Nonfiction", "(subject:\"nonfiction\" OR subject:\"non-fiction\" OR subject:\"self-help\" OR subject:\"biography\" OR subject:\"history\")"
    );
    private final RestClient restClient;
    private final boolean enabled;
    private final Map<String, CachedSearch> cache = new LinkedHashMap<>();
    private final Map<String, CachedDescription> descriptions = new LinkedHashMap<>();
    private final Map<String, CachedMetadata> metadata = new LinkedHashMap<>();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private long nextRequestAt;
    private record CachedSearch(long expiresAt, SearchPage page) { }
    private record CachedDescription(long expiresAt, String text) { }
    private record CachedMetadata(long expiresAt, BookPreview book) { }
    public record SearchPage(List<BookPreview> books, long total, boolean available, boolean postFiltered) {
        SearchPage(List<BookPreview> books, long total, boolean available) { this(books, total, available, false); }
    }

    public OpenLibraryService(@Value("${bookbuddy.catalog.online:true}") boolean enabled,
            @Value("${bookbuddy.catalog.base-url:https://openlibrary.org}") String baseUrl) {
        this.enabled = enabled;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(8));
        restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requests)
                .defaultHeader("User-Agent", "AI-Book-Buddy/1.0 (local learning project)").build();
    }

    public List<BookPreview> searchBooks(String query) { return searchBooks(query, null, null); }

    public List<BookPreview> searchBooks(String query, String genre, Double minRating) {
        return searchPage(query, genre, minRating, 1).books();
    }

    /** Cache successful query/filter/page combinations. Failed reads can be retried. */
    public synchronized SearchPage searchPage(String query, String genre, Double minRating, int page) {
        return fetchPage(query, genre, minRating, page, false);
    }

    public synchronized SearchPage trendingPage(String genre, Double minRating, int page) {
        return fetchPage("", genre, minRating, page, true);
    }

    /** AI keywords are literal terms, never executable search syntax or a relaxation of reader filters. */
    public synchronized SearchPage recommendationPage(List<String> keywords, String genre, Double minRating,
            boolean includeOlder) {
        String terms = keywords == null ? "" : keywords.stream().limit(4)
                .map(OpenLibraryService::literal).filter(value -> !value.isBlank())
                .map(value -> "\"" + value + "\"").collect(java.util.stream.Collectors.joining(" OR "));
        PublicationWindow window = includeOlder ? null : PublicationWindow.forRequest("", false);
        return fetchPage(terms, genre, minRating, 1, false, window, true, "recommend|");
    }

    /** Exact identity verification for Google editions; keep the original-year and English checks. */
    public synchronized SearchPage verifyRecommendation(BookPreview book, String genre, Double minRating,
            boolean includeOlder) {
        String identity = "title:\"" + literal(book.title()) + "\" AND author:\"" + literal(book.author()) + "\"";
        return fetchPage(identity, genre, minRating, 1, false,
                includeOlder ? null : PublicationWindow.forRequest("", false), true, "verify|");
    }

    /** Work descriptions are fetched only when details are opened, with a small bounded cache. */
    public synchronized String description(String workId) {
        if (!enabled || workId == null || !workId.matches("/works/OL\\d+W")) return "";
        CachedDescription cached = descriptions.get(workId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.text();
        try {
            waitForProvider();
            JsonNode work = boundedJson(restClient.get().uri(workId + ".json"));
            if (work == null) return "";
            JsonNode value = work.path("description");
            String text = value.isTextual() ? value.asText() : value.path("value").isTextual() ? value.path("value").asText() : "";
            text = plainText(text, 2000);
            if (descriptions.size() >= 100) descriptions.remove(descriptions.keySet().iterator().next());
            descriptions.put(workId, new CachedDescription(System.currentTimeMillis()
                    + Duration.ofMinutes(text.isBlank() ? 2 : 60).toMillis(), text));
            return text;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException exception) {
            return "";
        }
    }

    /** Resolve an exact provider work identity before attaching another source's description. */
    public synchronized BookPreview metadata(String workId) {
        if (!enabled || workId == null || !workId.matches("/works/OL[0-9]{1,30}W")) return null;
        CachedMetadata cached = metadata.get(workId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.book();
        try {
            waitForProvider();
            JsonNode response = boundedJson(restClient.get().uri(uri -> uri.path("/search.json")
                    .queryParam("q", "{query}").queryParam("limit", 1)
                    .queryParam("fields", "key,title,author_name,cover_i,ratings_average,ratings_count,first_publish_year,subject")
                    .build("key:\"" + workId + "\"")));
            if (response == null || !response.path("docs").isArray()) return null;
            BookPreview book = null;
            for (JsonNode doc : response.path("docs")) {
                String id = doc.path("key").asText("");
                if (!id.startsWith("/works/")) id = "/works/" + id;
                if (!workId.equals(id)) continue;
                String title = plainText(doc.path("title").asText(""), 500);
                if (title.isBlank()) continue;
                var authors = new ArrayList<String>();
                if (doc.path("author_name").isArray()) for (JsonNode author : doc.path("author_name")) {
                    if (authors.size() >= 8) break;
                    if (author.isTextual() && !author.asText().isBlank()) authors.add(plainText(author.asText(), 120));
                }
                var subjects = new ArrayList<String>();
                if (doc.path("subject").isArray()) for (JsonNode subject : doc.path("subject")) {
                    if (subject.isTextual() && !subject.asText().isBlank()) subjects.add(plainText(subject.asText(), 160));
                }
                String genre = BookAudience.isKids(workId, subjects) ? "Kids" : genreFromSubjects(subjects);
                Double rating = doc.path("ratings_average").isNumber() ? doc.path("ratings_average").doubleValue() : null;
                if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 5)) rating = null;
                Integer count = nonnegativeInteger(doc.path("ratings_count"));
                Integer cover = nonnegativeInteger(doc.path("cover_i"));
                Integer year = nonnegativeInteger(doc.path("first_publish_year"));
                book = new BookPreview(workId, title, authors.isEmpty() ? "Unknown author" : truncate(String.join(", ", authors), 500),
                        genre, cover != null && cover > 0 ? cover : null, rating, count, year, "", "openlibrary",
                        subjects.stream().limit(12).toList(), null);
                break;
            }
            if (metadata.size() >= 100) metadata.remove(metadata.keySet().iterator().next());
            metadata.put(workId, new CachedMetadata(System.currentTimeMillis()
                    + Duration.ofMinutes(book == null ? 2 : 60).toMillis(), book));
            return book;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private JsonNode boundedJson(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((sent, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) return null;
            byte[] bytes = response.getBody().readNBytes(1048577);
            if (bytes.length > 1048576) throw new IOException("Catalog response exceeds limit");
            JsonNode value = mapper.readTree(bytes);
            return value == null || !value.isObject() || value.hasNonNull("error") ? null : value;
        });
    }

    private static Integer nonnegativeInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
    }

    private static String plainText(String value, int limit) {
        String bounded = value.substring(0, Math.min(value.length(), 16000));
        String decoded = HtmlUtils.htmlUnescape(HtmlUtils.htmlUnescape(bounded));
        String text = decoded.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]*>", " ").replaceAll("[\\p{Cc}\\p{Cf}]+", " ")
                .replaceAll("\\s+", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }

    private SearchPage fetchPage(String query, String genre, Double minRating, int page, boolean trending) {
        PublicationWindow publicationWindow = PublicationWindow.forRequest(query, trending);
        boolean englishSuggestions = publicationWindow != null;
        return fetchPage(query, genre, minRating, page, trending, publicationWindow, englishSuggestions,
                trending ? "trending|" : "browse|");
    }

    private SearchPage fetchPage(String query, String genre, Double minRating, int page, boolean trending,
            PublicationWindow publicationWindow, boolean englishSuggestions, String mode) {
        if (!enabled) return new SearchPage(List.of(), 0, false);
        // These are the same activity constraints and sort used by Open
        // Library's homepage Trending Books carousel. A high star rating or
        // an old book's lifetime reading count does not make it trending.
        String builtQuery = buildQuery(trending
                ? "trending_score_hourly_sum:[1 TO *] AND readinglog_count:[4 TO *]" : query, genre, minRating, publicationWindow);
        String solrQuery = englishSuggestions && publicationWindow == null ? builtQuery + " AND language:eng" : builtQuery;
        String key = mode + solrQuery.toLowerCase(Locale.ROOT) + "|min=" + minRating + "|page=" + page;
        CachedSearch cached = cache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.page();
        try {
            // The public API allows one unidentified request/second. Cache hits need no delay.
            waitForProvider();
            OpenLibraryResponse response = restClient.get()
                    .uri(uri -> {
                        var search = uri.path("/search.json")
                            .queryParam("q", "{query}")
                            .queryParam("page", page)
                            .queryParam("limit", PAGE_SIZE)
                            .queryParam("lang", "en")
                            .queryParam("fields", "key,title,author_name,cover_i,ratings_average,ratings_count,first_publish_year,subject"
                                    + (englishSuggestions ? ",editions,editions.key,editions.title,editions.cover_i,editions.language" : ""));
                        if (trending) search.queryParam("sort", "trending");
                        else if (minRating != null && minRating > 0) search.queryParam("sort", "rating");
                        // Default discovery puts recent original publications
                        // first. Trending and an explicit rating keep their own order.
                        else if (publicationWindow != null) search.queryParam("sort", "new");
                        return search.build(solrQuery);
                    })
                    .retrieve().body(OpenLibraryResponse.class);
            List<BookPreview> results = new ArrayList<>();
            boolean postFiltered = false;
            if (response != null && response.docs() != null) {
                for (OpenLibraryResponse.BookDoc book : response.docs()) {
                    if (book.key() == null || book.title() == null) continue;
                    String workId = book.key().startsWith("/works/") ? book.key() : "/works/" + book.key();
                    if (!workId.matches("/works/OL\\d+W")) continue;
                    // Do not treat a new edition of an old work as a new book.
                    // Unknown and future first-publication years are not recommendations.
                    if (publicationWindow != null && !publicationWindow.contains(book.first_publish_year())) {
                        postFiltered = true;
                        continue;
                    }
                    // Work metadata combines many languages. Use the matching
                    // English edition, including translations, for suggestions.
                    // Missing language metadata is not proof of an English edition.
                    OpenLibraryResponse.EditionDoc edition = englishSuggestions ? englishEdition(book) : null;
                    if (englishSuggestions && edition == null) {
                        postFiltered = true;
                        continue;
                    }
                    Double rating = book.ratings_average();
                    // The API can sort ratings but cannot query ratings_average.
                    // Keep the user's exact threshold here, including excluding unrated works.
                    if (minRating != null && minRating > 0 && (rating == null || rating < minRating)) continue;
                    String author = book.author_name() == null || book.author_name().isEmpty()
                            ? "Unknown author" : String.join(", ", book.author_name());
                    List<String> allSubjects = book.subject() == null ? List.of() : book.subject();
                    // Check ALL subjects before display tags are shortened. Do
                    // not trust a broad genre label or an inconsistent upstream filter.
                    boolean kids = BookAudience.isKids(workId, allSubjects);
                    boolean wantsKids = "Kids".equals(genre);
                    if (kids != wantsKids) { postFiltered = true; continue; }
                    String resultGenre = wantsKids ? "Kids"
                            : genre == null || genre.isBlank() ? genreFromSubjects(allSubjects) : genre;
                    String displayTitle = edition == null ? book.title() : edition.title();
                    Integer displayCover = edition == null ? book.cover_i() : edition.cover_i();
                    results.add(new BookPreview(workId, truncate(displayTitle, 500), truncate(author, 500), resultGenre,
                            displayCover != null && displayCover > 0 ? displayCover : null,
                            rating, book.ratings_count(), book.first_publish_year(), "", "openlibrary",
                            allSubjects.stream().limit(12).toList(), null));
                }
            }
            long total = response == null ? 0 : response.numFound() == null
                    ? results.size() : Math.max(0, response.numFound());
            SearchPage saved = new SearchPage(List.copyOf(results), total, true, postFiltered);
            putCache(key, saved, Duration.ofMinutes(trending ? 5 : 15));
            return saved;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SearchPage(List.of(), 0, false);
        } catch (RuntimeException exception) {
            log.info("Open Library is unavailable; using the local catalog ({})", exception.getClass().getSimpleName());
            SearchPage unavailable = new SearchPage(List.of(), 0, false);
            return unavailable;
        }
    }

    private void waitForProvider() throws InterruptedException {
        long wait = nextRequestAt - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        nextRequestAt = System.currentTimeMillis() + 1100;
    }

    private static String literal(String value) {
        if (value == null) return "";
        String bounded = value.substring(0, Math.min(value.length(), 160));
        return bounded.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").strip();
    }

    static String buildQuery(String query, String genre, Double minRating) {
        return buildQuery(query, genre, minRating, PublicationWindow.forRequest(query, false));
    }

    private static String buildQuery(String query, String genre, Double minRating, PublicationWindow publicationWindow) {
        List<String> conditions = new ArrayList<>();
        if (query != null && !query.isBlank()) conditions.add("(" + query.strip() + ")");
        if (genre != null && !genre.isBlank() && SUBJECTS.containsKey(genre)) conditions.add(SUBJECTS.get(genre));
        if (minRating != null && minRating > 0) {
            conditions.add("ratings_count:[1 TO *]");
        }
        if (publicationWindow != null) {
            conditions.add(publicationWindow.query());
            // lang=en only boosts English; this clause actually requires it.
            conditions.add("language:eng");
        }
        // A whole-index negative subject query repeatedly times out at the
        // provider. For a general browse, request recent first-publication
        // years and apply the audience check above; gather the next 11 matches.
        if (conditions.isEmpty()) return "*:*";
        if (!"Kids".equals(genre) && ((query != null && !query.isBlank())
                || (genre != null && !genre.isBlank()) || (minRating != null && minRating > 0))) {
            conditions.add("NOT " + BookAudience.kidsQuery());
        }
        return String.join(" AND ", conditions);
    }

    static String canonicalGenre(String genre) {
        if (genre == null || genre.isBlank() || genre.equalsIgnoreCase("all") || genre.equalsIgnoreCase("any")) return "";
        return SUBJECTS.keySet().stream().filter(value -> value.equalsIgnoreCase(genre.strip())).findFirst().orElse(null);
    }

    private static OpenLibraryResponse.EditionDoc englishEdition(OpenLibraryResponse.BookDoc book) {
        if (book.editions() == null || book.editions().docs() == null) return null;
        return book.editions().docs().stream()
                .filter(edition -> edition != null && edition.title() != null && !edition.title().isBlank())
                .filter(edition -> edition.language() != null
                        && edition.language().stream().anyMatch("eng"::equalsIgnoreCase))
                .findFirst().orElse(null);
    }

    private void putCache(String key, SearchPage page, Duration ttl) {
        if (cache.size() >= 100) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new CachedSearch(System.currentTimeMillis() + ttl.toMillis(), page));
    }

    static String genreFromSubjects(List<String> subjects) {
        String text = String.join(" ", subjects).toLowerCase(Locale.ROOT);
        if (text.contains("science fiction") || text.contains("space travel")) return "Science Fiction";
        if (text.contains("fantasy") || text.contains("magic")) return "Fantasy";
        if (text.contains("mystery") || text.contains("detective")) return "Mystery";
        if (text.contains("romance") || text.contains("love stories")) return "Romance";
        if (text.contains("self-help") || text.contains("habits") || text.contains("biography") || text.contains("nonfiction")) return "Nonfiction";
        if (text.contains("classic")) return "Classics";
        if (text.contains("literary fiction")) return "Literary Fiction";
        return "General";
    }

    private String truncate(String text, int max) { return text.length() > max ? text.substring(0, max) : text; }
}

package com.bookshelf.myBooks;

import java.io.IOException;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Optional, quota-bounded Google Books metadata. No provider exceptions or secrets leave this class. */
@Service
public class GoogleBooksService {
    private static final String FIELDS = "id,volumeInfo(title,subtitle,authors,description,categories,language,publishedDate,averageRating,ratingsCount,printType)";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern ACTIVE_HTML = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ANCILLARY = Pattern.compile("\\b(?:study guide|study guides|workbook|workbooks|summary|summaries|reading journal|blank journal|blank book|notebook|notebooks|coloring book|colouring book|magazine)\\b");
    // Keep parsed provider payloads small enough for the free host's bounded Java heap.
    private static final int MAX_BYTES = 131072;
    private static final int MAX_CACHE_ENTRIES = 40;
    private static final Map<String, String> SUBJECTS = Map.of(
            "Fantasy", "fantasy", "Mystery", "mystery", "Science Fiction", "science fiction",
            "Romance", "romance", "Classics", "classics", "Literary Fiction", "literary",
            "Nonfiction", "nonfiction", "Kids", "juvenile");
    private final ObjectMapper mapper;
    private final RestClient client;
    private final String apiKey;
    private final boolean enabled;
    private final Clock clock;
    private final int dailyLimit;
    private final int minuteLimit;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ArrayDeque<Long> recentRequests = new ArrayDeque<>();
    private LocalDate quotaDay;
    private int requestsToday;
    private long cooldownUntil;
    private String cooldownState = "quota_limited";
    private String cooldownMessage = "Google Books requests are temporarily paused after a provider limit.";
    private record Cached(long expiresAt, JsonNode value) { }
    private record ProviderReply(JsonNode value, String state, String message) { }
    public record SearchResult(List<BookPreview> books, ProviderOutcome outcome) {
        public SearchResult { books = List.copyOf(books); }
    }
    public record GooglePage(List<BookPreview> books, long total, boolean hasMore, ProviderOutcome outcome) {
        public GooglePage { books = List.copyOf(books); }
    }

    @Autowired
    public GoogleBooksService(ObjectMapper mapper,
            @Value("${bookbuddy.catalog.online:true}") boolean online,
            @Value("${bookbuddy.google-books.enabled:true}") boolean enabled,
            @Value("${bookbuddy.google-books.api-key:${GOOGLE_BOOKS_API_KEY:}}") String apiKey,
            @Value("${bookbuddy.google-books.base-url:https://www.googleapis.com/books/v1}") String baseUrl) {
        this(mapper, online, enabled, apiKey, baseUrl, Clock.systemUTC(), 600, 20);
    }

    // Injectable time/limits keep quota tests local and instantaneous.
    GoogleBooksService(ObjectMapper mapper, boolean online, boolean enabled, String apiKey,
            String baseUrl, Clock clock, int dailyLimit, int minuteLimit) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.enabled = online && enabled && !this.apiKey.isBlank();
        this.clock = clock;
        this.dailyLimit = Math.max(0, Math.min(600, dailyLimit));
        this.minuteLimit = Math.max(0, Math.min(20, minuteLimit));
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "Book-Buddy/1.0")
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE).build();
    }

    /** Configured and enabled; individual requests may still be quota-limited or unavailable. */
    public boolean available() { return enabled; }

    /** Google publishedDate is an edition date, never proof of a work's original publication. */
    public synchronized List<BookPreview> search(String terms, String genre, boolean includeOlder) {
        return searchResult(terms, genre, includeOlder).books();
    }

    /** Ordinary catalog search uses Google as a real source, with explicit provider pagination. */
    public synchronized GooglePage searchPage(String query, String genre, Double minRating, int sourcePage, boolean includeOlder) {
        if (!enabled) return pageFailure("not_configured", "Google Books is not configured or is disabled on this server.");
        String selectedGenre = OpenLibraryService.canonicalGenre(genre);
        if (selectedGenre == null || sourcePage < 1 || sourcePage > 50
                || minRating != null && (!Double.isFinite(minRating) || minRating < 0 || minRating > 5)) {
            return pageFailure("filtered", "Google Books was not queried because the catalog filters are unsupported.");
        }
        String terms = literalTerms(query);
        boolean discovery = terms.isBlank();
        String subject = SUBJECTS.get(selectedGenre);
        if (subject != null) terms += (terms.isBlank() ? "" : " ") + "subject:\"" + subject + "\"";
        if (terms.isBlank()) terms = "subject:fiction";
        String order = discovery && (minRating == null || minRating <= 0) ? "newest" : "relevance";
        int offset = (sourcePage - 1) * 20;
        ProviderReply reply = request("page:" + offset + ":" + order + ":" + terms, null, terms, offset, order);
        SearchResult response = volumes(reply);
        if (reply.value() == null) return new GooglePage(List.of(), 0, false, response.outcome());
        int currentYear = LocalDate.now(clock).getYear();
        boolean kids = "Kids".equals(selectedGenre);
        List<BookPreview> books = response.books().stream()
                .filter(book -> isKids(book) == kids)
                .filter(book -> book.year() == null || book.year() <= currentYear)
                .filter(book -> includeOlder || book.year() != null && book.year() >= currentYear - 4)
                .filter(book -> minRating == null || minRating <= 0 || book.rating() != null && book.rating() >= minRating)
                .toList();
        JsonNode count = reply.value().path("totalItems");
        long total = count.isIntegralNumber() && count.canConvertToLong() ? Math.max(0, count.longValue()) : response.outcome().received();
        boolean more = response.outcome().received() > 0 && offset + response.outcome().received() < total;
        String state = books.isEmpty() ? response.outcome().received() == 0 ? "empty" : "filtered" : "ok";
        String message = books.isEmpty() ? response.outcome().received() == 0 ? "Google Books returned no records for these search terms."
                : "Google Books responded, but its records did not meet the selected filters."
                : "Google Books returned matching English book editions.";
        return new GooglePage(books, total, more, result(books, state, message, response.outcome().received()).outcome());
    }

    private static GooglePage pageFailure(String state, String message) {
        return new GooglePage(List.of(), 0, false, result(List.of(), state, message, 0).outcome());
    }

    /** A configured key is not a successful connection: every lookup reports its own outcome. */
    public synchronized SearchResult searchResult(String terms, String genre, boolean includeOlder) {
        if (!enabled) return result(List.of(), "not_configured", "Google Books is not configured or is disabled on this server.", 0);
        String selectedGenre = OpenLibraryService.canonicalGenre(genre);
        if (selectedGenre == null) return result(List.of(), "filtered", "Google Books was not queried because the genre is unsupported.", 0);
        String query = literalTerms(terms);
        String subject = SUBJECTS.get(selectedGenre);
        if (subject != null) query += (query.isBlank() ? "" : " ") + "subject:\"" + subject + "\"";
        if (query.isBlank()) return result(List.of(), "filtered", "Google Books needs a search term or genre.", 0);
        SearchResult response = volumes(query);
        if (!List.of("ok", "empty", "filtered").contains(response.outcome().state())) return response;
        int currentYear = LocalDate.now(clock).getYear();
        boolean kids = "Kids".equals(selectedGenre);
        List<BookPreview> books = response.books().stream()
                .filter(book -> isKids(book) == kids)
                .filter(book -> book.year() == null || book.year() <= currentYear)
                // This is deliberately labelled an edition-date policy, not an original-year guarantee.
                .filter(book -> includeOlder || book.year() != null
                        && book.year() >= currentYear - 4 && book.year() <= currentYear)
                .limit(20).toList();
        if (!books.isEmpty()) return result(books, "ok", "Google Books returned matching English book editions.", response.outcome().received());
        return result(books, response.outcome().received() == 0 ? "empty" : "filtered",
                response.outcome().received() == 0 ? "Google Books returned no records for these search terms."
                        : "Google Books responded, but its records did not meet the language, audience, edition-date or book-type filters.",
                response.outcome().received());
    }

    /** Try explicit broader catalog queries only after an empty result; transport failures are never retried. */
    public synchronized SearchResult searchQueries(List<String> queries, String genre, boolean includeOlder) {
        if (!enabled) return result(List.of(), "not_configured", "Google Books is not configured or is disabled on this server.", 0);
        var distinct = new LinkedHashSet<String>();
        if (queries != null) for (String query : queries) {
            String cleaned = literalTerms(query);
            if (!cleaned.isBlank()) distinct.add(cleaned);
            if (distinct.size() >= 3) break;
        }
        if (distinct.isEmpty()) distinct.add("");
        int received = 0;
        SearchResult last = null;
        for (String query : distinct) {
            last = searchResult(query, genre, includeOlder);
            received += last.outcome().received();
            if (!last.books().isEmpty() || !List.of("empty", "filtered").contains(last.outcome().state())) {
                return result(last.books(), last.outcome().state(), last.outcome().message(), received);
            }
        }
        return result(List.of(), received > 0 ? "filtered" : last.outcome().state(), last.outcome().message(), received);
    }

    /** Resolve a real reference title before deriving similarity terms; the anchor itself need not be recent. */
    public synchronized SearchResult lookupReference(String title) {
        if (!enabled) return result(List.of(), "not_configured", "Google Books is not configured or is disabled on this server.", 0);
        String cleaned = literalTerms(title);
        if (cleaned.isBlank()) return result(List.of(), "filtered", "Google Books needs a reference title.", 0);
        SearchResult response = volumes("intitle:\"" + cleaned + "\"");
        if (!List.of("ok", "empty", "filtered").contains(response.outcome().state())) return response;
        List<BookPreview> books = response.books().stream()
                .filter(book -> normalize(book.title()).equals(normalize(title)))
                .filter(book -> !blank(book.author()) && !"unknown author".equalsIgnoreCase(book.author()))
                .limit(3).toList();
        return result(books, books.isEmpty() ? response.outcome().received() == 0 ? "empty" : "filtered" : "ok",
                books.isEmpty() ? "Google Books did not provide an exact reference-title match."
                        : "Google Books verified the reference title and supplied its catalog metadata.", response.outcome().received());
    }

    /** Fill a missing description only after an exact normalized title AND author match. */
    public synchronized BookPreview enrich(BookPreview original) {
        if (!enabled || original == null || !blank(original.description()) || blank(original.title())
                || blank(original.author()) || "unknown author".equalsIgnoreCase(original.author().strip())) return original;
        String id = original.id();
        List<BookPreview> candidates;
        if (id != null && id.startsWith("googlebooks:") && ID.matcher(id.substring(12)).matches()) {
            String volumeId = id.substring(12);
            ProviderReply reply = request("volume:" + volumeId, volumeId, null);
            BookPreview direct = map(reply.value());
            candidates = direct == null || !id.equals(direct.id()) ? List.of() : List.of(direct);
        } else {
            // Quotes and operators in source metadata are removed before our own query syntax.
            String query = "intitle:\"" + literalTerms(original.title()) + "\" inauthor:\""
                    + literalTerms(original.author()) + "\"";
            candidates = volumes(query).books();
        }
        for (BookPreview candidate : candidates) {
            if (!sameIdentity(original, candidate) || blank(candidate.description())) continue;
            return new BookPreview(original.id(), original.title(), original.author(), original.genre(),
                    original.coverId(), original.rating(), original.ratingCount(), original.year(),
                    candidate.description(), original.source(), original.tags(), original.reason());
        }
        return original;
    }

    private SearchResult volumes(String query) {
        return volumes(request("search:" + query, null, query));
    }

    private SearchResult volumes(ProviderReply reply) {
        JsonNode response = reply.value();
        if (response == null) return result(List.of(), reply.state(), reply.message(), 0);
        if (!response.path("items").isArray()) return result(List.of(), "empty", "Google Books returned no records for these search terms.", 0);
        var resultBooks = new LinkedHashMap<String, BookPreview>();
        int examined = 0;
        for (JsonNode node : response.path("items")) {
            if (++examined > 20) break;
            BookPreview book = map(node);
            if (book != null) resultBooks.putIfAbsent(book.id(), book);
        }
        List<BookPreview> books = List.copyOf(resultBooks.values());
        int received = Math.min(20, response.path("items").size());
        return result(books, books.isEmpty() ? received == 0 ? "empty" : "filtered" : "ok",
                books.isEmpty() ? "Google Books responded without eligible English book records."
                        : "Google Books returned English book records.", received);
    }

    /** Synchronized callers coalesce identical simultaneous requests into a single cached lookup. */
    private ProviderReply request(String cacheKey, String volumeId, String query) {
        return request(cacheKey, volumeId, query, 0, "relevance");
    }

    private ProviderReply request(String cacheKey, String volumeId, String query, int offset, String order) {
        long now = clock.millis();
        Cached saved = cache.get(cacheKey);
        if (saved != null && saved.expiresAt() > now) return new ProviderReply(saved.value(), "ok", "Google Books metadata was read from its recent response cache.");
        if (!enabled) return new ProviderReply(null, "not_configured", "Google Books is not configured or is disabled on this server.");
        if (now < cooldownUntil) return new ProviderReply(null, cooldownState, cooldownMessage);
        if (!takeQuota(now)) return new ProviderReply(null, "quota_limited", "The app's shared Google Books allowance is temporarily used up.");
        try {
            ProviderReply reply = client.get().uri(uri -> {
                var builder = volumeId == null ? uri.path("/volumes") : uri.pathSegment("volumes", volumeId);
                if (query != null) builder.queryParam("q", "{terms}").queryParam("printType", "books")
                        .queryParam("langRestrict", "en").queryParam("maxResults", 20)
                        .queryParam("startIndex", offset).queryParam("orderBy", order)
                        .queryParam("fields", "totalItems,items(" + FIELDS + ")");
                else builder.queryParam("fields", FIELDS);
                builder.queryParam("key", "{apiKey}");
                return query == null ? builder.build(Map.of("apiKey", apiKey))
                        : builder.build(Map.of("terms", query, "apiKey", apiKey));
            }).exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (status == 403 || status == 429) {
                    cooldownUntil = clock.millis() + Duration.ofMinutes(15).toMillis();
                    // Inspect only known reason codes; never relay provider text or a request URL.
                    boolean quota = status == 429;
                    try {
                        byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
                        if (bytes.length <= MAX_BYTES) quota |= quotaError(mapper.readTree(bytes));
                    } catch (RuntimeException | IOException ignored) { /* A safe generic rejection remains enough. */ }
                    cooldownState = quota ? "quota_limited" : "unavailable";
                    cooldownMessage = quota ? "Google Books rejected the request because its shared allowance is temporarily unavailable."
                            : "Google Books rejected the request. Check the server key's API restrictions and whether Books API is enabled.";
                    return new ProviderReply(null, cooldownState, cooldownMessage);
                }
                if (!response.getStatusCode().is2xxSuccessful()) return new ProviderReply(null, "unavailable", "Google Books did not accept this request. Please try later or check the server configuration.");
                byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Metadata response exceeds limit");
                JsonNode parsed = mapper.readTree(bytes);
                if (parsed == null || !parsed.isObject() || parsed.hasNonNull("error")) return new ProviderReply(null, "unavailable", "Google Books returned an unreadable catalog response.");
                if (query != null && parsed.has("items") && !parsed.path("items").isArray()) return new ProviderReply(null, "unavailable", "Google Books returned an unreadable catalog response.");
                return new ProviderReply(parsed, "ok", "Google Books responded successfully.");
            });
            JsonNode node = reply.value();
            if (node != null) {
                boolean empty = query != null ? node.path("items").isMissingNode() || node.path("items").isEmpty() : map(node) == null;
                long ttl = empty ? Duration.ofMinutes(2).toMillis() : Duration.ofHours(6).toMillis();
                cache.put(cacheKey, new Cached(now + ttl, node));
                while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
            }
            return reply;
        } catch (RuntimeException exception) {
            // URI and provider bodies may contain the key: do not log or propagate them.
            return new ProviderReply(null, "unavailable", "Google Books could not be reached or its response exceeded the app's safe size limit.");
        }
    }

    private static SearchResult result(List<BookPreview> books, String state, String message, int received) {
        return new SearchResult(books, new ProviderOutcome("Google Books", state, message, received, books.size()));
    }

    private static boolean quotaError(JsonNode response) {
        if (response == null) return false;
        List<String> reasons = List.of("quotaexceeded", "dailylimitexceeded", "ratelimitexceeded", "userratelimitexceeded", "resourceexhausted");
        JsonNode error = response.path("error");
        String status = normalize(text(error.path("status"))).replace(" ", "");
        if (reasons.contains(status)) return true;
        if (error.path("errors").isArray()) for (JsonNode item : error.path("errors")) {
            if (reasons.contains(normalize(text(item.path("reason"))).replace(" ", ""))) return true;
        }
        if (error.path("details").isArray()) for (JsonNode item : error.path("details")) {
            if (reasons.contains(normalize(text(item.path("reason"))).replace(" ", ""))) return true;
        }
        return false;
    }

    private boolean takeQuota(long now) {
        LocalDate today = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        if (!today.equals(quotaDay)) { quotaDay = today; requestsToday = 0; }
        while (!recentRequests.isEmpty() && recentRequests.getFirst() <= now - 60000) recentRequests.removeFirst();
        if (now < cooldownUntil || requestsToday >= dailyLimit || recentRequests.size() >= minuteLimit) return false;
        requestsToday++;
        recentRequests.addLast(now);
        return true;
    }

    private BookPreview map(JsonNode node) {
        if (node == null) return null;
        String id = text(node.path("id"));
        JsonNode info = node.path("volumeInfo");
        if (!ID.matcher(id).matches() || !"en".equalsIgnoreCase(text(info.path("language")))
                || !"BOOK".equalsIgnoreCase(text(info.path("printType")))) return null;
        String title = clean(text(info.path("title")), 500);
        if (title.isBlank()) return null;
        String ancillary = normalize(title + " " + text(info.path("subtitle")));
        if (ANCILLARY.matcher(ancillary).find()) return null;
        List<String> authors = strings(info.path("authors"), 8, 120);
        String author = authors.isEmpty() ? "Unknown author" : String.join(", ", authors);
        List<String> categories = strings(info.path("categories"), 20, 160);
        if (categories.stream().map(GoogleBooksService::normalize).anyMatch(value -> value.startsWith("study aids") || value.contains("study guides"))) return null;
        String genre = kidsCategories(categories) ? "Kids" : genre(categories);
        Double rating = info.path("averageRating").isNumber() ? info.path("averageRating").doubleValue() : null;
        if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 5)) rating = null;
        Integer count = info.path("ratingsCount").isIntegralNumber() && info.path("ratingsCount").canConvertToInt()
                ? info.path("ratingsCount").intValue() : null;
        if (count != null && count < 0) count = null;
        String published = text(info.path("publishedDate"));
        Integer year = published.matches("[12][0-9]{3}(?:-[0-9]{2}(?:-[0-9]{2})?)?") ? Integer.valueOf(published.substring(0, 4)) : null;
        return new BookPreview("googlebooks:" + id, title, clean(author, 500), genre, null,
                rating, count, year, clean(text(info.path("description")), 2000), "googlebooks", categories, null);
    }

    private static boolean sameIdentity(BookPreview original, BookPreview candidate) {
        return normalize(original.title()).equals(normalize(candidate.title()))
                && !normalize(original.author()).isBlank()
                && normalize(original.author()).equals(normalize(candidate.author()));
    }

    private static boolean isKids(BookPreview book) { return "Kids".equals(book.genre()) || BookAudience.isKids(book.id(), book.tags()); }
    private static boolean kidsCategories(List<String> categories) {
        if (BookAudience.isKids(null, categories)) return true;
        return categories.stream().map(GoogleBooksService::normalize)
                .anyMatch(value -> value.startsWith("juvenile ") || value.equals("juvenile")
                        || value.startsWith("children ") || value.equals("children")
                        || value.startsWith("childrens ") || value.equals("childrens"));
    }

    private static String genre(List<String> categories) {
        String guessed = OpenLibraryService.genreFromSubjects(categories);
        if (!"General".equals(guessed)) return guessed;
        List<String> normalized = categories.stream().map(GoogleBooksService::normalize).toList();
        if (normalized.stream().anyMatch(value -> value.equals("fiction literary")
                || value.startsWith("fiction literary "))) return "Literary Fiction";
        if (normalized.stream().anyMatch(value -> value.equals("history") || value.startsWith("history ")
                || value.equals("science") || value.startsWith("science ")
                || value.equals("psychology") || value.startsWith("psychology ")
                || value.equals("business economics") || value.startsWith("business economics ")
                || value.equals("literary criticism") || value.startsWith("literary criticism "))) return "Nonfiction";
        return "General";
    }

    private static List<String> strings(JsonNode array, int count, int length) {
        if (!array.isArray()) return List.of();
        var result = new ArrayList<String>();
        for (JsonNode item : array) {
            if (result.size() >= count) break;
            String cleaned = clean(text(item), length);
            if (!cleaned.isBlank()) result.add(cleaned);
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node) { return node.isTextual() ? node.asText() : ""; }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String literalTerms(String text) {
        if (text == null) return "";
        String value = text.substring(0, Math.min(250, text.length())).replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ").strip();
        return value.substring(0, Math.min(200, value.length())).strip();
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String clean(String value, int limit) {
        String bounded = value.substring(0, Math.min(value.length(), 16000));
        String decoded = HtmlUtils.htmlUnescape(HtmlUtils.htmlUnescape(bounded));
        String text = HTML_TAG.matcher(ACTIVE_HTML.matcher(decoded).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }
}

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
    private record Cached(long expiresAt, JsonNode value) { }

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
        if (!enabled) return List.of();
        String selectedGenre = OpenLibraryService.canonicalGenre(genre);
        if (selectedGenre == null) return List.of();
        String query = literalTerms(terms);
        String subject = SUBJECTS.get(selectedGenre);
        if (subject != null) query += (query.isBlank() ? "" : " ") + "subject:\"" + subject + "\"";
        if (query.isBlank()) return List.of();
        List<BookPreview> books = volumes(query);
        int currentYear = LocalDate.now(clock).getYear();
        boolean kids = "Kids".equals(selectedGenre);
        return books.stream()
                .filter(book -> isKids(book) == kids)
                // This preliminary edition-date check is not an original-year guarantee.
                // The recommendation service must verify original years through Open Library.
                .filter(book -> includeOlder || book.year() != null
                        && book.year() >= currentYear - 4 && book.year() <= currentYear)
                .limit(8).toList();
    }

    /** Fill a missing description only after an exact normalized title AND author match. */
    public synchronized BookPreview enrich(BookPreview original) {
        if (!enabled || original == null || !blank(original.description()) || blank(original.title())
                || blank(original.author()) || "unknown author".equalsIgnoreCase(original.author().strip())) return original;
        String id = original.id();
        List<BookPreview> candidates;
        if (id != null && id.startsWith("googlebooks:") && ID.matcher(id.substring(12)).matches()) {
            String volumeId = id.substring(12);
            JsonNode value = request("volume:" + volumeId, volumeId, null);
            BookPreview direct = map(value);
            candidates = direct == null || !id.equals(direct.id()) ? List.of() : List.of(direct);
        } else {
            // Quotes and operators in source metadata are removed before our own query syntax.
            String query = "intitle:\"" + literalTerms(original.title()) + "\" inauthor:\""
                    + literalTerms(original.author()) + "\"";
            candidates = volumes(query);
        }
        for (BookPreview candidate : candidates) {
            if (!sameIdentity(original, candidate) || blank(candidate.description())) continue;
            return new BookPreview(original.id(), original.title(), original.author(), original.genre(),
                    original.coverId(), original.rating(), original.ratingCount(), original.year(),
                    candidate.description(), original.source(), original.tags(), original.reason());
        }
        return original;
    }

    private List<BookPreview> volumes(String query) {
        JsonNode result = request("search:" + query, null, query);
        if (result == null || !result.path("items").isArray()) return List.of();
        var resultBooks = new LinkedHashMap<String, BookPreview>();
        int examined = 0;
        for (JsonNode node : result.path("items")) {
            if (++examined > 8) break;
            BookPreview book = map(node);
            if (book != null) resultBooks.putIfAbsent(book.id(), book);
        }
        return List.copyOf(resultBooks.values());
    }

    /** Synchronized callers coalesce identical simultaneous requests into a single cached lookup. */
    private JsonNode request(String cacheKey, String volumeId, String query) {
        long now = clock.millis();
        Cached saved = cache.get(cacheKey);
        if (saved != null && saved.expiresAt() > now) return saved.value();
        if (!enabled || !takeQuota(now)) return null;
        try {
            JsonNode node = client.get().uri(uri -> {
                var builder = volumeId == null ? uri.path("/volumes") : uri.pathSegment("volumes", volumeId);
                if (query != null) builder.queryParam("q", "{terms}").queryParam("printType", "books")
                        .queryParam("langRestrict", "en").queryParam("maxResults", 8)
                        .queryParam("orderBy", "relevance").queryParam("fields", "items(" + FIELDS + ")");
                else builder.queryParam("fields", FIELDS);
                builder.queryParam("key", "{apiKey}");
                return query == null ? builder.build(Map.of("apiKey", apiKey))
                        : builder.build(Map.of("terms", query, "apiKey", apiKey));
            }).exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (status == 403 || status == 429) {
                    cooldownUntil = clock.millis() + Duration.ofMinutes(15).toMillis();
                    return null;
                }
                if (!response.getStatusCode().is2xxSuccessful()) return null;
                byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Metadata response exceeds limit");
                JsonNode parsed = mapper.readTree(bytes);
                if (parsed == null || !parsed.isObject() || parsed.hasNonNull("error")) return null;
                if (query != null && parsed.has("items") && !parsed.path("items").isArray()) return null;
                return parsed;
            });
            if (node != null) {
                boolean empty = query != null ? node.path("items").isMissingNode() || node.path("items").isEmpty() : map(node) == null;
                long ttl = empty ? Duration.ofMinutes(2).toMillis() : Duration.ofHours(6).toMillis();
                cache.put(cacheKey, new Cached(now + ttl, node));
                while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
            }
            return node;
        } catch (RuntimeException exception) {
            // URI and provider bodies may contain the key: do not log or propagate them.
            return null;
        }
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
        List<String> authors = strings(info.path("authors"), 8, 120);
        String author = authors.isEmpty() ? "Unknown author" : String.join(", ", authors);
        List<String> categories = strings(info.path("categories"), 20, 160);
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

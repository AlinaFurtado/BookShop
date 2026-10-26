package com.bookshelf.myBooks;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read public, attributed review excerpts without keys, scraping or database imports. */
@Service
public class ExternalReviewService {
    private static final Logger log = LoggerFactory.getLogger(ExternalReviewService.class);
    private static final int MAX_EDITIONS = 2;
    private static final int MAX_REVIEWS = 6;
    private static final int MAX_BYTES = 1048576;
    private static final Pattern ACTIVE_HTML = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");
    private static final DateTimeFormatter ARCHIVE_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final RestClient client;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private long nextRequestAt;
    private record Cached(long expiresAt, ExternalReviews value) { }

    public record SourceEdition(String id, String title, String url) { }
    public record ExternalReview(String id, String reviewer, String title, String excerpt,
            Integer rating, String date, boolean shortened, String editionTitle, String sourceUrl) { }
    public record ExternalReviews(String source, String status, String message,
            List<ExternalReview> reviews, List<SourceEdition> editions) { }

    public ExternalReviewService(ObjectMapper mapper,
            @Value("${bookbuddy.external-reviews.enabled:${bookbuddy.catalog.online:true}}") boolean enabled,
            @Value("${bookbuddy.external-reviews.base-url:https://archive.org}") String baseUrl) {
        this.mapper = mapper;
        this.enabled = enabled;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "AI-Book-Buddy/1.0 (local learning project)").build();
    }

    public synchronized ExternalReviews forWork(String workId) {
        if (!enabled) return result("disabled", "External reviews are switched off in offline mode.", List.of(), List.of());
        Cached cached = cache.get(workId);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.value();
        String shortId = workId.substring("/works/".length());
        var reviews = new LinkedHashMap<String, ExternalReview>();
        List<SourceEdition> editions = new ArrayList<>();
        boolean incomplete = false;
        try {
            // Match the catalog identifier, not a title shared by unrelated books.
            String query = "openlibrary_work:" + shortId + " AND mediatype:texts AND num_reviews:[1 TO *]";
            pause();
            JsonNode search = read(client.get().uri(uri -> uri.path("/advancedsearch.php")
                    .queryParam("q", "{query}")
                    .queryParam("fl[]", "identifier", "title", "openlibrary_work")
                    .queryParam("sort[]", "num_reviews desc")
                    .queryParam("rows", MAX_EDITIONS).queryParam("page", 1).queryParam("output", "json").build(query)));
            JsonNode docs = search.path("response").path("docs");
            if (!docs.isArray()) throw new IllegalStateException("Invalid source search response");
            int checked = 0;
            for (JsonNode doc : docs) {
                if (++checked > MAX_EDITIONS || reviews.size() >= MAX_REVIEWS) break;
                String identifier = scalar(doc.path("identifier"));
                if (!identifier.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}")
                        || !containsWork(doc.path("openlibrary_work"), shortId)) continue;
                String editionTitle = clean(scalar(doc.path("title")), 300);
                if (editionTitle.isBlank()) editionTitle = "Archived edition";
                String sourceUrl = "https://archive.org/details/" + identifier + "#reviews";
                editions.add(new SourceEdition(identifier, editionTitle, sourceUrl));
                try {
                    pause();
                    JsonNode response = read(client.get().uri(uri -> uri.pathSegment("metadata", identifier, "reviews")
                            .queryParam("start", 0).queryParam("count", MAX_REVIEWS).build()));
                    JsonNode items = response.path("result");
                    if (!items.isArray()) { incomplete = true; continue; }
                    for (JsonNode item : items) {
                        String title = clean(scalar(item.path("reviewtitle")), 160);
                        String text = clean(scalar(item.path("reviewbody")), 12000);
                        if (title.isBlank() && text.isBlank()) continue;
                        String reviewer = clean(scalar(item.path("reviewer")), 120);
                        if (reviewer.isBlank()) reviewer = "Internet Archive reader";
                        String date = date(scalar(item.path("reviewdate")));
                        if (date == null) date = date(scalar(item.path("createdate")));
                        // Only a short excerpt is cached/displayed; full text stays at the source.
                        String excerpt = excerpt(text);
                        Integer rating = rating(item.path("stars"));
                        String identity = scalar(item.path("reviewer_itemname"));
                        String id = identifier + ":" + (identity.isBlank() ? reviewer : identity);
                        reviews.putIfAbsent(id, new ExternalReview(id, reviewer, title, excerpt,
                                rating, date, !excerpt.equals(text), editionTitle, sourceUrl));
                        if (reviews.size() >= MAX_REVIEWS) break;
                    }
                } catch (RuntimeException exception) {
                    incomplete = true;
                    log.info("An Internet Archive review edition could not be read ({})", exception.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result("unavailable", "The external review lookup was interrupted. Please try again.", List.of(), List.of());
        } catch (RuntimeException exception) {
            log.info("Internet Archive reviews could not be loaded ({})", exception.getClass().getSimpleName());
            return result("unavailable", "Internet Archive could not be reached. Book Buddy reviews are still available below.", List.of(), editions);
        }
        String status = incomplete ? "partial" : reviews.isEmpty() ? "empty" : "ready";
        String message = incomplete
                ? "Some archived editions could not be checked. You can retry or read reviews at the source."
                : reviews.isEmpty() ? "No written reviews were available in the matching archived editions checked."
                : "Existing reader reviews from matching Internet Archive editions. They may discuss the scan or edition as well as the story.";
        ExternalReviews value = result(status, message, List.copyOf(reviews.values()), List.copyOf(editions));
        // Cache only complete reads, so a failed lookup can be retried immediately.
        if (!incomplete) {
            if (cache.size() >= 100) cache.remove(cache.keySet().iterator().next());
            cache.put(workId, new Cached(System.currentTimeMillis() + Duration.ofMinutes(30).toMillis(), value));
        }
        return value;
    }

    private JsonNode read(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((sent, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw new IOException("Review source request failed");
            byte[] bytes = response.getBody().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Review response is too large");
            JsonNode node = mapper.readTree(bytes);
            if (node == null || node.hasNonNull("error")) throw new IOException("Review source returned an error");
            return node;
        });
    }

    private void pause() throws InterruptedException {
        long wait = nextRequestAt - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        nextRequestAt = System.currentTimeMillis() + 1100;
    }

    private static boolean containsWork(JsonNode value, String expected) {
        if (value.isTextual()) return expected.equals(value.asText());
        if (value.isArray()) for (JsonNode item : value) if (expected.equals(item.asText())) return true;
        return false;
    }

    private static String scalar(JsonNode value) {
        return value.isTextual() || value.isNumber() ? value.asText() : "";
    }

    private static String clean(String value, int limit) {
        String bounded = value.substring(0, Math.min(value.length(), 20000));
        String decoded = HtmlUtils.htmlUnescape(bounded);
        String text = HTML_TAG.matcher(ACTIVE_HTML.matcher(decoded).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[\\p{Cntrl}\\s\\u00a0]+", " ").strip();
        return text.substring(0, Math.min(text.length(), limit));
    }

    private static String excerpt(String text) {
        if (text.length() <= 240) return text;
        int boundary = text.lastIndexOf(' ', 240);
        return text.substring(0, boundary > 120 ? boundary : 240).strip() + "…";
    }

    private static Integer rating(JsonNode value) {
        try {
            int rating = Integer.parseInt(scalar(value));
            return rating >= 1 && rating <= 5 ? rating : null;
        } catch (NumberFormatException exception) { return null; }
    }

    private static String date(String value) {
        if (value.isBlank()) return null;
        try { return Instant.parse(value).toString(); }
        catch (DateTimeParseException exception) {
            try { return LocalDateTime.parse(value, ARCHIVE_DATE).toInstant(ZoneOffset.UTC).toString(); }
            catch (DateTimeParseException ignored) { return null; }
        }
    }

    private static ExternalReviews result(String status, String message, List<ExternalReview> reviews, List<SourceEdition> editions) {
        return new ExternalReviews("Internet Archive", status, message, reviews, List.copyOf(editions));
    }
}

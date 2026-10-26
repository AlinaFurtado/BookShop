package com.bookshelf.myBooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Local HTTP stubs only: no Google account, live request, billing, or real key is used. */
class GoogleBooksServiceTests {
    private static final String TEST_KEY = "dummy-test-key&private=yes";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private GoogleBooksService service;
    private volatile int status = 200;
    private volatile Object body;
    private volatile String accept;
    private volatile String userAgent;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/books/v1/", this::respond);
        server.start();
        service = client(true, true, TEST_KEY, 600, 20);
        body = searchBody(volume("exact_ID-1", "A Quiet Voyage", "Jane Reader", "Fiction / Fantasy / Cozy", "<p>A calm &amp; magical journey.</p>", "en", "2025"));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void requestsOnlyEnglishBooksWithEncodedLiteralTermsAndTruthfulFields() {
        var results = service.search("cozy & magic / subject:romance", "Fantasy", false);
        assertEquals(1, results.size());
        BookPreview book = results.getFirst();
        assertEquals("googlebooks:exact_ID-1", book.id());
        assertEquals("googlebooks", book.source());
        assertEquals("Fantasy", book.genre());
        assertEquals(List.of("Fiction / Fantasy / Cozy"), book.tags());
        assertEquals("A calm & magical journey.", book.description());
        assertNull(book.coverId());
        assertNull(book.rating());
        assertNull(book.ratingCount());
        assertEquals(2025, book.year());
        Map<String, String> query = parameters(requests.getFirst());
        assertEquals("cozy magic subject romance subject:\"fantasy\"", query.get("q"));
        assertEquals("books", query.get("printType"));
        assertEquals("en", query.get("langRestrict"));
        assertEquals("8", query.get("maxResults"));
        assertEquals("relevance", query.get("orderBy"));
        assertEquals(TEST_KEY, query.get("key"));
        assertFalse(query.containsKey("private"), "The key is encoded as one query value");
        assertTrue(query.get("fields").contains("description"));
        assertEquals("application/json", accept);
        assertEquals("Book-Buddy/1.0", userAgent);
    }

    @Test
    void enforcesEnglishAudienceAndSafeIdsWithoutMistakingYoungAdultForChildren() {
        body = searchBody(
                volume("adult", "Adult Fantasy", "Jane Reader", "Fiction / Fantasy", "Real description", "en", "2025"),
                volume("young", "Older Teen Fantasy", "Jane Reader", "Young Adult Fiction / Fantasy", "Real description", "en", "2025"),
                volume("child", "A Young Explorer", "Jane Reader", "Juvenile Fiction / Fantasy", "Real description", "en", "2025"),
                volume("child2", "Picture Story", "Jane Reader", "Children / Picture Books", "Real description", "en", "2025"),
                volume("french", "Une histoire", "Jane Reader", "Fiction / Fantasy", "Description", "fr", "2025"),
                volume("../unsafe", "Bad identifier", "Jane Reader", "Fiction / Fantasy", "Description", "en", "2025"));
        assertEquals(List.of("googlebooks:adult", "googlebooks:young"), service.search("magic", "Fantasy", false).stream().map(BookPreview::id).toList());
        assertEquals(List.of("googlebooks:child", "googlebooks:child2"), service.search("magic", "Kids", false).stream().map(BookPreview::id).toList());
        assertTrue(service.search("magic", "unknown genre", true).isEmpty());
        assertEquals(2, requests.size(), "Unsupported genres do not spend quota");
    }

    @Test
    void retainsMissingValuesAndAllowsOlderEditionsOnlyWhenRequested() {
        Map<String, Object> minimal = Map.of("id", "minimal", "volumeInfo", Map.of(
                "title", "An Unrated Book", "language", "en", "printType", "BOOK"));
        Map<String, Object> invalidRating = volume("invalidRating", "Invalid Score", "Jane Reader", "Fiction", "", "en", "2026");
        @SuppressWarnings("unchecked")
        var info = (Map<String, Object>) invalidRating.get("volumeInfo");
        info.put("averageRating", 8);
        info.put("ratingsCount", -2);
        body = searchBody(minimal, volume("old", "A Classic", "Jane Reader", "Fiction", "", "en", "1978"), invalidRating);
        var recent = service.search("books", "", false);
        assertEquals(List.of("googlebooks:invalidRating"), recent.stream().map(BookPreview::id).toList());
        assertNull(recent.getFirst().rating());
        assertNull(recent.getFirst().ratingCount());
        var all = service.search("books", "", true);
        assertEquals(3, all.size());
        assertNull(all.getFirst().year());
        assertEquals("Unknown author", all.getFirst().author());
        assertEquals("", all.getFirst().description());
        assertEquals(1, requests.size(), "Older policy filters a shared source response without another request");
    }

    @Test
    void enrichmentRequiresMatchingTitleAndAuthorAndPreservesOpenLibraryIdentity() {
        BookPreview original = original("A Quiet Voyage", "Jane Reader");
        body = searchBody(
                volume("wrongauthor", "A Quiet Voyage", "Different Person", "Fiction", "Wrong author description", "en", "2025"),
                volume("collection", "A Quiet Voyage Collection", "Jane Reader", "Fiction", "Wrong collection description", "en", "2025"),
                volume("right", "A Quiet Voyage", "Jane Reader", "Fiction", "<script>bad()</script><b>The true description.</b>", "en", "2025"));
        BookPreview enriched = service.enrich(original);
        assertEquals("The true description.", enriched.description());
        assertEquals(original.id(), enriched.id());
        assertEquals(original.source(), enriched.source());
        assertEquals(original.genre(), enriched.genre());
        assertEquals(original.tags(), enriched.tags());
        assertEquals(original.rating(), enriched.rating());
        assertEquals(original.ratingCount(), enriched.ratingCount());
        assertEquals(original.year(), enriched.year());
        assertEquals(original.coverId(), enriched.coverId());
        assertEquals(original.reason(), enriched.reason());
        body = searchBody(volume("wrong", "A Quiet Voyage", "Different Person", "Fiction", "Wrong", "en", "2025"));
        BookPreview unmatched = original("Different Book", "Jane Reader");
        assertSame(unmatched, service.enrich(unmatched));
        assertSame(enriched, service.enrich(enriched), "An existing description is never overwritten");
        assertEquals(2, requests.size());
    }

    @Test
    void directGoogleEnrichmentUsesValidatedIdAndIgnoresReturnedMismatches() {
        body = volume("exact_ID-1", "A Quiet Voyage", "Jane Reader", "Fiction", "Stored source description", "en", "2025");
        BookPreview original = new BookPreview("googlebooks:exact_ID-1", "A Quiet Voyage", "Jane Reader", "Fantasy",
                null, 4.2, 17, 2025, "", "googlebooks", List.of(), null);
        assertEquals("Stored source description", service.enrich(original).description());
        assertTrue(requests.getFirst().startsWith("/books/v1/volumes/exact_ID-1?"));
        assertFalse(parameters(requests.getFirst()).containsKey("q"));
        body = volume("other", "Another Book", "Jane Reader", "Fiction", "Wrong description", "en", "2025");
        BookPreview mismatch = new BookPreview("googlebooks:otherrequested", "A Quiet Voyage", "Jane Reader", "Fantasy",
                null, null, null, null, "", "googlebooks", List.of(), null);
        assertSame(mismatch, service.enrich(mismatch));
    }

    @Test
    void disabledOrMissingKeyNeverMakesAnExternalRequest() {
        for (GoogleBooksService offline : List.of(client(false, true, TEST_KEY, 600, 20),
                client(true, false, TEST_KEY, 600, 20), client(true, true, " ", 600, 20))) {
            assertFalse(offline.available());
            assertTrue(offline.search("magic", "Fantasy", true).isEmpty());
            BookPreview original = original("A Quiet Voyage", "Jane Reader");
            assertSame(original, offline.enrich(original));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    void slashCategoriesKeepHistoryAndCriticismSeparateFromLiteraryFiction() {
        body = searchBody(
                volume("history", "A True History", "Jane Reader", "History / Europe / Modern", "", "en", "2025"),
                volume("criticism", "About Novels", "Jane Reader", "Literary Criticism / General", "", "en", "2025"),
                volume("novel", "A Novel", "Jane Reader", "Fiction / Literary", "", "en", "2025"));
        List<BookPreview> result = service.search("literature history", "", true);
        assertEquals(List.of("Nonfiction", "Nonfiction", "Literary Fiction"), result.stream().map(BookPreview::genre).toList());
    }

    @Test
    void cachesSuccessfulAndEmptyResponsesAndCoalescesConcurrentLookups() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            Callable<List<BookPreview>> lookup = () -> service.search("magic", "Fantasy", false);
            for (var future : executor.invokeAll(List.of(lookup, lookup, lookup, lookup))) assertEquals(1, future.get().size());
        }
        assertEquals(1, requests.size());
        clock.advance(Duration.ofHours(6).plusSeconds(1));
        service.search("magic", "Fantasy", false);
        assertEquals(2, requests.size());
        body = Map.of("totalItems", 0);
        assertTrue(service.search("no match", "Fantasy", true).isEmpty());
        assertTrue(service.search("no match", "Fantasy", true).isEmpty());
        assertEquals(3, requests.size());
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        service.search("no match", "Fantasy", true);
        assertEquals(4, requests.size());
    }

    @Test
    void capsMinuteAndDailyRequestsWhileServingCacheAndResetsOnUtcDay() {
        service = client(true, true, TEST_KEY, 3, 2);
        service.search("one", "Fantasy", true);
        service.search("two", "Fantasy", true);
        assertTrue(service.search("three", "Fantasy", true).isEmpty());
        assertEquals(2, requests.size());
        assertEquals(1, service.search("one", "Fantasy", true).size());
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        service.search("three", "Fantasy", true);
        assertTrue(service.search("four", "Fantasy", true).isEmpty());
        assertEquals(3, requests.size());
        clock.advance(Duration.ofDays(1));
        assertEquals(1, service.search("four", "Fantasy", true).size());
        assertEquals(4, requests.size());
    }

    @Test
    void oversizedProviderMetadataIsNotKeptInTheSmallHostCache() {
        body = searchBody(volume("oversize", "A Quiet Voyage", "Jane Reader", "Fiction",
                "x".repeat(140000), "en", "2025"));
        assertTrue(service.search("magic", "Fantasy", true).isEmpty());
        body = searchBody(volume("normal", "A Quiet Voyage", "Jane Reader", "Fiction",
                "A normal source description.", "en", "2025"));
        assertEquals(1, service.search("magic", "Fantasy", true).size());
        assertEquals(2, requests.size(), "An oversized source response is not cached as an empty success");
    }

    @Test
    void quotaRejectionsCoolDownWithoutLeakingKeyOrProviderBodyAndOutagesDoNotCache() {
        for (int failure : List.of(403, 429)) {
            service = client(true, true, TEST_KEY, 600, 20);
            status = failure;
            body = Map.of("error", "provider body contains " + TEST_KEY);
            assertTrue(assertDoesNotThrow(() -> service.search("magic", "Fantasy", true)).isEmpty());
            int attempts = requests.size();
            status = 200;
            body = searchBody(volume("recovered", "A Quiet Voyage", "Jane Reader", "Fiction", "Recovered", "en", "2025"));
            assertTrue(service.search("other", "Fantasy", true).isEmpty());
            assertEquals(attempts, requests.size(), "A quota rejection prevents an immediate retry");
            clock.advance(Duration.ofMinutes(16));
            var recovered = service.search("magic", "Fantasy", true);
            assertEquals(1, recovered.size());
            assertFalse(recovered.toString().contains(TEST_KEY));
        }
        status = 503;
        assertTrue(service.search("outage", "Fantasy", true).isEmpty());
        int attempts = requests.size();
        status = 200;
        assertFalse(service.search("outage", "Fantasy", true).isEmpty());
        assertEquals(attempts + 1, requests.size(), "An outage is not cached as an empty catalog");
    }

    private GoogleBooksService client(boolean online, boolean enabled, String key, int daily, int minute) {
        return new GoogleBooksService(mapper, online, enabled, key,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/books/v1", clock, daily, minute);
    }

    private static BookPreview original(String title, String author) {
        return new BookPreview("/works/OL123W", title, author, "Literary Fiction", 123, 4.4, 20,
                1999, "", "openlibrary", List.of("an original tag"), "Original reason");
    }

    private static Map<String, Object> volume(String id, String title, String author, String category,
            String description, String language, String published) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("authors", List.of(author));
        info.put("categories", List.of(category));
        info.put("description", description);
        info.put("language", language);
        info.put("publishedDate", published);
        info.put("printType", "BOOK");
        return Map.of("id", id, "volumeInfo", info);
    }

    @SafeVarargs
    private static Map<String, Object> searchBody(Map<String, Object>... items) { return Map.of("items", List.of(items)); }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        accept = exchange.getRequestHeaders().getFirst("Accept");
        userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        byte[] data = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        try (var output = exchange.getResponseBody()) { output.write(data); }
        catch (IOException closedEarlyForOversize) { /* The bounded client may close an oversized response. */ }
    }

    private static Map<String, String> parameters(String uri) {
        var result = new LinkedHashMap<String, String>();
        String raw = uri.substring(uri.indexOf('?') + 1);
        for (String pair : raw.split("&")) {
            String[] pieces = pair.split("=", 2);
            result.put(URLDecoder.decode(pieces[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pieces.length == 1 ? "" : pieces[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-27T12:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

package com.bookshelf.myBooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** No Internet, accounts, payments or file H2 are used by these source tests. */
class ExternalReviewTests {
    private HttpServer server;
    private ExternalReviewService service;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile boolean failSearch;
    private volatile boolean secondEdition;
    private volatile boolean wrongWork;
    private volatile boolean invalidSearch;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        service = new ExternalReviewService(mapper, true, baseUrl());
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void fetchesRealSourceFieldsByExactWorkIdWithSafeAttributedExcerpts() {
        var result = service.forWork("/works/OL27482W");
        assertEquals("ready", result.status());
        assertEquals("Internet Archive", result.source());
        assertEquals(2, result.reviews().size(), "Skip empty entries and duplicate reviews from one reviewer/item");
        var first = result.reviews().getFirst();
        assertEquals("Actual reader", first.reviewer());
        assertEquals("A real review", first.title());
        assertEquals(4, first.rating());
        assertEquals("2026-10-12T10:20:30Z", first.date());
        assertTrue(first.excerpt().startsWith("Stored & attributed"));
        assertTrue(first.shortened());
        assertTrue(first.excerpt().length() <= 241);
        assertFalse(first.excerpt().contains("<") || first.excerpt().contains("bad()"));
        assertEquals("https://archive.org/details/hobbit-scan#reviews", first.sourceUrl());
        assertNull(result.reviews().get(1).rating(), "Zero stars means no supplied rating, not a fabricated score");
        assertNull(result.reviews().get(1).date(), "Invalid dates stay unknown");
        assertTrue(URLDecoder.decode(requests.getFirst(), StandardCharsets.UTF_8)
                .contains("openlibrary_work:OL27482W AND mediatype:texts AND num_reviews:[1 TO *]"));
        assertEquals(2, requests.size());
    }

    @Test
    void aSameTitleWithAnotherWorkIdIsNeverImported() {
        wrongWork = true;
        var result = service.forWork("/works/OL27482W");
        assertEquals("empty", result.status());
        assertTrue(result.reviews().isEmpty());
        assertTrue(result.editions().isEmpty());
        assertEquals(1, requests.size(), "Do not fetch metadata for a mismatched work");
    }

    @Test
    void successfulReadsAreCachedWithoutAnotherProviderRequest() {
        var first = service.forWork("/works/OL27482W");
        var cached = service.forWork("/works/OL27482W");
        assertEquals(first, cached);
        assertEquals(2, requests.size());
    }

    @Test
    void partialSourceFailurePreservesAvailableReviewsAndIsMarkedClearly() {
        secondEdition = true;
        var result = service.forWork("/works/OL27482W");
        assertEquals("partial", result.status());
        assertEquals(2, result.reviews().size());
        assertEquals(2, result.editions().size());
        assertTrue(result.message().contains("retry"));
        assertEquals(3, requests.size());
    }

    @Test
    void sourceFailureIsNotReportedAsNoReviewsAndCanBeRetried() {
        failSearch = true;
        var failure = service.forWork("/works/OL27482W");
        assertEquals("unavailable", failure.status());
        failSearch = false;
        var recovered = service.forWork("/works/OL27482W");
        assertEquals("ready", recovered.status());
        assertEquals(3, requests.size());
    }

    @Test
    void malformedSourceResponseIsNotTreatedAsAnEmptyReviewCollection() {
        invalidSearch = true;
        assertEquals("unavailable", service.forWork("/works/OL27482W").status());
    }

    @Test
    void offlineModeMakesNoExternalCalls() {
        var offline = new ExternalReviewService(mapper, false, baseUrl());
        assertEquals("disabled", offline.forWork("/works/OL27482W").status());
        assertTrue(requests.isEmpty());
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        Object response;
        int status = 200;
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/advancedsearch.php")) {
            if (failSearch) { status = 503; response = Map.of("error", "Unavailable"); }
            else if (invalidSearch) response = Map.of("response", Map.of("docs", "not-an-array"));
            else {
                Map<String, Object> first = Map.of("identifier", "hobbit-scan", "title", "The Hobbit",
                        "openlibrary_work", List.of(wrongWork ? "OL99999W" : "OL27482W"));
                var docs = secondEdition ? List.of(first, Map.of("identifier", "missing-scan", "title", "Another edition", "openlibrary_work", "OL27482W")) : List.of(first);
                response = Map.of("response", Map.of("numFound", docs.size(), "docs", docs));
            }
        } else if (path.equals("/metadata/hobbit-scan/reviews")) {
            Map<String, String> written = Map.of("reviewer", "Actual reader", "reviewer_itemname", "@reader", "reviewtitle", "<b>A real review</b>",
                    "reviewbody", "<script>bad()</script><p>Stored &amp; attributed " + "real source words ".repeat(60) + "</p>", "stars", "4", "reviewdate", "2026-10-12 10:20:30");
            response = Map.of("result", List.of(written, Map.of("reviewer", "No rating", "reviewbody", "A short opinion.", "stars", "0", "reviewdate", "2026-02-31 12:00:00"),
                    Map.of("reviewtitle", " ", "reviewbody", ""), written));
        } else { status = 503; response = Map.of("error", "Unavailable edition"); }
        byte[] body = mapper.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
}

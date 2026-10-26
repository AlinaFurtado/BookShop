package com.bookshelf.myBooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Work descriptions and exact identity lookups use local stubs only, never Open Library. */
class OpenLibraryDetailsTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private OpenLibraryService service;
    private volatile Object body;
    private volatile byte[] raw;
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        service = new OpenLibraryService(true, baseUrl());
        body = Map.of("description", "An actual source description.");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void readsStringAndObjectDescriptionsAsPlainBoundedText() {
        body = Map.of("description", "<p>Magic &amp; friendship.</p><script>bad()</script>");
        assertEquals("Magic & friendship.", service.description("/works/OL123W"));
        body = Map.of("description", Map.of("type", "/type/text", "value", "&lt;b&gt;A source paragraph.&lt;/b&gt; " + "z".repeat(3000)));
        String description = service.description("/works/OL124W");
        assertTrue(description.startsWith("A source paragraph. "));
        assertEquals(2000, description.length());
        assertFalse(description.contains("<") || description.contains("&lt;"));
        assertEquals("/works/OL123W.json", requests.getFirst());
        assertEquals("/works/OL124W.json", requests.get(1));
    }

    @Test
    void successfulAndMissingDescriptionsAreCachedButInvalidTextIsNotInvented() {
        assertEquals("An actual source description.", service.description("/works/OL123W"));
        body = Map.of("description", "A later server value");
        assertEquals("An actual source description.", service.description("/works/OL123W"));
        assertEquals(1, requests.size());
        body = Map.of("title", "No description supplied");
        assertEquals("", service.description("/works/OL124W"));
        assertEquals("", service.description("/works/OL124W"));
        assertEquals(2, requests.size(), "A valid response missing a description uses the short negative cache");
        body = Map.of("description", Map.of("value", 12345));
        assertEquals("", service.description("/works/OL125W"));
    }

    @Test
    void sourceErrorsOversizedAndMalformedBodiesDoNotBecomeSuccessfulCachedDescriptions() {
        status = 503;
        assertEquals("", service.description("/works/OL123W"));
        status = 200;
        assertEquals("An actual source description.", service.description("/works/OL123W"));
        raw = "x".repeat(1048577).getBytes(StandardCharsets.UTF_8);
        assertEquals("", service.description("/works/OL124W"));
        raw = "{invalid json".getBytes(StandardCharsets.UTF_8);
        assertEquals("", service.description("/works/OL124W"));
        raw = null;
        assertEquals("An actual source description.", service.description("/works/OL124W"));
        assertEquals(5, requests.size());
    }

    @Test
    void metadataUsesExactIdAndDoesNotFilterOlderChildrenOrForeignWorkTitles() {
        body = Map.of("docs", List.of(Map.of(
                "key", "/works/OL27482W", "title", "Le Hobbit", "author_name", List.of("J. R. R. Tolkien"),
                "first_publish_year", 1937, "ratings_average", 4.2, "ratings_count", 42,
                "cover_i", 123, "subject", List.of("Juvenile Fiction", "Fantasy"))));
        BookPreview book = service.metadata("/works/OL27482W");
        assertNotNull(book);
        assertEquals("/works/OL27482W", book.id());
        assertEquals("Le Hobbit", book.title());
        assertEquals("J. R. R. Tolkien", book.author());
        assertEquals("Kids", book.genre());
        assertEquals(1937, book.year());
        assertEquals(4.2, book.rating());
        assertEquals(42, book.ratingCount());
        assertEquals(123, book.coverId());
        assertEquals("openlibrary", book.source());
        assertEquals("", book.description());
        Map<String, String> parameters = parameters(requests.getFirst());
        assertEquals("key:\"/works/OL27482W\"", parameters.get("q"));
        assertEquals("1", parameters.get("limit"));
        assertFalse(parameters.containsKey("lang"));
        assertFalse(parameters.get("q").contains("language") || parameters.get("q").contains("first_publish_year"));
        assertSame(book, service.metadata("/works/OL27482W"));
        assertEquals(1, requests.size());
    }

    @Test
    void metadataRejectsSameTitleWithWrongIdAndPreservesMissingFields() {
        body = Map.of("docs", List.of(Map.of("key", "/works/OL999W", "title", "A Quiet Voyage", "author_name", List.of("Jane Reader"))));
        assertNull(service.metadata("/works/OL123W"));
        assertNull(service.metadata("/works/OL123W"));
        assertEquals(1, requests.size(), "A successful mismatched identity is negatively cached briefly");
        body = Map.of("docs", List.of(Map.of("key", "OL124W", "title", "Verified but incomplete")));
        BookPreview minimal = service.metadata("/works/OL124W");
        assertNotNull(minimal);
        assertEquals("Unknown author", minimal.author());
        assertNull(minimal.rating());
        assertNull(minimal.ratingCount());
        assertNull(minimal.year());
        assertNull(minimal.coverId());
    }

    @Test
    void badMetadataResponsesCanRecoverWithoutCachingTransportFailures() {
        status = 403;
        assertNull(service.metadata("/works/OL123W"));
        status = 200;
        body = Map.of("docs", "wrong type");
        assertNull(service.metadata("/works/OL123W"));
        body = Map.of("docs", List.of(Map.of("key", "/works/OL123W", "title", "Recovered record", "author_name", List.of("Jane Reader"))));
        assertEquals("Recovered record", service.metadata("/works/OL123W").title());
        assertEquals(3, requests.size());
    }

    @Test
    void arbitraryHostsPathsAndSearchSyntaxCannotTriggerAnyProviderRequest() {
        for (String id : List.of("https://example.test/books", "/works/../../secret", "/works/OL123W?key=x",
                "/works/OL123W\" OR *:*", "googlebooks:someId", "/works/OL123W.json")) {
            assertEquals("", service.description(id));
            assertNull(service.metadata(id));
        }
        OpenLibraryService offline = new OpenLibraryService(false, baseUrl());
        assertEquals("", offline.description("/works/OL123W"));
        assertNull(offline.metadata("/works/OL123W"));
        assertTrue(requests.isEmpty());
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void respond(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        byte[] bytes = raw == null ? mapper.writeValueAsBytes(body) : raw;
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
        catch (IOException closedEarlyForOversize) { /* The bounded client may close an oversized response. */ }
    }

    private static Map<String, String> parameters(String uri) {
        var result = new LinkedHashMap<String, String>();
        for (String pair : uri.substring(uri.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }
}

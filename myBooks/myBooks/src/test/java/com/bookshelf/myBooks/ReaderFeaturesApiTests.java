package com.bookshelf.myBooks;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the new reader features through actual HTTP, sessions, CSRF and isolated H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:reader-features-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bookbuddy.catalog.online=false"
})
class ReaderFeaturesApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @Test
    void directReadSaveAndFinishDatesPreserveUnknownDatesAndValidateEdits() throws Exception {
        Browser reader = registered("Date Reader");
        Map<String, Object> book = book("/works/OL987654321W");
        book.put("status", "READ");
        var created = reader.mutate("POST", "/api/shelf", book);
        assertEquals(201, created.statusCode(), created.body());
        JsonNode entry = node(created);
        long id = entry.path("id").asLong();
        assertEquals("READ", entry.path("status").asText());
        assertTrue(entry.path("finishDate").isNull(), "Already Read does not invent a past completion date");
        assertEquals(id, node(reader.mutate("POST", "/api/shelf", book)).path("id").asLong());
        String path = "/api/shelf/" + id + "/status";
        assertEquals(400, reader.mutate("PATCH", path,
                Map.of("status", "READ", "finishDate", LocalDate.now().plusDays(2).toString())).statusCode());
        var dated = reader.mutate("PATCH", path, Map.of("status", "READ", "finishDate", "2025-06-12"));
        assertEquals(200, dated.statusCode(), dated.body());
        assertEquals("2025-06-12", node(dated).path("finishDate").asText());
        assertEquals("2025-06-12", node(reader.mutate("PATCH", path, Map.of("status", "READ"))).path("finishDate").asText(),
                "An unchanged status does not overwrite a known finish date");
        var cleared = reader.mutate("PATCH", path, Map.of("status", "READ", "clearFinishDate", true));
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(node(cleared).path("finishDate").isNull());
        assertEquals(200, reader.mutate("PATCH", path,
                Map.of("status", "READ", "finishDate", LocalDate.now().toString())).statusCode());
        var reading = reader.mutate("PATCH", path, Map.of("status", "READING"));
        assertEquals(200, reading.statusCode(), reading.body());
        assertTrue(node(reading).path("finishDate").isNull(), "Moving out of Read clears its completion date");
    }

    @Test
    void directShelfSaveHonorsSelectedStatusAndRejectsInvalidState() throws Exception {
        Browser reader = registered("Shelf Reader");
        Map<String, Object> book = book("/works/OL987654322W");
        book.put("status", "READING");
        var created = reader.mutate("POST", "/api/shelf", book);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("READING", node(created).path("status").asText());
        Map<String, Object> invalid = book("/works/OL987654323W");
        invalid.put("status", "FINISHED");
        assertEquals(400, reader.mutate("POST", "/api/shelf", invalid).statusCode());
        invalid.put("status", "READ");
        invalid.put("finishDate", LocalDate.now().plusYears(1).toString());
        assertEquals(400, reader.mutate("POST", "/api/shelf", invalid).statusCode());
        Map<String, Object> dated = book("/works/OL987654324W");
        dated.put("status", "READ");
        dated.put("finishDate", "2024-02-20");
        assertEquals("2024-02-20", node(reader.mutate("POST", "/api/shelf", dated)).path("finishDate").asText());
    }

    @Test
    void genuineReviewsHaveSeparateCommunityAverageAndEnforceOwnership() throws Exception {
        Browser guest = new Browser();
        Browser alice = registered("Alice Reviewer");
        Browser bob = registered("Bob Reviewer");
        String workId = "/works/OL987654325W";
        String query = "/api/reviews?workId=" + workId;
        var empty = guest.get(query);
        assertEquals(200, empty.statusCode(), empty.body());
        assertEquals(0, node(empty).path("count").asInt());
        assertTrue(node(empty).path("average").isNull());
        Map<String, Object> review = Map.of("workId", workId, "rating", 4,
                "text", "A thoughtful story with an ending worth discussing.", "spoiler", true);
        assertEquals(401, guest.mutate("POST", "/api/reviews", review).statusCode());
        assertEquals(403, guest.send("POST", "/api/reviews", review, null, null).statusCode());
        var first = alice.mutate("POST", "/api/reviews", review);
        assertEquals(201, first.statusCode(), first.body());
        long firstId = node(first).path("id").asLong();
        assertEquals(201, bob.mutate("POST", "/api/reviews", Map.of("workId", workId, "rating", 2,
                "text", "The pacing did not work for me.", "spoiler", false)).statusCode());
        JsonNode community = node(guest.get(query));
        assertEquals(2, community.path("count").asInt());
        assertEquals(3.0, community.path("average").asDouble(), 0.0001);
        assertEquals(2, community.path("reviews").size());
        assertEquals(firstId, node(alice.get(query)).path("currentUserReview").path("id").asLong());
        String path = "/api/reviews/" + firstId;
        assertEquals(404, bob.mutate("PUT", path, Map.of("rating", 5, "text", "Not my review", "spoiler", false)).statusCode());
        assertEquals(404, bob.mutate("DELETE", path, null).statusCode());
        var edited = alice.mutate("PUT", path, Map.of("rating", 5, "text", "Rereading changed my opinion.", "spoiler", false));
        assertEquals(200, edited.statusCode(), edited.body());
        community = node(guest.get(query));
        assertEquals(2, community.path("count").asInt());
        assertEquals(3.5, community.path("average").asDouble(), 0.0001);
        assertEquals(204, alice.mutate("DELETE", path, null).statusCode());
        assertEquals(1, node(guest.get(query)).path("count").asInt());
        assertTrue(node(alice.get(query)).path("currentUserReview").isNull());
    }

    @Test
    void reviewValidationRejectsInvalidRatingsAndEmptyText() throws Exception {
        Browser reader = registered("Validation Reader");
        String workId = "/works/OL987654326W";
        assertEquals(400, reader.mutate("POST", "/api/reviews",
                Map.of("workId", workId, "rating", 6, "text", "A review", "spoiler", false)).statusCode());
        assertEquals(400, reader.mutate("POST", "/api/reviews",
                Map.of("workId", workId, "rating", 4, "text", "   ", "spoiler", false)).statusCode());
        assertEquals(400, reader.mutate("POST", "/api/reviews",
                Map.of("workId", workId, "rating", 4, "text", "x".repeat(2001), "spoiler", false)).statusCode());
    }

    private Map<String, Object> book(String workId) {
        return new HashMap<>(Map.of("id", workId, "title", "A Reader Test Book", "author", "A Test Author", "genre", "Fantasy"));
    }

    private Browser registered(String name) throws Exception {
        Browser browser = new Browser();
        var result = browser.mutate("POST", "/api/auth/register", Map.of("name", name,
                "email", UUID.randomUUID() + "@example.test", "password", "bookworm123"));
        assertEquals(201, result.statusCode(), result.body());
        return browser;
    }

    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    private class Browser {
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }

        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            JsonNode csrf = node(get("/api/auth/csrf"));
            return send(method, path, body, csrf.path("headerName").asText(), csrf.path("token").asText());
        }

        HttpResponse<String> send(String method, String path, Object body, String header, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
            if (header != null) request.header(header, token);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}

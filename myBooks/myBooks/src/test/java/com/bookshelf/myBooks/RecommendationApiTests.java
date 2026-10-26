package com.bookshelf.myBooks;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP exercises login, CSRF and input size without contacting AI or book providers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:recommendation-api;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "bookbuddy.catalog.online=false",
        "bookbuddy.ai.enabled=false", "bookbuddy.ai.api-key=", "bookbuddy.google-books.api-key="
})
class RecommendationApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @Test
    void statusIsPublicAndRecommendationSearchNeedsBothSessionAndCsrf() throws Exception {
        Browser browser = new Browser();
        var status = browser.get("/api/recommendations/status");
        assertEquals(200, status.statusCode());
        assertFalse(mapper.readTree(status.body()).path("available").asBoolean());
        var request = Map.of("prompt", "A cosy story about magic and friendship");
        assertEquals(403, browser.post("/api/recommendations", request, false).statusCode());
        assertEquals(401, browser.post("/api/recommendations", request, true).statusCode());
        var registered = browser.post("/api/auth/register", Map.of("name", "Pip reader",
                "email", "pip-" + System.nanoTime() + "@example.test", "password", "bookworm123"), true);
        assertEquals(201, registered.statusCode(), registered.body());
        var disabled = browser.post("/api/recommendations", request, true);
        assertEquals(503, disabled.statusCode(), disabled.body());
        assertTrue(disabled.body().contains("not enabled"));
        assertEquals(400, browser.post("/api/recommendations", Map.of("prompt", "tiny"), true).statusCode());
        assertEquals(400, browser.post("/api/recommendations", Map.of("prompt", request.get("prompt"), "genre", "Unsupported"), true).statusCode());
        assertEquals(400, browser.post("/api/recommendations", Map.of("prompt", request.get("prompt"), "minRating", 8), true).statusCode());
        assertEquals(413, browser.post("/api/recommendations", Map.of("prompt", request.get("prompt"), "unknown", "x".repeat(9000)), true).statusCode());
    }

    @Test
    void googleBooksIdsRoundTripThroughShelfReadPassportAndLocalReviews() throws Exception {
        Browser browser = new Browser();
        assertEquals(201, browser.post("/api/auth/register", Map.of("name", "Google reader",
                "email", "google-" + System.nanoTime() + "@example.test", "password", "bookworm123"), true).statusCode());
        String workId = "googlebooks:real_Volume-1";
        Map<String, Object> book = Map.of("id", workId, "title", "A sourced book", "author", "An author",
                "genre", "Fantasy", "year", 2026, "description", "Catalog description", "source", "googlebooks");
        var added = browser.post("/api/shelf", book, true);
        assertEquals(201, added.statusCode(), added.body());
        JsonNode entry = mapper.readTree(added.body());
        long shelfId = entry.path("id").asLong();
        assertEquals(workId, entry.path("workId").asText());
        assertEquals("WANT_TO_READ", entry.path("status").asText());
        var read = browser.mutate("PATCH", "/api/shelf/" + shelfId + "/status",
                Map.of("status", "READ", "finishDate", LocalDate.now().minusDays(1).toString()), true);
        assertEquals(200, read.statusCode(), read.body());
        var passport = browser.mutate("PATCH", "/api/shelf/" + shelfId + "/passport",
                Map.of("destination", "The reading nook", "memory", "A private reflection"), true);
        assertEquals(200, passport.statusCode(), passport.body());
        JsonNode saved = mapper.readTree(browser.get("/api/shelf").body()).get(0);
        assertEquals(workId, saved.path("workId").asText());
        assertEquals("READ", saved.path("status").asText());
        assertEquals("A private reflection", saved.path("passportMemory").asText());

        var review = browser.post("/api/reviews", Map.of("workId", workId, "rating", 4,
                "text", "A real reader's review", "spoiler", false), true);
        assertEquals(201, review.statusCode(), review.body());
        long reviewId = mapper.readTree(review.body()).path("id").asLong();
        var edited = browser.mutate("PATCH", "/api/reviews/" + reviewId,
                Map.of("workId", workId, "rating", 5, "text", "An updated reader review", "spoiler", true), true);
        assertEquals(200, edited.statusCode(), edited.body());
        var reviews = browser.get("/api/reviews?workId=" + URLEncoder.encode(workId, StandardCharsets.UTF_8));
        JsonNode loaded = mapper.readTree(reviews.body());
        assertEquals(200, reviews.statusCode());
        assertEquals(1, loaded.path("count").asInt());
        assertEquals("An updated reader review", loaded.path("reviews").get(0).path("text").asText());
        assertTrue(loaded.path("reviews").get(0).path("spoiler").asBoolean());

        for (String invalid : List.of("googlebooks:", "googlebooks:../../x", "googlebooks:" + "x".repeat(81), "https://example.test/book")) {
            assertEquals(400, browser.post("/api/shelf", Map.of("id", invalid, "title", "A title", "author", "An author"), true).statusCode());
            assertEquals(400, browser.post("/api/reviews", Map.of("workId", invalid, "rating", 4,
                    "text", "Should not be saved", "spoiler", false), true).statusCode());
            assertEquals(400, browser.get("/api/reviews?workId=" + URLEncoder.encode(invalid, StandardCharsets.UTF_8)).statusCode());
        }
        assertEquals(204, browser.mutate("DELETE", "/api/reviews/" + reviewId, null, true).statusCode());
        assertEquals(204, browser.mutate("DELETE", "/api/shelf/" + shelfId, null, true).statusCode());
    }

    private class Browser {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, Object body, boolean csrf) throws Exception {
            return mutate("POST", path, body, csrf);
        }

        HttpResponse<String> mutate(String method, String path, Object body, boolean csrf) throws Exception {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
            if (csrf) {
                JsonNode token = mapper.readTree(get("/api/auth/csrf").body());
                builder.header(token.path("headerName").asText(), token.path("token").asText());
            }
            return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    }
}

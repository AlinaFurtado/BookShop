package com.bookshelf.myBooks;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests exercise cookies, CSRF, authorization, controllers and H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bookbuddy.catalog.online=false"
})
class BookBuddyApiTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ShelfEntryRepository shelf;
    @Autowired AppUserRepository users;

    @BeforeEach
    void resetDatabase() {
        shelf.deleteAll();
        users.deleteAll();
    }

    @Test
    void registrationLoginAndCsrfProtectTheShelf() throws Exception {
        Browser reader = new Browser();
        assertEquals(401, reader.get("/api/shelf").statusCode());
        assertEquals(403, reader.rawMutation("POST", "/api/auth/register",
                Map.of("name", "Reader", "email", "reader@example.test", "password", "bookworm123")).statusCode());
        var registered = reader.register("Reader", "reader@example.test");
        assertEquals(201, registered.statusCode(), registered.body());
        assertEquals("Reader", node(registered).path("name").asText());
        assertFalse(registered.body().contains("password"));
        assertEquals(200, reader.get("/api/auth/me").statusCode());
        assertEquals(204, reader.mutate("POST", "/api/auth/logout", null).statusCode());
        assertEquals(401, reader.get("/api/auth/me").statusCode());
        assertEquals(401, reader.mutate("POST", "/api/auth/login",
                Map.of("email", "reader@example.test", "password", "wrong-password")).statusCode());
        assertEquals(200, reader.mutate("POST", "/api/auth/login",
                Map.of("email", "reader@example.test", "password", "bookworm123")).statusCode());
        assertEquals(200, reader.get("/api/auth/me").statusCode());
    }

    @Test
    void shelfIsPersistentIdempotentAndPrivateToEachReader() throws Exception {
        Browser alice = new Browser();
        Browser bob = new Browser();
        assertEquals(201, alice.register("Alice", "alice@example.test").statusCode());
        Map<String, Object> book = Map.of("id", "/works/OL27482W", "title", "The Hobbit",
                "author", "J.R.R. Tolkien", "genre", "Fantasy");
        var added = alice.mutate("POST", "/api/shelf", book);
        assertEquals(201, added.statusCode(), added.body());
        long id = node(added).path("id").asLong();
        assertEquals("/works/OL27482W", node(added).path("workId").asText());
        assertEquals("WANT_TO_READ", node(added).path("status").asText());
        assertFalse(added.body().contains("ownerId"));
        assertEquals(id, node(alice.mutate("POST", "/api/shelf", book)).path("id").asLong());
        assertEquals(1, node(alice.get("/api/shelf")).size());
        assertEquals(400, alice.mutate("PATCH", "/api/shelf/" + id + "/status", Map.of("status", "FINISHED")).statusCode());
        var changed = alice.mutate("PATCH", "/api/shelf/" + id + "/status", Map.of("status", "READ"));
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals("READ", node(alice.get("/api/shelf")).get(0).path("status").asText());
        assertEquals(201, bob.register("Bob", "bob@example.test").statusCode());
        assertEquals(0, node(bob.get("/api/shelf")).size());
        assertEquals(404, bob.mutate("PATCH", "/api/shelf/" + id + "/status", Map.of("status", "READING")).statusCode());
        assertEquals(404, bob.mutate("DELETE", "/api/shelf/" + id, null).statusCode());
        assertEquals(204, alice.mutate("DELETE", "/api/shelf/" + id, null).statusCode());
        assertEquals(0, node(alice.get("/api/shelf")).size());
    }

    @Test
    void firstRegistrationPreservesTheOldPreLoginBookshelf() throws Exception {
        BookPreview legacyBook = new BookPreview("/works/OL27482W", "The Hobbit", "J.R.R. Tolkien",
                "Fantasy", null, null, null, 1937, "", "curated", null, null);
        shelf.save(new ShelfEntry(null, legacyBook));
        ShelfEntry readDuplicate = new ShelfEntry(null, legacyBook);
        readDuplicate.setStatus("READ");
        long preferredId = shelf.save(readDuplicate).getId();
        Browser first = new Browser();
        Browser second = new Browser();
        assertEquals(201, first.register("First", "first@example.test").statusCode());
        assertEquals(1, node(first.get("/api/shelf")).size());
        assertEquals(preferredId, node(first.get("/api/shelf")).get(0).path("id").asLong());
        assertEquals("READ", node(first.get("/api/shelf")).get(0).path("status").asText());
        assertEquals(1, shelf.findByOwnerIdIsNullOrderByIdAsc().size(), "Keep duplicate legacy row as a backup");
        assertEquals(201, second.register("Second", "second@example.test").statusCode());
        assertEquals(0, node(second.get("/api/shelf")).size());
    }

    @Test
    void catalogWorksOfflineAndFiltersNeverInventRatings() throws Exception {
        Browser browser = new Browser();
        var featured = browser.get("/api/books/featured");
        assertEquals(200, featured.statusCode(), featured.body());
        assertFalse(node(featured).isEmpty());
        assertTrue(node(featured).size() <= 11);
        PublicationWindow recent = PublicationWindow.forRequest("", false);
        node(featured).forEach(book -> assertTrue(recent.contains(book.path("year").asInt())));
        JsonNode fantasy = node(browser.get("/api/books/featured?genre=Fantasy"));
        assertFalse(fantasy.isEmpty());
        fantasy.forEach(book -> assertEquals("Fantasy", book.path("genre").asText()));
        JsonNode rated = node(browser.get("/api/books/featured?minRating=4"));
        rated.forEach(book -> assertTrue(book.path("rating").asDouble() >= 4));
        assertEquals(400, browser.get("/api/books/featured?minRating=9").statusCode());
        assertTrue(node(browser.get("/api/books/search?title=Hobbit")).isEmpty());
        JsonNode found = node(browser.get("/api/books/search?title=Hobbit&genre=Kids"));
        assertTrue(found.toString().contains("The Hobbit"));
        found.forEach(book -> assertEquals("Kids", book.path("genre").asText()));
        var health = browser.get("/api/health");
        assertEquals(200, health.statusCode());
        assertEquals("H2", node(health).path("database").asText(), "Adding the PostgreSQL driver must not change the default test database");
        var trends = browser.get("/api/books/trending");
        assertEquals(200, trends.statusCode(), "Guests can read current trends");
        assertEquals("unavailable", node(trends).path("source").asText(), "Offline books must not be presented as current trends");
        assertTrue(node(trends).path("books").isEmpty());
        assertEquals(400, browser.get("/api/books/trending?genre=Invalid").statusCode());
        assertEquals(400, browser.get("/api/books/trending?minRating=7").statusCode());
        assertEquals(400, browser.get("/api/books/trending?page=2&cursor=curated:11").statusCode());
    }

    @Test
    void externalReviewsAreAPublicReadAndRejectInvalidWorkIds() throws Exception {
        Browser guest = new Browser();
        var reviews = guest.get("/api/reviews/external?workId=/works/OL27482W");
        assertEquals(200, reviews.statusCode(), reviews.body());
        assertEquals("Internet Archive", node(reviews).path("source").asText());
        assertEquals("disabled", node(reviews).path("status").asText(), "This test uses offline mode");
        assertTrue(node(reviews).path("reviews").isEmpty());
        assertEquals(400, guest.get("/api/reviews/external?workId=not-a-work").statusCode());
    }

    private JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    private class Browser {
        final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> register(String name, String email) throws Exception {
            return mutate("POST", "/api/auth/register", Map.of("name", name, "email", email, "password", "bookworm123"));
        }

        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            JsonNode csrf = node(get("/api/auth/csrf"));
            return send(method, path, body, csrf.path("headerName").asText(), csrf.path("token").asText());
        }

        HttpResponse<String> rawMutation(String method, String path, Object body) throws Exception {
            return send(method, path, body, null, null);
        }

        private HttpResponse<String> send(String method, String path, Object body, String header, String token) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (header != null) request.header(header, token);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    }
}

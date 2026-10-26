package com.bookshelf.myBooks;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Passport reflection tests use real HTTP/session/CSRF and a separate in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:passport-api-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "bookbuddy.catalog.online=false"
})
class PassportApiTests {
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
    void existingReadBookIsAutomaticallyEligibleWithoutInventedReflectionsOrFinishDate() throws Exception {
        ShelfEntry legacy = new ShelfEntry(null, new BookPreview("/works/OL27482W", "The Hobbit", "J.R.R. Tolkien",
                "Fantasy", null, null, null, 1937, "A saved book.", "curated", null, null));
        legacy.setStatus("READ");
        long id = shelf.save(legacy).getId();
        Browser reader = registered("Passport Reader");

        JsonNode loaded = node(reader.get("/api/shelf"));
        assertEquals(1, loaded.size(), "The existing READ row needs no separate passport creation");
        assertEquals(id, loaded.get(0).path("id").asLong());
        assertTrue(loaded.get(0).path("finishDate").isNull());
        assertTrue(loaded.get(0).path("passportDestination").isNull());
        assertTrue(loaded.get(0).path("passportMemory").isNull());
        String path = "/api/shelf/" + id + "/passport";
        var saved = reader.mutate("PATCH", path, Map.of("destination", "The Shire", "mood", "Comforted",
                "favoriteCharacter", "Bilbo", "quote", "A short quote I chose.", "memory", "Read on a rainy afternoon."));
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("The Hobbit", node(saved).path("title").asText());
        assertEquals("J.R.R. Tolkien", node(saved).path("author").asText());
        assertEquals("/works/OL27482W", node(saved).path("workId").asText());
        assertTrue(node(saved).path("finishDate").isNull(), "Saving reflections does not invent a completion date");
        JsonNode reloaded = node(reader.get("/api/shelf")).get(0);
        assertEquals("The Shire", reloaded.path("passportDestination").asText());
        assertEquals("Read on a rainy afternoon.", reloaded.path("passportMemory").asText());

        Map<String, Object> partial = new HashMap<>();
        partial.put("destination", "");
        partial.put("mood", "Inspired");
        partial.put("memory", null);
        var updated = reader.mutate("PATCH", path, partial);
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(node(updated).path("passportDestination").isNull(), "Empty text clears a reflection");
        assertEquals("Inspired", node(updated).path("passportMood").asText());
        assertEquals("Bilbo", node(updated).path("passportFavoriteCharacter").asText(), "Omitted fields remain unchanged");
        assertEquals("Read on a rainy afternoon.", node(updated).path("passportMemory").asText(), "Null leaves a note unchanged");
    }

    @Test
    void passportIsPrivateProtectedByCsrfAndOnlyEditableForOwnedReadBooks() throws Exception {
        Browser alice = registered("Alice Passport");
        Browser bob = registered("Bob Passport");
        Browser guest = new Browser();
        long id = addBook(alice, "/works/OL987654331W", "READ");
        String path = "/api/shelf/" + id + "/passport";
        Map<String, String> notes = Map.of("memory", "A private memory.");
        assertEquals(401, guest.mutate("PATCH", path, notes).statusCode());
        assertEquals(403, alice.send("PATCH", path, notes, null, null).statusCode());
        assertEquals(404, bob.mutate("PATCH", path, notes).statusCode());
        assertEquals(0, node(bob.get("/api/shelf")).size());
        assertEquals(200, alice.mutate("PATCH", path, notes).statusCode());

        long reading = addBook(alice, "/works/OL987654332W", "READING");
        assertEquals(409, alice.mutate("PATCH", "/api/shelf/" + reading + "/passport", notes).statusCode());
        assertEquals(400, alice.mutate("PATCH", path, Map.of("title", "Replace metadata")).statusCode());
        assertEquals(400, alice.mutate("PATCH", path, Map.of("status", "READING")).statusCode());
        JsonNode original = node(alice.get("/api/shelf"));
        for (JsonNode entry : original) {
            if (entry.path("id").asLong() == id) {
                assertEquals("Passport Test Book", entry.path("title").asText());
                assertEquals("READ", entry.path("status").asText());
            }
        }
    }

    @Test
    void reflectionLimitsAreValidatedAndNotesSurviveStatusChanges() throws Exception {
        Browser reader = registered("Validation Passport");
        long id = addBook(reader, "/works/OL987654333W", "READ");
        String path = "/api/shelf/" + id + "/passport";
        Map<String, Integer> limits = Map.of("destination", 120, "mood", 80,
                "favoriteCharacter", 120, "quote", 600, "memory", 2000);
        for (var field : limits.entrySet()) {
            assertEquals(400, reader.mutate("PATCH", path, Map.of(field.getKey(), "x".repeat(field.getValue() + 1))).statusCode());
        }
        assertEquals(400, reader.mutate("PATCH", path, Map.of("mood", 42)).statusCode());
        assertEquals(400, reader.mutate("PATCH", path, Map.of("mood", "Should not be saved", "memory", "x".repeat(2001))).statusCode());
        assertTrue(node(reader.get("/api/shelf")).get(0).path("passportMood").isNull());
        assertEquals(200, reader.mutate("PATCH", path, Map.of("memory", "Keep this reflection.")).statusCode());
        assertEquals(200, reader.mutate("PATCH", "/api/shelf/" + id + "/status", Map.of("status", "READING")).statusCode());
        JsonNode reading = node(reader.get("/api/shelf")).get(0);
        assertEquals("Keep this reflection.", reading.path("passportMemory").asText());
        assertTrue(reading.path("finishDate").isNull());
        assertEquals(200, reader.mutate("PATCH", "/api/shelf/" + id + "/status", Map.of("status", "READ")).statusCode());
        JsonNode readAgain = node(reader.get("/api/shelf")).get(0);
        assertEquals("Keep this reflection.", readAgain.path("passportMemory").asText());
        assertTrue(readAgain.path("finishDate").isNull());
        assertEquals(1, shelf.count(), "Status cycles do not create duplicate passports or saved entries");
    }

    private long addBook(Browser reader, String workId, String status) throws Exception {
        var response = reader.mutate("POST", "/api/shelf", Map.of("id", workId, "title", "Passport Test Book",
                "author", "Test Author", "genre", "Fantasy", "status", status));
        assertEquals(201, response.statusCode(), response.body());
        return node(response).path("id").asLong();
    }

    private Browser registered(String name) throws Exception {
        Browser reader = new Browser();
        var response = reader.mutate("POST", "/api/auth/register", Map.of("name", name,
                "email", UUID.randomUUID() + "@example.test", "password", "bookworm123"));
        assertEquals(201, response.statusCode(), response.body());
        return reader;
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

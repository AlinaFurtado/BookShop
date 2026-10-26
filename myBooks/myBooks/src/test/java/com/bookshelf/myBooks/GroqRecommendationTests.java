package com.bookshelf.myBooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Fake provider only. No real key, Groq request, paid API or local model. */
class GroqRecommendationTests {
    private HttpServer server;
    private GroqRecommendationClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private volatile Object answer;
    private volatile int status;
    private volatile String finishReason;

    @BeforeEach
    void start() throws IOException {
        answer = Map.of("genre", "Kids", "keywords", List.of("friendship", "magic"), "avoid", List.of("horror"));
        status = 200;
        finishReason = "stop";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::respond);
        server.start();
        client = new GroqRecommendationClient(mapper, true, true, "private-test-key", baseUrl(), "openai/gpt-oss-20b");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void extractsBoundedPreferencesWithStrictSchemaAndGenreOverride() {
        var preferences = client.extract("A magical adventure about friendship", "Fantasy");
        assertEquals("Fantasy", preferences.genre());
        assertEquals(List.of("friendship", "magic"), preferences.keywords());
        assertEquals(List.of("horror"), preferences.avoid());
        var request = requests.getFirst();
        assertEquals("openai/gpt-oss-20b", request.path("model").asText());
        assertTrue(request.path("response_format").path("json_schema").path("strict").asBoolean());
        assertFalse(request.toString().contains("private-test-key"));
        assertFalse(request.has("tools"));
        assertEquals(1200, request.path("max_completion_tokens").asInt());
        assertEquals("low", request.path("reasoning_effort").asText());
        assertFalse(request.path("include_reasoning").asBoolean());
    }

    @Test
    void aModelCannotImplicitlySwitchTheAudienceToKids() {
        assertEquals("", client.extract("A magical adventure about friendship", "").genre());
        assertEquals("Kids", client.extract("A magical adventure about friendship", "Kids").genre());
    }

    @Test
    void recommendationsContainOnlySuppliedUniqueIdsAndLiteralSourceEvidence() {
        List<Map<String, String>> selected = new ArrayList<>();
        selected.add(Map.of("id", "invented", "reason", "invented book", "evidence", "Fantasy"));
        selected.add(Map.of("id", "/works/OL1W", "reason", "This is a made-up plot", "evidence", "Fantasy"));
        selected.add(Map.of("id", "/works/OL1W", "reason", "duplicate", "evidence", "Fantasy"));
        selected.add(Map.of("id", "/works/OL2W", "reason", "wrong evidence", "evidence", "nonexistent dragons"));
        for (int id = 3; id <= 7; id++) selected.add(Map.of("id", "/works/OL" + id + "W", "reason", "genre match", "evidence", "Fantasy"));
        answer = Map.of("recommendations", selected);
        List<BookPreview> candidates = new ArrayList<>();
        for (int id = 1; id <= 7; id++) candidates.add(book(id));
        var result = client.rank("A magical adventure about friendship", new RecommendationPreferences("Fantasy", List.of(), List.of()), candidates);
        assertEquals(4, result.size());
        assertEquals(List.of("/works/OL1W", "/works/OL3W", "/works/OL4W", "/works/OL5W"), result.stream().map(BookPreview::id).toList());
        result.forEach(book -> {
            assertTrue(book.reason().contains("Fantasy"));
            assertFalse(book.reason().contains("made-up plot"), "Do not expose generated unsupported plot claims");
        });
        assertEquals("Source description", result.getFirst().description(), "The model must not replace source metadata");
    }

    @Test
    void providerFailuresAreSanitizedAndPreserveCooldownWithoutRetries() {
        status = 429;
        var failure = assertThrows(GroqRecommendationClient.ProviderFailure.class,
                () -> client.extract("Please recommend something magical", ""));
        assertEquals(429, failure.status());
        assertEquals(120, failure.retryAfterSeconds());
        assertFalse(failure.getMessage().contains("private-test-key"));
        assertFalse(failure.getMessage().contains("remote-secret"));
        assertEquals(1, requests.size());
    }

    @Test
    void truncatedResponsesAreRejectedRatherThanParsedAsRecommendations() {
        finishReason = "length";
        assertThrows(GroqRecommendationClient.ProviderFailure.class,
                () -> client.extract("Please recommend something magical", ""));
        assertEquals(1, requests.size());
    }

    @Test
    void missingKeyOfflineModeAndUnapprovedModelMakeNoProviderRequests() {
        for (var disabled : List.of(new GroqRecommendationClient(mapper, true, false, "private-test-key", baseUrl(), "openai/gpt-oss-20b"),
                new GroqRecommendationClient(mapper, false, true, "private-test-key", baseUrl(), "openai/gpt-oss-20b"),
                new GroqRecommendationClient(mapper, true, true, "", baseUrl(), "openai/gpt-oss-20b"),
                new GroqRecommendationClient(mapper, true, true, "private-test-key", baseUrl(), "another-model"))) {
            assertFalse(disabled.available());
            assertThrows(GroqRecommendationClient.ProviderFailure.class,
                    () -> disabled.extract("Please recommend something magical", ""));
        }
        assertTrue(requests.isEmpty());
    }

    private static BookPreview book(int id) {
        return new BookPreview("/works/OL" + id + "W", "A real title " + id, "Actual author", "Fantasy",
                null, null, null, 2026, "Source description", "Open Library", List.of("friendship"), null);
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void respond(HttpExchange exchange) throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        assertEquals("Bearer private-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
        assertNull(exchange.getRequestURI().getQuery(), "Never put the provider key in a URL");
        requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
        Object envelope = status == 200 ? Map.of("choices", List.of(Map.of("finish_reason", finishReason,
                "message", Map.of("content", mapper.writeValueAsString(answer)))))
                : Map.of("error", "remote-secret private-test-key");
        byte[] body = mapper.writeValueAsBytes(envelope);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Retry-After", "120");
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }
}

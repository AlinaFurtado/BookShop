package com.bookshelf.myBooks;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Only reader-written reflections can be changed by the passport endpoint. */
public record PassportUpdate(String destination, String mood, String favoriteCharacter,
        String quote, String memory) {
    private static final Set<String> FIELDS = Set.of("destination", "mood", "favoriteCharacter", "quote", "memory");

    public static PassportUpdate from(JsonNode body) {
        if (body == null || !body.isObject()) badRequest("Send a passport reflection object.");
        for (var field : body.properties()) {
            if (!FIELDS.contains(field.getKey())) badRequest("Only passport reflections can be edited here.");
            if (!field.getValue().isNull() && !field.getValue().isTextual()) {
                badRequest("Passport reflections must be text.");
            }
        }
        return new PassportUpdate(text(body, "destination", 120), text(body, "mood", 80),
                text(body, "favoriteCharacter", 120), text(body, "quote", 600), text(body, "memory", 2000));
    }

    private static String text(JsonNode body, String field, int maximum) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return null; // Missing/null leaves the saved note unchanged.
        String text = value.asText();
        if (text.length() > maximum) badRequest(field + " must be at most " + maximum + " characters.");
        return text.strip(); // An explicit empty string clears this note.
    }

    private static void badRequest(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

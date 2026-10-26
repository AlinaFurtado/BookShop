package com.bookshelf.myBooks;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    private final RecommendationService recommendations;
    private final GoogleBooksService googleBooks;
    private final ObjectMapper mapper;

    public record Status(boolean available, boolean googleBooksAvailable, String message) { }

    public RecommendationController(RecommendationService recommendations, GoogleBooksService googleBooks, ObjectMapper mapper) {
        this.recommendations = recommendations;
        this.googleBooks = googleBooks;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Status status() {
        boolean enabled = recommendations.available();
        return new Status(enabled, googleBooks.available(), enabled
                ? "Describe your next read. AI searches require sign-in and use a limited shared free allowance."
                : "AI recommendations are not enabled here yet. Regular book search is available.");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecommendationService.Response recommend(HttpServletRequest request, Authentication reader) {
        if (reader == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in to ask Pip for recommendations.");
        RecommendationService.Request body;
        try {
            // Bound raw JSON too: an unknown field or an oversized body must not consume unbounded memory.
            if (request.getContentLengthLong() > 8192) throw oversized();
            byte[] bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw oversized();
            body = mapper.readValue(bytes, RecommendationService.Request.class);
        } catch (ResponseStatusException failure) { throw failure; }
        catch (IOException | RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The recommendation request contains an invalid value.");
        }
        return recommendations.recommend(reader.getName(), body);
    }

    private static ResponseStatusException oversized() {
        return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "The recommendation request is too large.");
    }
}

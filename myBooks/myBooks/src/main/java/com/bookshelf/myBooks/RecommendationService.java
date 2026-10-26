package com.bookshelf.myBooks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationService {
    private final GroqRecommendationClient ai;
    private final RecommendationCatalog catalog;
    private final RecommendationRateLimiter limiter;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private record Cached(long expiresAt, Response value) { }

    public record Request(String prompt, String genre, Double minRating, Boolean includeOlder) { }
    public record Response(List<BookPreview> books, String message, List<String> sources,
            boolean cached, Integer minPublicationYear, int maxPublicationYear) { }

    public RecommendationService(GroqRecommendationClient ai, RecommendationCatalog catalog,
            RecommendationRateLimiter limiter) {
        this.ai = ai;
        this.catalog = catalog;
        this.limiter = limiter;
    }

    public boolean available() { return ai.available(); }

    public Response recommend(String reader, Request request) {
        Request checked = validate(request);
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI recommendations are not enabled yet. Regular book search is available.");
        String key = cacheKey(reader, checked);
        Response cached = cached(key);
        if (cached != null) return cached;
        try (var ticket = limiter.enter(reader)) {
            RecommendationPreferences preferences = ai.extract(checked.prompt(), checked.genre());
            RecommendationCatalog.CandidateBatch candidates = catalog.candidates(preferences,
                    checked.genre(), checked.minRating(), checked.includeOlder());
            List<BookPreview> matches = ai.rank(checked.prompt(), preferences, candidates.books());
            int currentYear = Year.now(ZoneOffset.UTC).getValue();
            String message = matches.isEmpty()
                    ? "There was not enough verified catalog information for a confident match. Try broader preferences or regular book search."
                    : "Pip selected " + matches.size() + " possible matches from real catalog records. Check the details for your preferences.";
            if (candidates.note() != null && !candidates.note().isBlank()) message += " " + candidates.note();
            Response result = new Response(matches, message, List.copyOf(candidates.sources()), false,
                    checked.includeOlder() ? null : currentYear - 4, currentYear);
            // Successful empty results are also cached: repeated clicks must not burn provider quota.
            cache(key, result);
            return result;
        } catch (GroqRecommendationClient.ProviderFailure failure) {
            if (failure.retryAfterSeconds() > 0) limiter.pause(Duration.ofSeconds(failure.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.valueOf(failure.status()), failure.getMessage());
        }
    }

    private synchronized Response cached(String key) {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Cached cached = cache.get(key);
        if (cached == null) return null;
        Response value = cached.value();
        return new Response(value.books(), value.message(), value.sources(), true,
                value.minPublicationYear(), value.maxPublicationYear());
    }

    private synchronized void cache(String key, Response response) {
        if (cache.size() >= 100) cache.remove(cache.keySet().iterator().next());
        cache.put(key, new Cached(System.currentTimeMillis() + Duration.ofMinutes(20).toMillis(), response));
    }

    static Request validate(Request request) {
        if (request == null || request.prompt() == null) invalid("Describe the kind of book you would like to read.");
        String prompt = request.prompt().strip();
        if (prompt.length() < 10 || prompt.length() > 600
                || prompt.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t')) {
            invalid("Please use between 10 and 600 characters for your reading request.");
        }
        String genre = request.genre() == null ? "" : request.genre().strip();
        if (!genre.isEmpty() && !GroqRecommendationClient.GENRES.contains(genre)) invalid("Choose a supported genre.");
        Double rating = request.minRating();
        if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 5)) invalid("Rating must be between 0 and 5.");
        return new Request(prompt, genre, rating, Boolean.TRUE.equals(request.includeOlder()));
    }

    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private static String cacheKey(String reader, Request request) {
        String material = reader + "\n" + request.prompt() + "\n" + request.genre()
                + "\n" + request.minRating() + "\n" + request.includeOlder();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}

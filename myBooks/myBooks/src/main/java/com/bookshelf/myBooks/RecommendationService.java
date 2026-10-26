package com.bookshelf.myBooks;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    public record Request(String prompt, String genre, Double minRating, Boolean includeOlder,
            Boolean refresh, List<String> excludeIds) {
        public Request(String prompt, String genre, Double minRating, Boolean includeOlder) {
            this(prompt, genre, minRating, includeOlder, false, List.of());
        }
    }
    public record Response(List<BookPreview> books, String message, List<String> sources,
            boolean cached, Integer minPublicationYear, int maxPublicationYear,
            List<ProviderOutcome> providers, BookPreview referenceBook) {
        public Response(List<BookPreview> books, String message, List<String> sources,
                boolean cached, Integer minPublicationYear, int maxPublicationYear) {
            this(books, message, sources, cached, minPublicationYear, maxPublicationYear, List.of(), null);
        }
    }

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
        Response cached = checked.refresh() ? null : cached(key);
        if (cached != null) return cached;
        List<ProviderOutcome> providers = new ArrayList<>();
        BookPreview reference = null;
        try (var ticket = limiter.enter(reader)) {
            RecommendationPreferences preferences = ai.extract(checked.prompt(), checked.genre());
            if (!preferences.referenceTitle().isBlank()) {
                RecommendationCatalog.ReferenceResult resolved = catalog.resolveReference(preferences.referenceTitle());
                if (resolved != null) {
                    providers.addAll(resolved.providers());
                    reference = resolved.book();
                    if (reference != null) preferences = ai.referencePreferences(checked.prompt(), preferences, reference);
                }
            }
            RecommendationCatalog.CandidateBatch candidates = catalog.candidates(preferences,
                    checked.genre(), checked.minRating(), checked.includeOlder());
            providers.addAll(candidates.providers());
            if (reference == null) reference = candidates.referenceBook();
            List<BookPreview> eligible = eligible(candidates.books(), checked.excludeIds(), reference, preferences.referenceTitle());
            List<BookPreview> matches = ai.rank(checked.prompt(), preferences, eligible);
            String message = matches.isEmpty()
                    ? "There was not enough verified catalog information for a confident match. Try broader preferences or regular book search."
                    : "Pip selected " + matches.size() + " possible matches from real catalog records. Check the details for your preferences.";
            if (eligible.isEmpty() && !candidates.books().isEmpty()) message =
                    "The available candidates were your reference book or suggestions you already saw. Try broader preferences or different filters.";
            if (!preferences.referenceTitle().isBlank() && reference == null) message +=
                    " The reference title could not be verified, so this search uses only your stated preferences.";
            if (reference != null && (reference.description() == null || reference.description().isBlank()) && reference.tags().isEmpty())
                message += " Reference details were sparse; suggestions use the available genre and your stated preferences.";
            if (candidates.note() != null && !candidates.note().isBlank()) message += " " + candidates.note();
            providers.add(new ProviderOutcome("Groq", matches.isEmpty() ? eligible.isEmpty() ? "skipped" : "empty" : "ok",
                    matches.isEmpty() ? "No verified recommendation survived selection." : "Ranked real catalog candidates using available metadata.",
                    Math.min(16, eligible.size()), matches.size()));
            Response result = response(checked, matches, message, providers, reference);
            // Successful empty results are also cached: repeated clicks must not burn provider quota.
            cache(key, result);
            return result;
        } catch (GroqRecommendationClient.ProviderFailure failure) {
            if (failure.retryAfterSeconds() > 0) limiter.pause(Duration.ofSeconds(failure.retryAfterSeconds()));
            providers.add(new ProviderOutcome("Groq", failure.status() == 429 ? "quota_limited" : "unavailable",
                    failure.getMessage(), 0, 0));
            // Preserve source diagnostics; a provider failure is not an assertion that no books exist.
            return response(checked, List.of(), failure.getMessage(), providers, reference);
        }
    }

    private static Response response(Request request, List<BookPreview> books, String message,
            List<ProviderOutcome> providers, BookPreview reference) {
        int year = Year.now(ZoneOffset.UTC).getValue();
        return new Response(List.copyOf(books), message, selectedSources(books), false,
                request.includeOlder() ? null : year - 4, year, List.copyOf(providers), reference);
    }

    private static List<String> selectedSources(List<BookPreview> books) {
        var sources = new LinkedHashSet<String>();
        for (BookPreview book : books) {
            if (book.id().startsWith("/works/")) sources.add("Open Library");
            if (book.id().startsWith("googlebooks:")) sources.add("Google Books");
            if (book.id().startsWith("curated:")) sources.add("Bundled fallback catalog");
        }
        return List.copyOf(sources);
    }

    private static List<BookPreview> eligible(List<BookPreview> candidates, List<String> excluded, BookPreview reference,
            String referenceTitle) {
        var seen = new LinkedHashSet<String>();
        List<BookPreview> books = new ArrayList<>();
        for (BookPreview candidate : candidates) {
            if (excluded.contains(candidate.id()) || !seen.add(candidate.id())) continue;
            if (!referenceTitle.isBlank() && baseTitle(referenceTitle).equals(baseTitle(candidate.title()))) continue;
            if (reference != null && (reference.id().equals(candidate.id()) || sameBook(reference, candidate))) continue;
            books.add(candidate);
        }
        return List.copyOf(books);
    }

    private static boolean sameBook(BookPreview first, BookPreview second) {
        return !baseTitle(first.title()).isBlank() && baseTitle(first.title()).equals(baseTitle(second.title()));
    }

    private static String baseTitle(String title) {
        String value = title == null ? "" : title;
        return normalized(value.split("[:：]|\\s+[—–-]\\s+|\\s*[\\[(]", 2)[0]);
    }

    private static String normalized(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private synchronized Response cached(String key) {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Cached cached = cache.get(key);
        if (cached == null) return null;
        Response value = cached.value();
        return new Response(value.books(), value.message(), value.sources(), true,
                value.minPublicationYear(), value.maxPublicationYear(), value.providers(), value.referenceBook());
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
        List<String> excluded = request.excludeIds() == null ? List.of() : request.excludeIds();
        if (excluded.size() > 12 || excluded.stream().anyMatch(id -> !BookIdentifier.valid(id)))
            invalid("Exclude at most 12 valid book identifiers.");
        return new Request(prompt, genre, rating, Boolean.TRUE.equals(request.includeOlder()),
                Boolean.TRUE.equals(request.refresh()), excluded.stream().distinct().sorted().toList());
    }

    private static void invalid(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private static String cacheKey(String reader, Request request) {
        String material = reader + "\n" + request.prompt() + "\n" + request.genre()
                + "\n" + request.minRating() + "\n" + request.includeOlder() + "\n" + request.excludeIds()
                + "\n" + Year.now(ZoneOffset.UTC).getValue();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}

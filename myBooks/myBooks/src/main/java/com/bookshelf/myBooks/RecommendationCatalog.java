package com.bookshelf.myBooks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/** Google-first candidates, reference lookup and independent provider outcomes. */
@Service
public class RecommendationCatalog {
    private final OpenLibraryService openLibrary;
    private final GoogleBooksService google;
    private final CatalogService catalog;
    public RecommendationCatalog(OpenLibraryService openLibrary, GoogleBooksService google, CatalogService catalog) {
        this.openLibrary = openLibrary; this.google = google; this.catalog = catalog;
    }
    public record CandidateBatch(List<BookPreview> books, List<String> sources, String note,
            List<ProviderOutcome> providers, BookPreview referenceBook) {
        public CandidateBatch(List<BookPreview> books, List<String> sources, String note) {
            this(books, sources, note, List.of(), null);
        }
    }
    public record ReferenceResult(BookPreview book, List<ProviderOutcome> providers) { }

    public ReferenceResult resolveReference(String title) {
        if (title == null || title.isBlank()) return new ReferenceResult(null, List.of());
        GoogleBooksService.SearchResult result = google.lookupReference(title);
        var providers = new ArrayList<ProviderOutcome>();
        providers.add(result.outcome());
        if (!result.books().isEmpty()) return new ReferenceResult(result.books().getFirst(), List.copyOf(providers));
        var response = openLibrary.searchPage("title:\"" + literal(title) + "\"", "", null, 1);
        BookPreview reference = response.books().stream().filter(CatalogQuality::discoveryBook)
                .filter(book -> sameTitle(book.title(), title)).findFirst().orElse(null);
        providers.add(new ProviderOutcome("Open Library", !response.available() ? "unavailable" : reference == null ? "empty" : "ok",
                !response.available() ? "Reference-book lookup is unavailable." : reference == null
                        ? "No exact reference-book identity was found." : "The reference book was identified from its catalog record.",
                response.books().size(), reference == null ? 0 : 1));
        return new ReferenceResult(reference, List.copyOf(providers));
    }

    public CandidateBatch candidates(RecommendationPreferences preferences, String selectedGenre, Double minRating, boolean includeOlder) {
        long deadline = System.nanoTime() + Duration.ofSeconds(18).toNanos();
        String genre = selectedGenre == null ? "" : selectedGenre;
        if (genre.isBlank()) {
            String inferred = OpenLibraryService.canonicalGenre(preferences.genre());
            if (inferred != null && !"Kids".equals(inferred)) genre = inferred;
        }
        PublicationWindow window = includeOlder ? null : PublicationWindow.forRequest("", false);
        var googleResult = google.searchQueries(queries(preferences), genre, includeOlder);
        boolean olAttempted = System.nanoTime() < deadline;
        var live = olAttempted ? openLibrary.recommendationPage(preferences.keywords(), genre, minRating, true)
                : new OpenLibraryService.SearchPage(List.of(), 0, false);
        var sources = new LinkedHashSet<String>();
        var books = new LinkedHashMap<String, BookPreview>();
        var knownOriginals = new LinkedHashMap<String, BookPreview>();
        for (BookPreview book : catalog.curatedBooks()) knownOriginals.put(CatalogQuality.identity(book), book);
        for (BookPreview book : live.books()) knownOriginals.put(CatalogQuality.identity(book), book);
        int googleAccepted = 0;
        int oldOriginals = 0;
        for (BookPreview book : googleResult.books()) {
            BookPreview known = knownOriginals.get(CatalogQuality.identity(book));
            if (window != null && known != null && known.year() != null && !window.contains(known.year())) { oldOriginals++; continue; }
            if (acceptable(book, genre, minRating, window, preferences.avoid())) {
                if (books.putIfAbsent(CatalogQuality.identity(book), book) == null) googleAccepted++;
                sources.add("Google Books");
            }
        }
        int olAccepted = 0;
        for (BookPreview book : live.books()) {
            if (books.containsKey(CatalogQuality.identity(book))) continue;
            BookPreview enriched = matchingDescription(book, googleResult.books());
            if (acceptable(enriched, genre, minRating, window, preferences.avoid())) {
                books.putIfAbsent(CatalogQuality.identity(enriched), enriched); olAccepted++; sources.add("Open Library");
            }
        }
        if (books.isEmpty() && !live.available() && List.of("not_configured", "unavailable", "quota_limited").contains(googleResult.outcome().state())) {
            for (BookPreview book : catalog.curatedBooks()) {
                if (!acceptable(book, genre, minRating, window, preferences.avoid())) continue;
                String metadata = metadata(book);
                if (!preferences.keywords().isEmpty() && preferences.keywords().stream().map(CatalogQuality::normalize).noneMatch(metadata::contains)) continue;
                books.putIfAbsent(CatalogQuality.identity(book), book); sources.add("Bundled fallback catalog");
            }
        }
        var outcomes = new ArrayList<ProviderOutcome>();
        ProviderOutcome googleOutcome = googleResult.outcome();
        String googleState = googleAccepted == 0 && !googleResult.books().isEmpty() ? "filtered" : googleOutcome.state();
        outcomes.add(new ProviderOutcome("Google Books", googleState, googleAccepted == 0 && !googleResult.books().isEmpty()
                ? "Google Books responded, but its candidates did not pass your filters." : googleOutcome.message(), googleOutcome.received(), googleAccepted));
        outcomes.add(new ProviderOutcome("Open Library", !olAttempted ? "skipped" : !live.available() ? "unavailable" : olAccepted > 0 ? "ok" : live.books().isEmpty() ? "empty" : "filtered",
                !olAttempted ? "Supporting lookup was skipped to keep this request bounded." : !live.available() ? "Supporting catalog could not be reached." : olAccepted > 0 ? "Supporting catalog supplied eligible candidates."
                        : "No additional candidates passed your filters.", live.books().size(), olAccepted));
        var notes = new ArrayList<String>();
        if (googleAccepted > 0) notes.add("Google Books publication dates describe editions; an original publication date may differ.");
        if (oldOriginals > 0) notes.add("Known older originals were excluded even when a recent edition exists.");
        if (!preferences.avoid().isEmpty()) notes.add("Content exclusions use available metadata and may be incomplete.");
        return new CandidateBatch(books.values().stream().limit(24).toList(), List.copyOf(sources), String.join(" ", notes), List.copyOf(outcomes), null);
    }

    private static List<String> queries(RecommendationPreferences preferences) {
        var result = new LinkedHashSet<String>();
        for (String query : preferences.searchQueries()) {
            if (query != null && !query.isBlank() && !referenceTerm(query, preferences.referenceTitle())) result.add(query);
            if (result.size() >= 2) break;
        }
        for (String word : preferences.keywords()) {
            if (word != null && !word.isBlank() && !referenceTerm(word, preferences.referenceTitle())) result.add(word);
            if (result.size() >= 3) break;
        }
        if (result.isEmpty()) result.add(preferences.genre().isBlank() ? "fiction" : preferences.genre());
        return result.stream().limit(2).toList();
    }
    private static boolean acceptable(BookPreview book, String genre, Double minRating, PublicationWindow window, List<String> avoid) {
        if (!CatalogQuality.discoveryBook(book)) return false;
        if (book.year() != null && book.year() > java.time.Year.now(java.time.ZoneOffset.UTC).getValue()) return false;
        if (window != null && !window.contains(book.year())) return false;
        if (minRating != null && minRating > 0 && (book.rating() == null || book.rating() < minRating)) return false;
        boolean kids = "Kids".equals(book.genre()) || BookAudience.isKids(book.id(), book.tags());
        if (kids != "Kids".equals(genre) || !CatalogQuality.matchesGenre(book, genre)) return false;
        String padded = " " + metadata(book) + " ";
        return avoid.stream().map(CatalogQuality::normalize).filter(value -> !value.isBlank()).noneMatch(term -> padded.contains(" " + term + " "));
    }
    static BookPreview matchingDescription(BookPreview original, List<BookPreview> candidates) {
        if (original.description() != null && !original.description().isBlank()) return original;
        for (BookPreview candidate : candidates) if (sameIdentity(original, candidate) && candidate.description() != null && !candidate.description().isBlank()) return withDescription(original, candidate.description());
        return original;
    }
    static boolean sameIdentity(BookPreview first, BookPreview second) { return CatalogQuality.sameIdentity(first, second); }
    static BookPreview withDescription(BookPreview book, String description) {
        return new BookPreview(book.id(), book.title(), book.author(), book.genre(), book.coverId(), book.rating(), book.ratingCount(), book.year(), description, book.source(), book.tags(), book.reason());
    }
    private static String metadata(BookPreview book) {
        return CatalogQuality.normalize(book.title() + " " + book.genre() + " " + String.join(" ", book.tags()) + " " + (book.description() == null ? "" : book.description()));
    }
    private static boolean sameTitle(String first, String second) { return !CatalogQuality.normalize(second).isBlank() && CatalogQuality.normalize(first).equals(CatalogQuality.normalize(second)); }
    private static boolean referenceTerm(String query, String title) {
        String reference = CatalogQuality.normalize(title);
        String term = CatalogQuality.normalize(query);
        return !reference.isBlank() && !term.isBlank() && (" " + reference + " ").contains(" " + term + " ");
    }
    private static String literal(String text) { return text.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").strip(); }
}

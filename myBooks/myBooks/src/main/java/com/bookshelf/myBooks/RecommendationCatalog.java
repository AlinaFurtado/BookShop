package com.bookshelf.myBooks;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Retrieves real candidates. A model cannot relax publication, audience, or rating filters. */
@Service
public class RecommendationCatalog {
    private final OpenLibraryService openLibrary;
    private final GoogleBooksService google;
    private final CatalogService catalog;

    public RecommendationCatalog(OpenLibraryService openLibrary, GoogleBooksService google, CatalogService catalog) {
        this.openLibrary = openLibrary;
        this.google = google;
        this.catalog = catalog;
    }

    public record CandidateBatch(List<BookPreview> books, List<String> sources, String note) { }

    public CandidateBatch candidates(RecommendationPreferences preferences, String selectedGenre,
            Double minRating, boolean includeOlder) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        String genre = selectedGenre == null ? "" : selectedGenre;
        if (genre.isBlank()) {
            String inferred = OpenLibraryService.canonicalGenre(preferences.genre());
            // Kids requires the reader's explicit selection, never the model's inference.
            if (inferred != null && !"Kids".equals(inferred)) genre = inferred;
        }
        PublicationWindow window = includeOlder ? null : PublicationWindow.forRequest("", false);
        OpenLibraryService.SearchPage live = openLibrary.recommendationPage(preferences.keywords(), genre, minRating, includeOlder);
        List<BookPreview> googleBooks = System.nanoTime() < deadline
                ? google.search(String.join(" ", preferences.keywords()), genre, includeOlder) : List.of();
        var sources = new LinkedHashSet<String>();
        var books = new LinkedHashMap<String, BookPreview>();
        for (BookPreview book : live.books()) {
            BookPreview enriched = matchingDescription(book, googleBooks);
            if (acceptable(enriched, genre, minRating, window, preferences.avoid())) {
                books.putIfAbsent(identity(enriched), enriched);
                sources.add("Open Library");
                if (enriched != book) sources.add("Google Books");
            }
        }
        boolean unverifiedEdition = false;
        // A recent edition is not proof of a recent original. Verify at most one
        // extra work; never turn a reprint of an old title into a new recommendation.
        boolean verifiedOne = false;
        for (BookPreview googleBook : googleBooks) {
            if (books.containsKey(identity(googleBook))) continue;
            if (!includeOlder) {
                if (verifiedOne || System.nanoTime() >= deadline || books.size() >= 8) {
                    unverifiedEdition = true;
                    continue;
                }
                verifiedOne = true;
                boolean accepted = false;
                for (BookPreview verified : openLibrary.verifyRecommendation(googleBook, genre, minRating, false).books()) {
                    if (!sameIdentity(verified, googleBook)) continue;
                    BookPreview enriched = matchingDescription(verified, List.of(googleBook));
                    if (acceptable(enriched, genre, minRating, window, preferences.avoid())) {
                        books.putIfAbsent(identity(enriched), enriched);
                        sources.add("Open Library");
                        sources.add("Google Books");
                        accepted = true;
                    }
                }
                if (!accepted) unverifiedEdition = true;
            } else if (acceptable(googleBook, genre, minRating, null, preferences.avoid())) {
                books.putIfAbsent(identity(googleBook), googleBook);
                sources.add("Google Books");
            }
        }
        if (!live.available() && books.isEmpty()) {
            for (BookPreview book : catalog.curatedBooks()) {
                if (!acceptable(book, genre, minRating, window, preferences.avoid())) continue;
                String metadata = metadata(book);
                if (!preferences.keywords().isEmpty() && preferences.keywords().stream()
                        .map(RecommendationCatalog::normalize).noneMatch(metadata::contains)) continue;
                books.putIfAbsent(identity(book), book);
                sources.add("Bundled fallback catalog");
            }
        }
        var notes = new ArrayList<String>();
        if (!live.available()) notes.add("Open Library is temporarily unavailable.");
        if (unverifiedEdition) notes.add("Google editions without a verified recent original publication were omitted.");
        if (includeOlder && books.values().stream().anyMatch(book -> book.id().startsWith("googlebooks:")))
            notes.add("Google Books dates describe editions, which may be newer than the original work.");
        if (!preferences.avoid().isEmpty()) notes.add("Exclusions use available catalog metadata; content warnings may be incomplete.");
        return new CandidateBatch(books.values().stream().limit(12).toList(), List.copyOf(sources), String.join(" ", notes));
    }

    private static boolean acceptable(BookPreview book, String genre, Double minRating, PublicationWindow window,
            List<String> avoid) {
        if (!BookIdentifier.valid(book.id())) return false;
        if (book.year() != null && book.year() > java.time.Year.now(java.time.ZoneOffset.UTC).getValue()) return false;
        if (window != null && !window.contains(book.year())) return false;
        if (minRating != null && minRating > 0 && (book.rating() == null || book.rating() < minRating)) return false;
        boolean kids = "Kids".equals(book.genre()) || BookAudience.isKids(book.id(), book.tags());
        if (kids != "Kids".equals(genre)) return false;
        if (!genre.isBlank() && !genre.equals(book.genre())) return false;
        String metadata = metadata(book);
        String padded = " " + metadata + " ";
        return avoid.stream().map(RecommendationCatalog::normalize).filter(value -> !value.isBlank())
                .noneMatch(term -> padded.contains(" " + term + " "));
    }

    static BookPreview matchingDescription(BookPreview original, List<BookPreview> candidates) {
        if (original.description() != null && !original.description().isBlank()) return original;
        for (BookPreview candidate : candidates) {
            if (sameIdentity(original, candidate) && candidate.description() != null
                    && !candidate.description().isBlank()) return withDescription(original, candidate.description());
        }
        return original;
    }

    static boolean sameIdentity(BookPreview first, BookPreview second) {
        String author = normalize(first.author());
        return !normalize(first.title()).isBlank() && !author.isBlank() && !"unknown author".equals(author)
                && identity(first).equals(identity(second));
    }

    static BookPreview withDescription(BookPreview book, String description) {
        return new BookPreview(book.id(), book.title(), book.author(), book.genre(), book.coverId(), book.rating(),
                book.ratingCount(), book.year(), description, book.source(), book.tags(), book.reason());
    }

    private static String metadata(BookPreview book) {
        return normalize(book.title() + " " + book.genre() + " " + String.join(" ", book.tags())
                + " " + (book.description() == null ? "" : book.description()));
    }

    private static String identity(BookPreview book) { return normalize(book.title()) + "|" + normalize(book.author()); }
    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}

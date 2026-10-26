package com.bookshelf.myBooks;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Deterministic quality checks; uncertain metadata is never turned into a book fact. */
final class CatalogQuality {
    private CatalogQuality() { }
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
    static String identity(BookPreview book) { return normalize(book.title()) + "|" + normalize(book.author()); }
    static boolean sameIdentity(BookPreview first, BookPreview second) {
        String author = normalize(first.author());
        return !normalize(first.title()).isBlank() && !author.isBlank() && !author.equals("unknown author")
                && identity(first).equals(identity(second));
    }
    static boolean ancillary(BookPreview book) {
        String text = " " + normalize(book.title()) + " ";
        return List.of("study guide", "summary of", "summary analysis", "workbook", "notebook",
                "coloring book", "colouring book", "reading journal", "blank journal", "magazine")
                .stream().anyMatch(term -> text.contains(" " + term + " "));
    }
    static boolean discoveryBook(BookPreview book) {
        return BookIdentifier.valid(book.id()) && !normalize(book.author()).isBlank()
                && !normalize(book.author()).equals("unknown author") && !ancillary(book);
    }
    static boolean matchesGenre(BookPreview book, String genre) {
        if (genre == null || genre.isBlank() || genre.equals(book.genre())) return true;
        String subjects = " " + normalize(String.join(" ", book.tags())) + " ";
        if (subjects.contains(" " + normalize(genre) + " ")) return true;
        if ("Mystery".equals(genre) && (subjects.contains(" detective ") || subjects.contains(" crime "))) return true;
        if ("Fantasy".equals(genre) && subjects.contains(" magic ")) return true;
        if ("Romance".equals(genre) && subjects.contains(" love stories ")) return true;
        // Source subject queries can be more specific than returned categories.
        return "General".equals(book.genre());
    }
}

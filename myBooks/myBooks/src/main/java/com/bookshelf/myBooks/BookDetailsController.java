package com.bookshelf.myBooks;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Description lookups use fixed provider hosts and never accept arbitrary URLs. */
@RestController
public class BookDetailsController {
    private final OpenLibraryService openLibrary;
    private final GoogleBooksService google;
    private final CatalogService catalog;

    public BookDetailsController(OpenLibraryService openLibrary, GoogleBooksService google, CatalogService catalog) {
        this.openLibrary = openLibrary;
        this.google = google;
        this.catalog = catalog;
    }

    public record Details(BookPreview book, String descriptionSource, String note) { }

    @GetMapping("/api/books/details")
    public Details details(@RequestParam String id, @RequestParam String title, @RequestParam String author) {
        if (!BookIdentifier.valid(id) || title.isBlank() || title.length() > 500
                || author.isBlank() || author.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid catalog book.");
        BookPreview original = catalog.curatedBooks().stream().filter(book -> book.id().equals(id)).findFirst()
                .orElse(new BookPreview(id, title, author, "General", null, null, null, null, "",
                        id.startsWith("googlebooks:") ? "googlebooks" : "openlibrary", List.of(), null));
        if (original.description() != null && !original.description().isBlank())
            return new Details(original, "Book Buddy catalog", "");
        String description = openLibrary.description(id);
        if (!description.isBlank()) return new Details(RecommendationCatalog.withDescription(original, description), "Open Library", "");
        BookPreview verified = original;
        if (id.startsWith("/works/")) {
            verified = openLibrary.metadata(id);
            if (verified == null || !RecommendationCatalog.sameIdentity(original, verified))
                return new Details(original, "", "A matching description could not be verified for this catalog record right now.");
        }
        BookPreview enriched = google.enrich(verified);
        if (enriched.description() != null && !enriched.description().isBlank())
            return new Details(RecommendationCatalog.withDescription(original, enriched.description()), "Google Books", "Description matched by catalog title and author.");
        return new Details(original, "", "A matching description is not available from the configured catalogs right now.");
    }
}

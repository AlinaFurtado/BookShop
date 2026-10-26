package com.bookshelf.myBooks;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookController {
    private final CatalogService catalog;
    private final String database;

    public BookController(CatalogService catalog, @Value("${spring.datasource.url:}") String databaseUrl) {
        this.catalog = catalog;
        // Report the configured database without exposing its URL or credentials.
        database = databaseUrl.startsWith("jdbc:postgresql:") ? "PostgreSQL"
                : databaseUrl.startsWith("jdbc:h2:") ? "H2" : "Configured database";
    }

    @GetMapping({"/api/books", "/api/books/featured"})
    public List<BookPreview> featured(@RequestParam(required = false) String genre,
            @RequestParam(required = false) Double minRating) {
        return catalog.featured(genre, minRating);
    }

    @GetMapping("/api/books/search")
    public List<BookPreview> search(@RequestParam(defaultValue = "") String title,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Double minRating) {
        return catalog.search(title, genre, minRating);
    }

    @GetMapping("/api/books/browse")
    public CatalogService.CatalogPage browse(@RequestParam(defaultValue = "") String title,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Double minRating,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String cursor) {
        return catalog.browse(title, genre, minRating, page, cursor);
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("app", "AI Book Buddy", "status", "up", "database", database, "billing", "No paid APIs");
    }

    @GetMapping("/api/books/trending")
    public CatalogService.CatalogPage trending(@RequestParam(required = false) String genre,
            @RequestParam(required = false) Double minRating,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) String cursor) {
        return catalog.trending(genre, minRating, page, cursor);
    }
}

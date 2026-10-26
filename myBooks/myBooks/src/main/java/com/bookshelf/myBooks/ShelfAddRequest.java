package com.bookshelf.myBooks;

import java.time.LocalDate;
import java.util.List;

/** Flat JSON keeps the original add-book request compatible, adding optional shelf choices. */
public record ShelfAddRequest(String id, String title, String author, String genre,
        Integer coverId, Double rating, Integer ratingCount, Integer year,
        String description, String source, List<String> tags, String reason,
        String status, LocalDate finishDate, Boolean clearFinishDate) {
    public BookPreview book() {
        return new BookPreview(id, title, author, genre, coverId, rating, ratingCount,
                year, description, source, tags, reason);
    }
}

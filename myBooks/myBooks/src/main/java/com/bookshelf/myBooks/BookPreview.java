package com.bookshelf.myBooks;

import java.util.List;

/** A catalog/search result. Its id is a work ID, not a saved shelf row ID. */
public record BookPreview(
        String id, String title, String author, String genre,
        Integer coverId, Double rating, Integer ratingCount, Integer year,
        String description, String source, List<String> tags, String reason) {

    public BookPreview {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public BookPreview withReason(String reason) {
        return new BookPreview(id, title, author, genre, coverId, rating, ratingCount,
                year, description, source, tags, reason);
    }
}

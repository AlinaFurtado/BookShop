package com.bookshelf.myBooks;

import java.util.List;

/** Preferences extracted from a reader's request, never a source of book facts. */
public record RecommendationPreferences(String genre, List<String> keywords, List<String> avoid) {
    public RecommendationPreferences {
        genre = genre == null ? "" : genre;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
    }
}

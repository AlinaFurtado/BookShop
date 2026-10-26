package com.bookshelf.myBooks;

import java.util.List;

/** Preferences extracted from a reader's request, never a source of book facts. */
public record RecommendationPreferences(String genre, List<String> keywords, List<String> avoid,
        String referenceTitle, List<String> searchQueries) {
    public RecommendationPreferences {
        genre = genre == null ? "" : genre;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        referenceTitle = referenceTitle == null ? "" : referenceTitle;
        searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
    }

    public RecommendationPreferences(String genre, List<String> keywords, List<String> avoid) {
        this(genre, keywords, avoid, "", List.of());
    }
}

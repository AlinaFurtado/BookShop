package com.bookshelf.myBooks;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAlias;

/** Field names deliberately match the external API's JSON. */
public record OpenLibraryResponse(@JsonAlias("num_found") Long numFound, List<BookDoc> docs) {
    public record BookDoc(String key, String title, List<String> author_name,
            Integer cover_i, Double ratings_average, Integer ratings_count,
            Integer first_publish_year, List<String> subject, EditionMatches editions) {}

    public record EditionMatches(List<EditionDoc> docs) {}
    public record EditionDoc(String key, String title, Integer cover_i, List<String> language) {}
}

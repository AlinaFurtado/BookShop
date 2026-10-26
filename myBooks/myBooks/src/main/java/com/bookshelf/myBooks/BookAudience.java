package com.bookshelf.myBooks;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Audience is separate from a story's topic: a book about children need not be for children. */
final class BookAudience {
    private BookAudience() { }

    private static final List<String> KIDS_SUBJECTS = List.of(
            "juvenile fiction", "juvenile literature", "juvenile nonfiction", "juvenile non-fiction",
            "juvenile audience", "juvenile works", "children's fiction", "children's literature",
            "children's stories", "children's books", "picture books", "board books",
            "early readers", "readers (primary)", "readers (elementary)", "nursery rhymes",
            "fiction, juvenile", "fiction, children", "kinderbuch", "livres pour enfants");

    // Known children's classics in the offline collection. Work IDs, not title
    // guesses, keep these separated even when a provider omits audience subjects.
    private static final Set<String> KNOWN_KIDS_WORKS = Set.of(
            "/works/OL27482W", "/works/OL60149W", "/works/OL59798W",
            "/works/OL29983W", "/works/OL77746W");
    // Mixed catalog records can attach a children's label to an adult or
    // older-teen novel. These audited work-level exceptions take precedence;
    // they never use words in a title to guess a reader's age.
    private static final Set<String> OLDER_READER_WORKS = Set.of(
            "/works/OL17860744W", // A Court of Mist and Fury
            "/works/OL16014245W", // Shatter Me
            "/works/OL3140822W",  // To Kill a Mockingbird
            "/works/OL20234863W", // Girl in Pieces
            "/works/OL20646061W", // A Good Girl's Guide to Murder
            "/works/OL5735363W",  // The Hunger Games
            "/works/OL5720023W"); // Twilight
    private static final List<String> NORMALIZED_SUBJECTS = KIDS_SUBJECTS.stream()
            .map(BookAudience::normalize).toList();

    static String kidsQuery() {
        String subjects = KIDS_SUBJECTS.stream().map(subject -> "subject:\"" + subject + "\"")
                .collect(Collectors.joining(" OR ", "(", ")"));
        String exceptions = OLDER_READER_WORKS.stream().sorted()
                .map(id -> "key:\"" + id + "\"").collect(Collectors.joining(" OR ", "(", ")"));
        return "(" + subjects + " AND NOT " + exceptions + ")";
    }

    static boolean isKids(String workId, List<String> subjects) {
        if (workId != null && OLDER_READER_WORKS.contains(workId)) return false;
        if (workId != null && KNOWN_KIDS_WORKS.contains(workId)) return true;
        if (subjects == null) return false;
        return subjects.stream().filter(subject -> subject != null).anyMatch(subject -> {
            String normalized = " " + normalize(subject) + " ";
            return NORMALIZED_SUBJECTS.stream().anyMatch(marker -> normalized.contains(" " + marker + " "));
        });
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}

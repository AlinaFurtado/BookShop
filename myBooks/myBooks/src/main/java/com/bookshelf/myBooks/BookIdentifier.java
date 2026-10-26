package com.bookshelf.myBooks;

/** A provider ID is data, never a URL or a path supplied to an arbitrary host. */
final class BookIdentifier {
    private BookIdentifier() { }

    static boolean valid(String id) {
        return id != null && id.matches("(?:/works/OL\\d{1,20}W|curated:[a-z0-9-]{1,80}|googlebooks:[A-Za-z0-9_-]{1,80})");
    }
}

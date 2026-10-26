package com.bookshelf.myBooks;

import java.time.Year;
import java.time.ZoneOffset;

/** Five inclusive publication years, based on the work's original publication. */
record PublicationWindow(int firstYear, int lastYear) {
    static PublicationWindow forRequest(String text, boolean trending) {
        if (!trending && text != null && !text.isBlank()) return null;
        int currentYear = Year.now(ZoneOffset.UTC).getValue();
        return new PublicationWindow(currentYear - 4, currentYear);
    }

    boolean contains(Integer year) { return year != null && year >= firstYear && year <= lastYear; }
    String query() { return "first_publish_year:[" + firstYear + " TO " + lastYear + "]"; }
    String label() { return firstYear + "–" + lastYear; }
}

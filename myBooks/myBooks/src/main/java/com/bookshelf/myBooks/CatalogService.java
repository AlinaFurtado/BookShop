package com.bookshelf.myBooks;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogService {
    public static final int BROWSE_PAGE_SIZE = 11;
    private static final int MAX_PROVIDER_PAGES_PER_REQUEST = 6;
    private static final long REQUEST_BUDGET_MILLIS = 12000;
    private final List<BookPreview> catalog;
    private final OpenLibraryService openLibrary;

    // A continuation names a source page and the first unused matching item.
    // It survives cache expiry without retaining or replaying an entire search.
    private record Cursor(boolean curated, int providerPage, int itemOffset) {
        String value() { return curated ? "curated:" + itemOffset : "ol:" + providerPage + ":" + itemOffset; }
    }

    public CatalogService(ObjectMapper mapper, OpenLibraryService openLibrary) throws IOException {
        this.openLibrary = openLibrary;
        try (var input = new ClassPathResource("catalog.json").getInputStream()) {
            catalog = List.copyOf(Arrays.asList(mapper.readValue(input, BookPreview[].class)));
        }
    }

    public List<BookPreview> curatedBooks() { return catalog; }

    // Older array endpoints use the same live search as the paginated UI.
    public List<BookPreview> featured(String genre, Double minRating) {
        return browse("", genre, minRating, 1).books();
    }

    public List<BookPreview> search(String query, String genre, Double minRating) {
        return browse(query, genre, minRating, 1).books();
    }

    public CatalogPage browse(String query, String genre, Double minRating, int page) {
        return browse(query, genre, minRating, page, null);
    }

    public CatalogPage browse(String query, String genre, Double minRating, int page, String continuation) {
        return browse(query, genre, minRating, page, continuation, false);
    }

    public CatalogPage trending(String genre, Double minRating, int page, String continuation) {
        return browse("", genre, minRating, page, continuation, true);
    }

    private CatalogPage browse(String query, String genre, Double minRating, int page, String continuation, boolean trending) {
        String selectedGenre = OpenLibraryService.canonicalGenre(genre);
        if (selectedGenre == null) badRequest("Choose one of the supported genres.");
        if (minRating != null && (!Double.isFinite(minRating) || minRating < 0 || minRating > 5)) {
            badRequest("Minimum rating must be between 0 and 5.");
        }
        if (page < 1 || page > 999999999) badRequest("Invalid display page.");
        String text = query == null ? "" : query.strip();
        PublicationWindow publicationWindow = PublicationWindow.forRequest(text, trending);
        if (text.length() > 160) badRequest("Search is limited to 160 characters.");
        if (page > 1 && (continuation == null || continuation.isBlank())) {
            badRequest("Use the nextCursor from the previous response to continue browsing.");
        }
        Cursor cursor = parseCursor(continuation);
        if (trending && cursor.curated()) badRequest("Trending requires a live catalog cursor.");
        if (cursor.curated()) return withPublicationWindow(curatedPage(text, selectedGenre, minRating, page, cursor.itemOffset()), publicationWindow);
        CatalogPage live = discover(text, selectedGenre, minRating, page, cursor, continuation != null && !continuation.isBlank(), trending);
        // An offline selection cannot truthfully be labelled 'trending now'.
        if (live == null && trending) return withPublicationWindow(new CatalogPage(List.of(), page, BROWSE_PAGE_SIZE, null, true,
                "unavailable", "Current trends could not be fetched from Open Library. Please try again.", "ol:1:0"), publicationWindow);
        return withPublicationWindow(live != null ? live : curatedPage(text, selectedGenre, minRating, page, 0), publicationWindow);
    }

    /** Fetch only enough source pages for this human-requested batch. */
    private CatalogPage discover(String text, String genre, Double minRating, int page, Cursor cursor, boolean continuing, boolean trending) {
        int providerPage = cursor.providerPage();
        int itemOffset = cursor.itemOffset();
        int fetched = 0;
        long total = 0;
        boolean available = false;
        boolean exhausted = false;
        boolean connectionFailed = false;
        boolean postFiltered = false;
        var matches = new LinkedHashMap<String, BookPreview>();
        long deadline = System.currentTimeMillis() + REQUEST_BUDGET_MILLIS;
        while (matches.size() < BROWSE_PAGE_SIZE && !exhausted
                && fetched < MAX_PROVIDER_PAGES_PER_REQUEST && System.currentTimeMillis() < deadline) {
            OpenLibraryService.SearchPage remote = trending ? openLibrary.trendingPage(genre, minRating, providerPage)
                    : openLibrary.searchPage(text, genre, minRating, providerPage);
            fetched++;
            if (!remote.available()) {
                connectionFailed = true;
                break; // Keep the failed page and offset so Retry never skips it.
            }
            available = true;
            total = remote.total();
            postFiltered |= remote.postFiltered();
            List<BookPreview> candidates = remote.books();
            int index = Math.min(itemOffset, candidates.size());
            while (index < candidates.size() && matches.size() < BROWSE_PAGE_SIZE) {
                BookPreview book = candidates.get(index++);
                matches.putIfAbsent(book.id(), addOriginalSummary(book));
            }
            itemOffset = index;
            if (index < candidates.size()) break; // Remaining matches belong to the next UI batch.
            exhausted = (long) providerPage * OpenLibraryService.PAGE_SIZE >= total;
            if (!exhausted) { providerPage++; itemOffset = 0; }
        }
        // Only an initial outage uses the small offline collection. A live
        // continuation keeps its source and exact location through an outage.
        if (!available && !continuing) return null;
        boolean more = !exhausted;
        String message;
        if (connectionFailed) message = "Open Library could not be reached. Your browsing position is kept; load more to retry.";
        else if (matches.size() < BROWSE_PAGE_SIZE && more) message = "Still looking for matching books. Load more to continue from this point.";
        else if (matches.isEmpty()) message = "No books matched these choices. Try another search, genre or minimum rating.";
        else if (trending) message = "Trending on Open Library, ordered by recent reader activity.";
        else message = minRating != null && minRating > 0
                ? "Live Open Library matches averaging " + minRating + "+ stars. Unrated books are excluded."
                : "Browsing Open Library’s searchable catalog, including rated and unrated books.";
        return new CatalogPage(List.copyOf(matches.values()), page, BROWSE_PAGE_SIZE,
                !available || postFiltered || (minRating != null && minRating > 0) ? null : total,
                more, "openlibrary", message, more ? new Cursor(false, providerPage, itemOffset).value() : null);
    }

    private CatalogPage curatedPage(String text, String selectedGenre, Double minRating, int page, int offset) {
        String normalized = text.toLowerCase(Locale.ROOT);
        PublicationWindow publicationWindow = PublicationWindow.forRequest(text, false);
        List<BookPreview> matching = catalog.stream()
                .filter(book -> normalized.isEmpty() || (book.title() + " " + book.author()).toLowerCase(Locale.ROOT).contains(normalized))
                .filter(book -> "Kids".equals(selectedGenre) == ("Kids".equals(book.genre()) || BookAudience.isKids(book.id(), book.tags())))
                .filter(book -> selectedGenre.isEmpty() || selectedGenre.equalsIgnoreCase(book.genre()))
                .filter(book -> publicationWindow == null || publicationWindow.contains(book.year()))
                .filter(book -> minRating == null || minRating <= 0 || (book.rating() != null && book.rating() >= minRating))
                .sorted(publicationWindow == null ? (left, right) -> 0
                        : java.util.Comparator.comparing(BookPreview::year).reversed())
                .toList();
        int start = Math.min(offset, matching.size());
        int end = Math.min(start + BROWSE_PAGE_SIZE, matching.size());
        return new CatalogPage(matching.subList(start, end), page, BROWSE_PAGE_SIZE, (long) matching.size(), end < matching.size(), "curated",
                "Open Library could not be reached. These results are from the smaller offline collection. Retry Open Library to reconnect.",
                end < matching.size() ? "curated:" + end : null);
    }

    private Cursor parseCursor(String value) {
        if (value == null || value.isBlank()) return new Cursor(false, 1, 0);
        if (value.matches("curated:[0-9]{1,9}")) {
            return new Cursor(true, 1, Integer.parseInt(value.substring(8)));
        }
        if (!value.matches("ol:[1-9][0-9]{0,8}:[0-9]{1,2}")) {
            badRequest("This browsing cursor is no longer valid. Start a new search.");
        }
        String[] parts = value.split(":");
        int offset = Integer.parseInt(parts[2]);
        if (offset > OpenLibraryService.PAGE_SIZE) badRequest("Invalid position in the catalog page.");
        return new Cursor(false, Integer.parseInt(parts[1]), offset);
    }

    private BookPreview addOriginalSummary(BookPreview online) {
        BookPreview local = catalog.stream().filter(book -> book.id().equals(online.id())).findFirst().orElse(null);
        if (local == null) return online;
        return new BookPreview(online.id(), online.title(), online.author(),
                "General".equals(online.genre()) ? local.genre() : online.genre(),
                online.coverId() == null ? local.coverId() : online.coverId(), online.rating(), online.ratingCount(),
                online.year(), local.description(), online.source(), online.tags(), null);
    }

    private void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    private CatalogPage withPublicationWindow(CatalogPage result, PublicationWindow window) {
        String message = result.message();
        if (window != null) message += " English editions. First published " + window.label()
                + ". Search a title or author to include older books.";
        return new CatalogPage(result.books(), result.page(), result.pageSize(), result.total(), result.hasMore(),
                result.source(), message, result.nextCursor(), window == null ? null : window.firstYear(), window == null ? null : window.lastYear());
    }

    public record CatalogPage(List<BookPreview> books, int page, int pageSize, Long total,
            boolean hasMore, String source, String message, String nextCursor, Integer minPublicationYear, Integer maxPublicationYear) {
        CatalogPage(List<BookPreview> books, int page, int pageSize, Long total,
                boolean hasMore, String source, String message, String nextCursor) {
            this(books, page, pageSize, total, hasMore, source, message, nextCursor, null, null);
        }
    }
}

package com.bookshelf.myBooks;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GoogleBooksService google;

    // A continuation names a source page and the first unused matching item.
    // It survives cache expiry without retaining or replaying an entire search.
    private record Cursor(boolean curated, int providerPage, int itemOffset) {
        String value() { return curated ? "curated:" + itemOffset : "ol:" + providerPage + ":" + itemOffset; }
    }
    private record GoogleCursor(int providerPage, int itemOffset) {
        String value() { return "gb:" + providerPage + ":" + itemOffset; }
    }
    private record GoogleDiscovery(CatalogPage page, ProviderOutcome initialFailure) { }

    public CatalogService(ObjectMapper mapper, OpenLibraryService openLibrary) throws IOException {
        this(mapper, openLibrary, null);
    }

    @Autowired
    public CatalogService(ObjectMapper mapper, OpenLibraryService openLibrary, GoogleBooksService google) throws IOException {
        this.openLibrary = openLibrary;
        this.google = google;
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
        boolean continuing = continuation != null && !continuation.isBlank();
        boolean googleCursor = continuing && continuation.startsWith("gb:");
        if (trending && googleCursor) badRequest("Trending requires an Open Library catalog cursor.");
        ProviderOutcome googleFailure = null;
        if (!trending && (googleCursor || !continuing && google != null)) {
            GoogleDiscovery primary = discoverGoogle(text, selectedGenre, minRating, page,
                    googleCursor ? parseGoogleCursor(continuation) : new GoogleCursor(1, 0), googleCursor, publicationWindow);
            if (primary.page() != null) return withPublicationWindow(primary.page(), publicationWindow);
            googleFailure = primary.initialFailure();
        }
        Cursor cursor = parseCursor(continuation);
        if (trending && cursor.curated()) badRequest("Trending requires a live catalog cursor.");
        if (cursor.curated()) return withPublicationWindow(curatedPage(text, selectedGenre, minRating, page, cursor.itemOffset()), publicationWindow);
        CatalogPage live = discover(text, selectedGenre, minRating, page, cursor, continuing, trending);
        // An offline selection cannot truthfully be labelled 'trending now'.
        if (live == null && trending) return withPublicationWindow(new CatalogPage(List.of(), page, BROWSE_PAGE_SIZE, null, true,
                "unavailable", "Current trends could not be fetched from Open Library. Please try again.", "ol:1:0"), publicationWindow);
        CatalogPage result = live != null ? live : curatedPage(text, selectedGenre, minRating, page, 0);
        if (googleFailure != null) result = withProvider(result, googleFailure);
        return withPublicationWindow(result, publicationWindow);
    }

    /** Google is the main catalog; source cursors preserve unused records instead of dropping a 20-row page's tail. */
    private GoogleDiscovery discoverGoogle(String text, String genre, Double minRating, int page,
            GoogleCursor cursor, boolean continuing, PublicationWindow window) {
        int providerPage = cursor.providerPage();
        int itemOffset = cursor.itemOffset();
        int fetched = 0;
        int received = 0;
        boolean connected = false;
        boolean exhausted = false;
        ProviderOutcome failure = null;
        var matches = new LinkedHashMap<String, BookPreview>();
        long deadline = System.currentTimeMillis() + REQUEST_BUDGET_MILLIS;
        while (matches.size() < BROWSE_PAGE_SIZE && !exhausted && fetched < 3 && System.currentTimeMillis() < deadline) {
            GoogleBooksService.GooglePage remote = google == null
                    ? new GoogleBooksService.GooglePage(List.of(), 0, false,
                            new ProviderOutcome("Google Books", "not_configured", "Google Books is not configured on this server.", 0, 0))
                    : google.searchPage(text, genre, minRating, providerPage, window == null);
            fetched++;
            ProviderOutcome outcome = remote.outcome();
            received += outcome.received();
            if (List.of("not_configured", "quota_limited", "unavailable").contains(outcome.state())) {
                failure = outcome;
                break; // Never skip a failed source page or silently switch a continuation's provider.
            }
            connected = true;
            var uniquePage = new LinkedHashMap<String, BookPreview>();
            for (BookPreview book : remote.books()) uniquePage.putIfAbsent(CatalogQuality.identity(book), book);
            List<BookPreview> candidates = List.copyOf(uniquePage.values());
            int index = Math.min(itemOffset, candidates.size());
            while (index < candidates.size() && matches.size() < BROWSE_PAGE_SIZE) {
                BookPreview book = candidates.get(index++);
                if (acceptableGoogle(book, text, genre, minRating, window))
                    matches.putIfAbsent(CatalogQuality.identity(book), book);
            }
            itemOffset = index;
            if (index < candidates.size()) break;
            exhausted = !remote.hasMore() || providerPage >= 50;
            if (!exhausted) { providerPage++; itemOffset = 0; }
        }
        if (!connected && !continuing && failure != null) return new GoogleDiscovery(null, failure);
        boolean more = !exhausted;
        String message;
        if (failure != null) message = failure.message() + " Your Google Books browsing position is kept; load more to retry.";
        else if (matches.isEmpty() && !more) message = "Google Books responded, but no books matched these choices. Try another search, genre or minimum rating.";
        else if (matches.size() < BROWSE_PAGE_SIZE && more) message = "Still looking through Google Books for matching editions. Load more to continue from this point.";
        else message = minRating != null && minRating > 0
                ? "Google Books editions averaging " + minRating + "+ stars. Unrated books are excluded."
                : "Browsing Google Books catalog records, including rated and unrated editions.";
        String state = failure != null ? failure.state() : !matches.isEmpty() ? "ok" : received > 0 ? "filtered" : "empty";
        ProviderOutcome outcome = new ProviderOutcome("Google Books", state, message, received, matches.size());
        CatalogPage result = new CatalogPage(List.copyOf(matches.values()), page, BROWSE_PAGE_SIZE, null, more,
                "googlebooks", message, more ? new GoogleCursor(providerPage, itemOffset).value() : null,
                null, null, List.of(outcome), "edition");
        return new GoogleDiscovery(result, null);
    }

    private boolean acceptableGoogle(BookPreview book, String text, String genre, Double minRating, PublicationWindow window) {
        if (!BookIdentifier.valid(book.id()) || CatalogQuality.ancillary(book)) return false;
        if (text.isBlank() && !CatalogQuality.discoveryBook(book)) return false;
        if (!CatalogQuality.matchesGenre(book, genre)) return false;
        if ("Kids".equals(genre) != ("Kids".equals(book.genre()) || BookAudience.isKids(book.id(), book.tags()))) return false;
        if (book.year() != null && book.year() > java.time.Year.now(java.time.ZoneOffset.UTC).getValue()) return false;
        if (window != null && !window.contains(book.year())) return false;
        if (minRating != null && minRating > 0 && (book.rating() == null || book.rating() < minRating)) return false;
        // A known old original must not be presented as new just because Google lists a recent edition.
        return window == null || catalog.stream().filter(local -> CatalogQuality.sameIdentity(local, book))
                .noneMatch(local -> local.year() != null && local.year() < window.firstYear());
    }

    private GoogleCursor parseGoogleCursor(String value) {
        if (value == null || !value.matches("gb:[1-9][0-9]?:[0-9]{1,2}"))
            badRequest("This Google Books browsing cursor is no longer valid. Start a new search.");
        String[] parts = value.split(":");
        int providerPage = Integer.parseInt(parts[1]);
        int itemOffset = Integer.parseInt(parts[2]);
        if (providerPage > 50 || itemOffset > 20) badRequest("Invalid position in the Google Books catalog page.");
        return new GoogleCursor(providerPage, itemOffset);
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

    private CatalogPage withProvider(CatalogPage result, ProviderOutcome outcome) {
        var providers = new java.util.ArrayList<ProviderOutcome>();
        providers.add(outcome);
        providers.addAll(result.providers());
        return new CatalogPage(result.books(), result.page(), result.pageSize(), result.total(), result.hasMore(),
                result.source(), result.message(), result.nextCursor(), result.minPublicationYear(), result.maxPublicationYear(),
                List.copyOf(providers), result.publicationBasis());
    }

    private CatalogPage withPublicationWindow(CatalogPage result, PublicationWindow window) {
        String message = result.message();
        if (window != null) message += "edition".equals(result.publicationBasis())
                ? " English editions published " + window.label() + ". Edition dates may be newer than a work's original publication. Search a title or author to include older books."
                : " English editions. First published " + window.label() + ". Search a title or author to include older books.";
        return new CatalogPage(result.books(), result.page(), result.pageSize(), result.total(), result.hasMore(),
                result.source(), message, result.nextCursor(), window == null ? null : window.firstYear(), window == null ? null : window.lastYear(),
                result.providers(), result.publicationBasis());
    }

    public record CatalogPage(List<BookPreview> books, int page, int pageSize, Long total,
            boolean hasMore, String source, String message, String nextCursor, Integer minPublicationYear, Integer maxPublicationYear,
            List<ProviderOutcome> providers, String publicationBasis) {
        public CatalogPage { providers = providers == null ? List.of() : List.copyOf(providers); }
        CatalogPage(List<BookPreview> books, int page, int pageSize, Long total,
                boolean hasMore, String source, String message, String nextCursor) {
            this(books, page, pageSize, total, hasMore, source, message, nextCursor, null, null, List.of(), "original");
        }
        CatalogPage(List<BookPreview> books, int page, int pageSize, Long total,
                boolean hasMore, String source, String message, String nextCursor, Integer minPublicationYear, Integer maxPublicationYear) {
            this(books, page, pageSize, total, hasMore, source, message, nextCursor, minPublicationYear, maxPublicationYear, List.of(), "original");
        }
    }
}

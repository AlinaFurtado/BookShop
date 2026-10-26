package com.bookshelf.myBooks;

import java.io.IOException;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Provider mocks exercise catalog routing and cursor guarantees without a key or a live request. */
class CatalogGoogleTests {
    private final OpenLibraryService ol = mock(OpenLibraryService.class);
    private final GoogleBooksService google = mock(GoogleBooksService.class);
    private CatalogService catalog;
    private final int year = Year.now(ZoneOffset.UTC).getValue();

    @BeforeEach
    void setUp() throws IOException {
        catalog = new CatalogService(JsonMapper.builder().build(), ol, google);
        when(google.searchPage(anyString(), anyString(), nullable(Double.class), anyInt(), anyBoolean()))
                .thenReturn(page(List.of(), 0, false, "empty", 0));
        when(ol.searchPage(anyString(), anyString(), nullable(Double.class), anyInt()))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(), 0, true, false));
        when(ol.trendingPage(anyString(), nullable(Double.class), anyInt()))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(), 0, true, false));
    }

    @Test
    void servesElevenAtATimeWithoutDroppingGoogleTwentyRecordPageOverflow() {
        List<BookPreview> first = books(0, 20);
        List<BookPreview> second = books(20, 25);
        when(google.searchPage("", "Fantasy", null, 1, false)).thenReturn(page(first, 25, true, "ok", 20));
        when(google.searchPage("", "Fantasy", null, 2, false)).thenReturn(page(second, 25, false, "ok", 5));
        var one = catalog.browse("", "Fantasy", null, 1);
        assertEquals(11, one.books().size());
        assertEquals("gb:1:11", one.nextCursor());
        assertEquals("googlebooks", one.source());
        assertEquals("edition", one.publicationBasis());
        assertEquals(year - 4, one.minPublicationYear());
        assertNull(one.total(), "Provider totals include editions rejected by local filters");
        assertEquals(20, one.providers().getFirst().received());
        assertEquals(11, one.providers().getFirst().accepted());
        var two = catalog.browse("", "Fantasy", null, 2, one.nextCursor());
        assertEquals(11, two.books().size());
        assertEquals("gb:2:2", two.nextCursor());
        var three = catalog.browse("", "Fantasy", null, 3, two.nextCursor());
        assertEquals(3, three.books().size());
        assertFalse(three.hasMore());
        assertNull(three.nextCursor());
        List<String> actual = new ArrayList<>();
        for (var result : List.of(one, two, three)) actual.addAll(result.books().stream().map(BookPreview::id).toList());
        assertEquals(25, new HashSet<>(actual).size());
        assertEquals(books(0, 25).stream().map(BookPreview::id).toList(), actual);
        verifyNoInteractions(ol);
    }

    @Test
    void fallsBackOnlyForARealGoogleConfigurationQuotaOrConnectionFailure() {
        BookPreview fallback = book("/works/OL123W", "A Real Open Library Work", "Writer", "Fantasy", year);
        when(ol.searchPage("", "Fantasy", null, 1))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(fallback), 1, true, false));
        for (String state : List.of("not_configured", "quota_limited", "unavailable")) {
            when(google.searchPage("", "Fantasy", null, 1, false)).thenReturn(page(List.of(), 0, false, state, 0));
            var result = catalog.browse("", "Fantasy", null, 1);
            assertEquals("openlibrary", result.source());
            assertEquals("original", result.publicationBasis());
            assertEquals(List.of(fallback.id()), result.books().stream().map(BookPreview::id).toList());
            assertEquals("Google Books", result.providers().getFirst().provider());
            assertEquals(state, result.providers().getFirst().state());
            assertEquals(0, result.providers().getFirst().accepted());
        }
    }

    @Test
    void duplicateEditionsInOneProviderPageCannotReappearAcrossItsDisplayBatches() {
        var editions = new ArrayList<>(books(0, 15));
        editions.add(book("googlebooks:otherEdition", "A Catalog Novel 0", "Writer", "Fantasy", year));
        editions.add(book("googlebooks:anotherEdition", "A Catalog Novel 12", "Writer", "Fantasy", year));
        when(google.searchPage("", "Fantasy", null, 1, false)).thenReturn(page(editions, 17, false, "ok", 17));
        var one = catalog.browse("", "Fantasy", null, 1);
        var two = catalog.browse("", "Fantasy", null, 2, one.nextCursor());
        assertEquals(11, one.books().size());
        assertEquals(4, two.books().size());
        var all = new ArrayList<>(one.books());
        all.addAll(two.books());
        assertEquals(15, all.stream().map(CatalogQuality::identity).distinct().count());
        assertEquals(books(0, 15).stream().map(BookPreview::id).toList(), all.stream().map(BookPreview::id).toList());
    }

    @Test
    void aSuccessfulEmptyOrFilteredGoogleResponseDoesNotSilentlySwitchCatalogs() {
        for (String state : List.of("empty", "filtered")) {
            when(google.searchPage("", "Fantasy", 4.5, 1, false))
                    .thenReturn(page(List.of(), 0, false, state, state.equals("filtered") ? 20 : 0));
            var result = catalog.browse("", "Fantasy", 4.5, 1);
            assertEquals("googlebooks", result.source());
            assertTrue(result.books().isEmpty());
            assertEquals(state, result.providers().getFirst().state());
            assertFalse(result.hasMore());
        }
        verifyNoInteractions(ol);
    }

    @Test
    void continuationFailureKeepsGoogleSourceAndExactCursorRatherThanLosingPosition() {
        when(google.searchPage("", "Fantasy", null, 2, false))
                .thenReturn(page(List.of(), 0, false, "unavailable", 0));
        var failed = catalog.browse("", "Fantasy", null, 2, "gb:2:2");
        assertEquals("googlebooks", failed.source());
        assertTrue(failed.books().isEmpty());
        assertEquals("gb:2:2", failed.nextCursor());
        assertTrue(failed.hasMore());
        assertTrue(failed.message().contains("position is kept"));
        assertEquals("unavailable", failed.providers().getFirst().state());
        when(google.searchPage("", "Fantasy", null, 2, false))
                .thenReturn(page(books(20, 25), 25, false, "ok", 5));
        var recovered = catalog.browse("", "Fantasy", null, 3, failed.nextCursor());
        assertEquals(books(22, 25).stream().map(BookPreview::id).toList(), recovered.books().stream().map(BookPreview::id).toList());
        verifyNoInteractions(ol);
    }

    @Test
    void recentDiscoveryLabelsEditionDatesAndRejectsKnownOldOriginalsWithoutMandatoryOpenLibraryLookups() {
        BookPreview old = catalog.curatedBooks().stream()
                .filter(book -> book.year() != null && book.year() < year - 4 && !"Kids".equals(book.genre()))
                .findFirst().orElseThrow();
        BookPreview reprint = book("googlebooks:reprint", old.title(), old.author(), old.genre(), year);
        BookPreview modern = book("googlebooks:modern", "A Recent Original Candidate", "Writer", old.genre(), year);
        BookPreview dated = book("googlebooks:dated", "An Old Edition", "Writer", old.genre(), 1980);
        BookPreview missing = book("googlebooks:missing", "An Undated Edition", "Writer", old.genre(), null);
        when(google.searchPage("", "", null, 1, false))
                .thenReturn(page(List.of(reprint, modern, dated, missing), 4, false, "ok", 4));
        var result = catalog.browse("", "", null, 1);
        assertEquals(List.of(modern.id()), result.books().stream().map(BookPreview::id).toList());
        assertTrue(result.message().contains("Edition dates may be newer"));
        assertFalse(result.message().contains("First published"));
        assertEquals("edition", result.publicationBasis());
        assertEquals(4, result.providers().getFirst().received());
        assertEquals(1, result.providers().getFirst().accepted());
        when(google.searchPage(old.title(), "", null, 1, true))
                .thenReturn(page(List.of(reprint), 1, false, "ok", 1));
        var explicit = catalog.browse(old.title(), "", null, 1);
        assertEquals(List.of(reprint.id()), explicit.books().stream().map(BookPreview::id).toList());
        assertNull(explicit.minPublicationYear());
        verifyNoInteractions(ol);
    }

    @Test
    void discoveryFiltersUnknownAuthorsAndAncillaryRecordsButAnExplicitBookSearchMayRetainUnknownAuthor() {
        BookPreview unknown = book("googlebooks:unknown", "An Obscure Novel", "Unknown author", "Fantasy", year);
        BookPreview workbook = book("googlebooks:workbook", "Dragon Workbook", "Writer", "Fantasy", year);
        when(google.searchPage("", "Fantasy", null, 1, false))
                .thenReturn(page(List.of(unknown, workbook), 2, false, "ok", 2));
        assertTrue(catalog.browse("", "Fantasy", null, 1).books().isEmpty());
        when(google.searchPage("An Obscure Novel", "Fantasy", null, 1, true))
                .thenReturn(page(List.of(unknown), 1, false, "ok", 1));
        assertEquals(List.of(unknown), catalog.browse("An Obscure Novel", "Fantasy", null, 1).books());
        verifyNoInteractions(ol);
    }

    @Test
    void sparseDiscoveryFetchesAtMostThreeProviderPagesAndKeepsTheNextLocation() {
        when(google.searchPage(eq(""), eq("Fantasy"), isNull(), anyInt(), eq(false)))
                .thenAnswer(call -> page(List.of(), 1000, true, "filtered", 20));
        var result = catalog.browse("", "Fantasy", null, 1);
        assertTrue(result.books().isEmpty());
        assertEquals("gb:4:0", result.nextCursor());
        assertEquals(60, result.providers().getFirst().received());
        verify(google, times(3)).searchPage(eq(""), eq("Fantasy"), isNull(), anyInt(), eq(false));
        verifyNoInteractions(ol);
    }

    @Test
    void trendingRemainsOpenLibraryAndRejectsGoogleCursors() {
        BookPreview trend = book("/works/OL123W", "A Current Trend", "Writer", "Fantasy", year);
        when(ol.trendingPage("Fantasy", null, 1))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(trend), 1, true, false));
        var result = catalog.trending("Fantasy", null, 1, null);
        assertEquals("openlibrary", result.source());
        assertEquals("original", result.publicationBasis());
        assertEquals(List.of(trend), result.books());
        assertThrows(ResponseStatusException.class, () -> catalog.trending("Fantasy", null, 2, "gb:1:11"));
        verifyNoInteractions(google);
    }

    @Test
    void malformedGoogleCursorsAreRejectedWithoutSpendingProviderQuota() {
        for (String cursor : List.of("gb:0:0", "gb:51:0", "gb:1:21", "gb:1:-1", "gb:1:0:extra"))
            assertThrows(ResponseStatusException.class, () -> catalog.browse("", "Fantasy", null, 2, cursor));
        verifyNoInteractions(google, ol);
    }

    private List<BookPreview> books(int start, int end) {
        var books = new ArrayList<BookPreview>();
        for (int index = start; index < end; index++)
            books.add(book("googlebooks:volume" + index, "A Catalog Novel " + index, "Writer", "Fantasy", year));
        return List.copyOf(books);
    }

    private static BookPreview book(String id, String title, String author, String genre, Integer year) {
        return new BookPreview(id, title, author, genre, null, 4.7, 15, year, "A source description.",
                id.startsWith("googlebooks:") ? "googlebooks" : "openlibrary", List.of(genre), null);
    }

    private static GoogleBooksService.GooglePage page(List<BookPreview> books, long total, boolean hasMore, String state, int received) {
        return new GoogleBooksService.GooglePage(books, total, hasMore,
                new ProviderOutcome("Google Books", state, "Google Books: " + state, received, books.size()));
    }
}

package com.bookshelf.myBooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** An in-process catalog server makes these integration tests free and repeatable. */
class CatalogFilterTests {
    private HttpServer server;
    private CatalogService catalog;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private volatile boolean unavailable;
    private volatile boolean pagedFixture;
    private volatile boolean sparseFixture;
    private volatile int fixtureTotal = 60;
    private volatile List<Map<String, Object>> audienceDocs;

    @BeforeEach
    void startCatalogStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search.json", this::respond);
        server.start();
        catalog = new CatalogService(json, new OpenLibraryService(true, baseUrl()));
    }

    @AfterEach
    void stopCatalogStub() { server.stop(0); }

    @Test
    void ratingAloneBrowsesLiveCatalogAndRejectsLowOrMissingRatings() {
        List<BookPreview> books = catalog.featured(null, 4.5);

        assertEquals(1, requests.size(), "A rating alone must query Open Library");
        String query = requests.getFirst().get("q");
        assertTrue(query.contains("ratings_count:[1 TO *]"), query);
        assertFalse(query.contains("ratings_average:"), "Open Library cannot query an average-rating range");
        assertEquals("rating", requests.getFirst().get("sort"));
        assertTrue(query.contains("NOT "), "A rating-only browse must still exclude children's subjects");
        assertFalse(query.contains("subject:\"fantasy\""), "Rating alone must not force a story genre");
        assertFalse(books.isEmpty());
        assertTrue(books.stream().anyMatch(book -> "/works/OL90000001W".equals(book.id())),
                "Results must include live works outside the 36 bundled books");
        assertTrue(books.stream().allMatch(book -> book.rating() != null && book.rating() >= 4.5));
        assertTrue(books.stream().filter(book -> book.id().startsWith("/works/OL900000"))
                .allMatch(book -> "openlibrary".equals(book.source())));
    }

    @Test
    void genreAloneBrowsesLiveCatalogWithoutRequiringARating() {
        List<BookPreview> books = catalog.featured("Mystery", null);

        assertEquals(1, requests.size(), "A genre alone must query Open Library");
        String query = requests.getFirst().get("q").toLowerCase(Locale.ROOT);
        assertTrue(query.contains("subject"), query);
        assertFalse(query.contains("ratings_average") || query.contains("ratings_count"), query);
        assertTrue(books.stream().allMatch(book -> "Mystery".equals(book.genre())));
        assertTrue(books.stream().anyMatch(book -> "/works/OL90000005W".equals(book.id()) && book.rating() == null),
                "An unrated matching work must remain visible when no rating filter is selected");
    }

    @Test
    void genreAndRatingAreCombinedInTheRemoteQueryAndResults() {
        List<BookPreview> books = catalog.featured("Fantasy", 4.5);

        assertEquals(1, requests.size());
        String query = requests.getFirst().get("q").toLowerCase(Locale.ROOT);
        assertTrue(query.contains("subject") && query.contains("fantasy"), query);
        assertTrue(query.contains("ratings_count"), query);
        assertFalse(query.contains("ratings_average:"), query);
        assertEquals("rating", requests.getFirst().get("sort"));
        assertTrue(books.stream().anyMatch(book -> "/works/OL90000001W".equals(book.id())));
        assertTrue(books.stream().allMatch(book -> "Fantasy".equals(book.genre())
                && book.rating() != null && book.rating() >= 4.5));
    }

    @Test
    void cachedSearchesKeepTextGenreAndRatingSeparate() {
        catalog.search("lantern", "Fantasy", 4.5);
        catalog.search("lantern", "Fantasy", 4.5);
        assertEquals(1, requests.size(), "An identical search should use the cache");

        List<BookPreview> mystery = catalog.search("lantern", "Mystery", 4.5);
        assertEquals(2, requests.size(), "Changing genre must not reuse another genre's cache entry");
        assertTrue(mystery.stream().allMatch(book -> "Mystery".equals(book.genre())));

        catalog.search("lantern", "Fantasy", 4.0);
        assertEquals(3, requests.size(), "Changing rating must use a different cache entry");
        catalog.search("lantern sequel", "Fantasy", 4.0);
        assertEquals(4, requests.size(), "Changing search text must use a different cache entry");
    }

    @Test
    void elevenBookDisplayPagesKeepProviderOverflowWithoutSkippingWorks() {
        pagedFixture = true;
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = catalog.browse("lantern", "Fantasy", null, page++, cursor);
            assertEquals(11, result.pageSize());
            assertEquals("openlibrary", result.source());
            assertEquals(result.hasMore() ? 11 : 5, result.books().size());
            ids.addAll(result.books().stream().map(BookPreview::id).toList());
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);

        assertEquals(60, ids.size());
        assertEquals(60, new HashSet<>(ids).size(), "No duplicate or skipped overflow works");
        for (int index = 0; index < 60; index++) assertEquals("/works/OL" + (91000000 + index) + "W", ids.get(index));
        assertEquals(3, requests.size(), "Display pages reuse the remaining candidates from each provider page");
    }

    @Test
    void sparseRatingsUseBoundedOverfetchAndContinueAfterAShortPage() {
        sparseFixture = true;
        CatalogService.CatalogPage first = catalog.browse("lantern", "Fantasy", 4.5, 1);
        assertEquals(6, requests.size(), "Each request may scan at most six provider pages");
        assertEquals(6, first.books().size());
        assertTrue(first.hasMore());
        assertNotNull(first.nextCursor());
        assertNull(first.total(), "A candidate count must not pretend to be the exact rating-match count");

        CatalogService.CatalogPage second = catalog.browse("lantern", "Fantasy", 4.5, 2, first.nextCursor());
        assertEquals(8, requests.size());
        assertEquals(2, second.books().size());
        assertFalse(second.hasMore());
        List<String> ids = new ArrayList<>(first.books().stream().map(BookPreview::id).toList());
        ids.addAll(second.books().stream().map(BookPreview::id).toList());
        assertEquals(8, new HashSet<>(ids).size());
        for (int page = 0; page < 8; page++) assertEquals("/works/OL" + (91000000 + page * 24) + "W", ids.get(page));
    }

    @Test
    void initialUnfilteredBrowseQueriesTheWholeLiveSearchIndex() {
        pagedFixture = true;
        CatalogService.CatalogPage first = catalog.browse("", null, null, 1);
        assertEquals(11, first.books().size());
        assertEquals(11, first.pageSize());
        assertEquals("openlibrary", first.source());
        assertEquals(60L, first.total());
        assertEquals(PublicationWindow.forRequest("", false).query() + " AND language:eng", requests.getFirst().get("q"));
        assertEquals("new", requests.getFirst().get("sort"), "Default discovery must put new publications first");
        assertTrue(first.hasMore());
        CatalogService.CatalogPage second = catalog.browse("", null, null, 2, first.nextCursor());
        assertEquals(11, second.books().size());
        assertTrue(first.books().stream().noneMatch(book -> second.books().stream().anyMatch(next -> next.id().equals(book.id()))));
        assertEquals(1, requests.size(), "The second batch reuses the remaining results from the source page");
    }

    @Test
    void browsingContinuesBeyondAThousandMatchesAndSurvivesAnEmptyCache() throws IOException {
        pagedFixture = true;
        fixtureTotal = 1100;
        CatalogService.CatalogPage deep = catalog.browse("", null, null, 93, "ol:43:0");
        assertEquals("/works/OL91001008W", deep.books().getFirst().id());
        assertEquals(11, deep.books().size());
        assertTrue(deep.hasMore(), "One thousand matches must not end a larger search");
        assertEquals("43", requests.getFirst().get("page"));

        CatalogService freshCache = new CatalogService(json, new OpenLibraryService(true, baseUrl()));
        CatalogService.CatalogPage next = freshCache.browse("", null, null, 94, deep.nextCursor());
        assertEquals("/works/OL91001019W", next.books().getFirst().id());
        assertEquals(2, requests.size(), "A cursor resumes directly without replaying all earlier pages");
    }

    @Test
    void continuationKeepsItsPlaceAcrossAConnectionFailureAndRetry() {
        pagedFixture = true;
        CatalogService.CatalogPage first = catalog.browse("", null, null, 1);
        CatalogService.CatalogPage second = catalog.browse("", null, null, 2, first.nextCursor());
        unavailable = true;
        CatalogService.CatalogPage interrupted = catalog.browse("", null, null, 3, second.nextCursor());
        assertEquals("openlibrary", interrupted.source(), "A live continuation must not switch to the offline catalog");
        assertEquals(2, interrupted.books().size());
        assertEquals("ol:2:0", interrupted.nextCursor());
        unavailable = false;
        CatalogService.CatalogPage recovered = catalog.browse("", null, null, 4, interrupted.nextCursor());
        assertEquals("/works/OL91000024W", recovered.books().getFirst().id());
        assertEquals(11, recovered.books().size());
        assertEquals(3, requests.size(), "A failed page can be retried immediately; it is never skipped");
    }

    @Test
    void freshRetryLeavesTheOfflineCollectionWhenTheSourceRecovers() {
        unavailable = true;
        CatalogService.CatalogPage offline = catalog.browse("", null, null, 1);
        assertEquals("curated", offline.source());
        assertTrue(offline.message().contains("Retry Open Library"));
        unavailable = false;
        CatalogService.CatalogPage recovered = catalog.browse("", null, null, 1);
        assertEquals("openlibrary", recovered.source());
        assertTrue(recovered.books().stream().anyMatch(book -> "/works/OL90000001W".equals(book.id())));
        assertEquals(2, requests.size());
    }

    @Test
    void failedRemoteBrowseFallsBackToClearlyIdentifiedCuratedBooks() {
        unavailable = true;
        List<BookPreview> books = catalog.featured("Fantasy", null);

        assertEquals(1, requests.size());
        assertFalse(books.isEmpty(), "An outage should still allow local browsing");
        assertTrue(books.stream().allMatch(book -> "Fantasy".equals(book.genre())));
        assertTrue(books.stream().allMatch(book -> "curated".equals(book.source())),
                "The fallback must not claim its records came from a live request");
    }

    @Test
    void offlineModeCanStillApplyAGenreWithoutContactingOpenLibrary() throws IOException {
        CatalogService offline = new CatalogService(json, new OpenLibraryService(false, baseUrl()));
        List<BookPreview> books = offline.featured("Fantasy", null);

        assertFalse(books.isEmpty());
        assertTrue(books.stream().allMatch(book -> "Fantasy".equals(book.genre()) && "curated".equals(book.source())));
        assertTrue(requests.isEmpty());
    }

    @Test
    void generalAndStoryGenresRejectKidsEvenWhenTheProviderIgnoresAudienceExclusions() {
        List<String> lateAudienceTag = new ArrayList<>(java.util.Collections.nCopies(14, "Magic"));
        lateAudienceTag.add("Children’s fiction");
        audienceDocs = List.of(
                book("OL92000001W", "A Children's Book", 4.9, lateAudienceTag),
                book("OL92000002W", "Growing Up: An Adult Memoir", 4.7, List.of("Children", "Parenting", "Memoirs")),
                book("OL27482W", "The Hobbit", 4.8, List.of("Fantasy")),
                book("OL92000003W", "An Adult Fantasy", null, List.of("Fantasy")),
                book("OL92000004W", "A Teen Adventure", 4.6, List.of("Young adult fiction")));

        for (String genre : List.of("", "Fantasy", "Mystery", "Science Fiction", "Romance", "Classics", "Literary Fiction", "Nonfiction")) {
            List<String> ids = catalog.featured(genre, null).stream().map(BookPreview::id).toList();
            assertEquals(List.of("/works/OL92000002W", "/works/OL92000003W", "/works/OL92000004W"), ids,
                    "Children's audience tags and known works must be excluded from " + genre);
        }
        assertEquals(PublicationWindow.forRequest("", false).query() + " AND language:eng", requests.getFirst().get("q"), "General discovery should request recent first publications in English");
        assertTrue(requests.stream().skip(1).allMatch(request -> request.get("q").contains("NOT ")));
    }

    @Test
    void kidsGenreKeepsUnratedKidsAndAppliesTheSelectedRatingWithoutLeakingAdultWorks() {
        audienceDocs = List.of(
                book("OL92000001W", "Small Worlds", null, List.of("Fantasy", "Juvenile fiction")),
                book("OL92000002W", "A Picture Story", 4.8, List.of("Picture books")),
                book("OL92000003W", "A Grown-up Story", 4.9, List.of("Fantasy")),
                book("OL92000004W", "A Lower Rated Kids Book", 3.7, List.of("Children's stories")));
        List<BookPreview> kids = catalog.featured("kids", null);
        assertEquals(3, kids.size());
        assertTrue(kids.stream().allMatch(book -> "Kids".equals(book.genre())));
        assertTrue(kids.stream().anyMatch(book -> book.rating() == null));
        assertFalse(requests.getFirst().get("q").startsWith("*:* AND NOT "));
        assertFalse(requests.getFirst().get("q").contains("ratings_count"));
        List<BookPreview> rated = catalog.featured("Kids", 4.5);
        assertEquals(List.of("/works/OL92000002W"), rated.stream().map(BookPreview::id).toList());
    }

    @Test
    void filteringAudienceStillFillsElevenBookBatchesWithoutSkippingProviderOverflow() {
        audienceDocs = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            String id = "OL" + (92000000 + index) + "W";
            boolean kids = index % 3 == 0;
            audienceDocs.add(book(id, "Mixed Shelf " + index, 4.7,
                    List.of(kids ? "Juvenile fiction" : "Fantasy")));
            if (!kids) expected.add("/works/" + id);
        }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = catalog.browse("", "Fantasy", null, page++, cursor);
            assertEquals(result.hasMore() ? 11 : 7, result.books().size());
            assertNull(result.total(), "A mixed source count must not pretend to count the audience matches");
            actual.addAll(result.books().stream().map(BookPreview::id).toList());
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);
        assertEquals(expected, actual);
        assertEquals(3, requests.size());
    }

    @Test
    void auditedOlderReaderWorksStayOutsideKidsDespiteIncorrectChildrensSubjects() {
        audienceDocs = List.of(
                book("OL17860744W", "A Court of Mist and Fury", 4.6, List.of("Fantasy", "Children's fiction")),
                book("OL16014245W", "Shatter Me", 4.5, List.of("Juvenile fiction", "Children's fiction")),
                book("OL3140822W", "To Kill a Mockingbird", 4.4, List.of("Classics", "Juvenile fiction")),
                book("OL82563W", "Harry Potter and the Philosopher's Stone", 4.7, List.of("Juvenile fiction")));
        assertEquals(List.of("/works/OL82563W"), catalog.featured("Kids", null).stream().map(BookPreview::id).toList());
        assertEquals(List.of("/works/OL17860744W", "/works/OL16014245W", "/works/OL3140822W"),
                catalog.featured("", null).stream().map(BookPreview::id).toList());
    }

    @Test
    void offlineKidsAreSeparatedFromDefaultBrowseSearchAndEveryOtherGenre() throws IOException {
        CatalogService offline = new CatalogService(json, new OpenLibraryService(false, baseUrl()));
        List<BookPreview> kids = offline.search("Hobbit", "Kids", null);
        assertEquals(1, kids.size());
        assertTrue(kids.stream().allMatch(book -> "Kids".equals(book.genre())));
        assertTrue(kids.stream().anyMatch(book -> "The Hobbit".equals(book.title())));
        assertEquals(1, offline.search("Hobbit", "Kids", null).size());
        assertTrue(offline.featured("Kids", null).isEmpty(), "The offline children's classics are too old for default picks");
        assertTrue(offline.search("Hobbit", "Fantasy", null).isEmpty());
        List<String> childIds = kids.stream().map(BookPreview::id).toList();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = offline.browse("", null, null, page++, cursor);
            assertTrue(result.books().stream().noneMatch(book -> childIds.contains(book.id())));
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);
        assertTrue(requests.isEmpty());
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void suggestionsRequireEnglishEditionsAndDisplayTheirTitlesAndCoversRatherThanTheWorkDefaults() {
        Map<String, Object> translation = book("OL95000001W", "夜の物語", 4.7, List.of("Fantasy"));
        translation.put("author_name", List.of("村上 花子"));
        translation.put("cover_i", 111);
        translation.put("editions", Map.of("docs", List.of(Map.of("key", "/books/OL95000101M",
                "title", "Stories of the Night", "cover_i", 222, "language", List.of("eng")))));
        Map<String, Object> foreign = book("OL95000002W", "Une histoire", 4.9, List.of("Fantasy"));
        foreign.put("editions", Map.of("docs", List.of(Map.of("title", "Une histoire", "language", List.of("fre")))));
        Map<String, Object> unknown = book("OL95000003W", "An English-looking title", 4.8, List.of("Fantasy"));
        unknown.put("editions", Map.of("docs", List.of(Map.of("title", "An English-looking title"))));
        Map<String, Object> missing = book("OL95000004W", "No edition metadata", 4.7, List.of("Fantasy"));
        missing.remove("editions");
        Map<String, Object> noCover = book("OL95000005W", "A cover in another language", null, List.of("Fantasy"));
        noCover.put("cover_i", 333);
        noCover.put("editions", Map.of("docs", List.of(Map.of("title", "The English Edition", "language", List.of("eng")))));
        Map<String, Object> kids = book("OL95000006W", "English Kids Story", null, List.of("Children's fiction"));
        audienceDocs = List.of(translation, foreign, unknown, missing, noCover, kids);

        for (CatalogService.CatalogPage result : List.of(catalog.browse("", null, null, 1),
                catalog.browse("", "Fantasy", null, 1), catalog.trending(null, null, 1, null))) {
            assertEquals(List.of("/works/OL95000001W", "/works/OL95000005W"), result.books().stream().map(BookPreview::id).toList());
            BookPreview english = result.books().getFirst();
            assertEquals("Stories of the Night", english.title());
            assertEquals(222, english.coverId());
            assertEquals("村上 花子", english.author(), "Author names do not determine a book's reading language");
            assertEquals(4.7, english.rating(), "Ratings remain work-level data");
            assertNull(result.books().getLast().coverId(), "Do not substitute another language's work cover");
            assertNull(result.total(), "Rejected language records must not inflate the displayed match count");
            assertTrue(result.message().contains("English editions"));
        }
        assertEquals(List.of("/works/OL95000006W"), catalog.featured("Kids", null).stream().map(BookPreview::id).toList());
        assertTrue(requests.stream().allMatch(request -> request.get("q").contains("language:eng")));
        assertTrue(requests.stream().allMatch(request -> "en".equals(request.get("lang"))));
        assertTrue(requests.stream().allMatch(request -> request.get("fields").contains("editions.language")));
    }

    @Test
    void explicitTitleAndAuthorSearchesRemainOpenToOtherLanguagesAndOlderOriginals() {
        Map<String, Object> french = datedBook("OL95000007W", 1980);
        french.put("title", "Un roman français");
        french.put("editions", Map.of("docs", List.of(Map.of("title", "Un roman français", "language", List.of("fre")))));
        audienceDocs = List.of(french);
        assertTrue(catalog.featured("Fantasy", null).isEmpty());
        for (String text : List.of("Un roman français", "A favourite author")) {
            CatalogService.CatalogPage result = catalog.browse(text, "Fantasy", null, 1);
            assertEquals("Un roman français", result.books().getFirst().title());
            assertEquals(1980, result.books().getFirst().year());
            assertNull(result.minPublicationYear());
            assertFalse(requests.getLast().get("q").contains("language:eng"));
            assertFalse(result.message().contains("English editions"));
        }
    }

    @Test
    void languageFilteringFillsElevenBookBatchesAndPreservesEveryEnglishContinuationMatch() {
        audienceDocs = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            String id = "OL" + (95000200 + index) + "W";
            Map<String, Object> doc = book(id, "Mixed Language Shelf " + index, 4.7, List.of("Fantasy"));
            if (index % 3 == 0) doc.put("editions", Map.of("docs", List.of(Map.of("title", "Une histoire", "language", List.of("fre")))));
            else expected.add("/works/" + id);
            audienceDocs.add(doc);
        }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = catalog.browse("", null, null, page++, cursor);
            assertEquals(result.hasMore() ? 11 : 7, result.books().size());
            actual.addAll(result.books().stream().map(BookPreview::id).toList());
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);
        assertEquals(expected, actual);
        assertEquals(3, requests.size());
    }

    @Test
    void defaultDiscoveryAndTrendsRejectOldUnknownAndFutureOriginalYearsEvenIfTheSourceIgnoresTheRange() {
        PublicationWindow window = PublicationWindow.forRequest("", false);
        Map<String, Object> unknownYear = datedBook("OL94000003W", null);
        Map<String, Object> oldReprint = datedBook("OL94000004W", 1980);
        oldReprint.put("publish_year", List.of(window.lastYear()));
        audienceDocs = List.of(datedBook("OL94000001W", window.firstYear()), datedBook("OL94000002W", window.lastYear()),
                unknownYear, oldReprint, datedBook("OL94000005W", 1978), datedBook("OL94000006W", window.lastYear() + 1));
        CatalogService.CatalogPage discovery = catalog.browse("", null, null, 1);
        CatalogService.CatalogPage trends = catalog.trending(null, null, 1, null);
        for (CatalogService.CatalogPage result : List.of(discovery, trends)) {
            assertEquals(List.of("/works/OL94000001W", "/works/OL94000002W"), result.books().stream().map(BookPreview::id).toList());
            assertEquals(window.firstYear(), result.minPublicationYear());
            assertEquals(window.lastYear(), result.maxPublicationYear());
            assertNull(result.total(), "Rejected original years must not be included in a displayed match total");
        }
        assertEquals("new", requests.getFirst().get("sort"));
        assertEquals("trending", requests.getLast().get("sort"));
        assertTrue(requests.stream().allMatch(request -> request.get("q").contains(window.query())));
    }

    @Test
    void specificTitleOrAuthorSearchesKeepOlderWorksAndDoNotApplyTheSuggestionWindow() {
        audienceDocs = List.of(datedBook("OL94000004W", 1980));
        CatalogService.CatalogPage recent = catalog.browse("", "Fantasy", null, 1);
        assertTrue(recent.books().isEmpty());
        for (String text : List.of("An older novel", "A favourite author")) {
            CatalogService.CatalogPage search = catalog.browse(text, "Fantasy", null, 1);
            assertEquals(1980, search.books().getFirst().year());
            assertNull(search.minPublicationYear());
            assertNull(search.maxPublicationYear());
            assertFalse(requests.getLast().get("q").contains("first_publish_year:"));
        }
    }

    @Test
    void oldClassicsRequireAnExplicitSearchInsteadOfFillingARecentGenreWithOldBooks() {
        Map<String, Object> classic = datedBook("OL94000005W", 1978);
        classic.put("subject", List.of("Classics"));
        audienceDocs = List.of(classic);
        assertTrue(catalog.featured("Classics", null).isEmpty());
        assertTrue(catalog.trending("Classics", null, 1, null).books().isEmpty());
        assertEquals(1978, catalog.search("A classic title", "Classics", null).getFirst().year());
    }

    @Test
    void recentYearFilteringKeepsElevenBookBatchesAndEveryContinuationMatch() {
        audienceDocs = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        int currentYear = PublicationWindow.forRequest("", false).lastYear();
        for (int index = 0; index < 60; index++) {
            String id = "OL" + (94000100 + index) + "W";
            boolean old = index % 3 == 0;
            audienceDocs.add(datedBook(id, old ? 1980 : currentYear));
            if (!old) expected.add("/works/" + id);
        }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = catalog.browse("", null, null, page++, cursor);
            assertEquals(result.hasMore() ? 11 : 7, result.books().size());
            assertTrue(result.books().stream().allMatch(book -> book.year() == currentYear));
            actual.addAll(result.books().stream().map(BookPreview::id).toList());
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);
        assertEquals(expected, actual);
        assertEquals(3, requests.size());
    }

    @Test
    void offlineDefaultsAlsoUseRecentOriginalYearsButAnOlderSpecificSearchStillWorks() throws IOException {
        CatalogService offline = new CatalogService(json, new OpenLibraryService(false, baseUrl()));
        CatalogService.CatalogPage recent = offline.browse("", null, null, 1);
        PublicationWindow window = PublicationWindow.forRequest("", false);
        assertFalse(recent.books().isEmpty());
        assertTrue(recent.books().stream().allMatch(book -> window.contains(book.year())));
        assertTrue(offline.featured("Mystery", null).isEmpty(), "Never fill an empty recent category with old fallback titles");
        assertEquals("The Murder of Roger Ackroyd", offline.search("Roger Ackroyd", "Mystery", null).getFirst().title());
        assertTrue(requests.isEmpty());
    }

    private Map<String, Object> datedBook(String id, Integer year) {
        Map<String, Object> doc = book(id, "Publication test " + id, 4.7, List.of("Fantasy"));
        if (year == null) doc.remove("first_publish_year");
        else doc.put("first_publish_year", year);
        return doc;
    }

    @Test
    void trendsUseCurrentActivityAcrossGenresAndKeepProviderOrderRatherThanRatingOrder() {
        audienceDocs = List.of(
                book("OL93000001W", "A Trending Mystery", 4.6, List.of("Mystery")),
                book("OL93000002W", "A Trending Space Story", 4.9, List.of("Science fiction")),
                book("OL93000003W", "An Unrated Trend", null, List.of("Fantasy")),
                book("OL93000004W", "A Kids Trend", 4.9, List.of("Children's fiction")));
        CatalogService.CatalogPage trends = catalog.trending(null, null, 1, null);
        assertEquals(List.of("/works/OL93000001W", "/works/OL93000002W", "/works/OL93000003W"),
                trends.books().stream().map(BookPreview::id).toList());
        assertEquals(List.of("Mystery", "Science Fiction", "Fantasy"), trends.books().stream().map(BookPreview::genre).toList());
        assertEquals("openlibrary", trends.source());
        String query = requests.getFirst().get("q");
        assertTrue(query.contains("trending_score_hourly_sum:[1 TO *]"));
        assertTrue(query.contains("readinglog_count:[4 TO *]"));
        assertEquals("trending", requests.getFirst().get("sort"));
        assertFalse(query.contains("subject:\"fantasy\""), "All genres must not force a story genre");
        assertNull(trends.books().getLast().rating(), "Unrated trending works stay unrated");

        List<BookPreview> rated = catalog.trending(null, 4.5, 1, null).books();
        assertEquals(List.of("/works/OL93000001W", "/works/OL93000002W"), rated.stream().map(BookPreview::id).toList());
        assertEquals("trending", requests.getLast().get("sort"), "Minimum rating must not replace the trend order");
        List<BookPreview> kids = catalog.trending("Kids", null, 1, null).books();
        assertEquals(List.of("/works/OL93000004W"), kids.stream().map(BookPreview::id).toList());
        assertEquals("Kids", kids.getFirst().genre());
    }

    @Test
    void trendingContinuationReturnsElevenAtATimeWithoutSkippingOverflow() {
        pagedFixture = true;
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int page = 1;
        do {
            CatalogService.CatalogPage result = catalog.trending("Fantasy", null, page++, cursor);
            assertEquals(result.hasMore() ? 11 : 5, result.books().size());
            ids.addAll(result.books().stream().map(BookPreview::id).toList());
            cursor = result.nextCursor();
        } while (cursor != null && page < 10);
        assertEquals(60, ids.size());
        assertEquals(60, new HashSet<>(ids).size());
        for (int index = 0; index < 60; index++) assertEquals("/works/OL" + (91000000 + index) + "W", ids.get(index));
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(request -> "trending".equals(request.get("sort"))));
    }

    @Test
    void unavailableTrendsNeverInventOfflineTrendsAndCanBeRetriedImmediately() {
        unavailable = true;
        CatalogService.CatalogPage unavailableTrends = catalog.trending(null, null, 1, null);
        assertEquals("unavailable", unavailableTrends.source());
        assertTrue(unavailableTrends.books().isEmpty());
        unavailable = false;
        CatalogService.CatalogPage recovered = catalog.trending(null, null, 1, null);
        assertEquals("openlibrary", recovered.source());
        assertFalse(recovered.books().isEmpty());
        assertEquals(2, requests.size());
    }

    @Test
    void trendCacheCannotBeConfusedWithAnIdenticalTextQueryUsingAnotherSort() {
        catalog.trending(null, null, 1, null);
        catalog.trending(null, null, 1, null);
        assertEquals(1, requests.size());
        catalog.search("trending_score_hourly_sum:[1 TO *] AND readinglog_count:[4 TO *]", null, null);
        assertEquals(2, requests.size(), "Identical Solr text with a different sort needs its own cache key");
        assertEquals("trending", requests.getFirst().get("sort"));
        assertNull(requests.getLast().get("sort"));
    }

    private void respond(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = queryParameters(exchange.getRequestURI().getRawQuery());
        requests.add(parameters);
        byte[] body;
        int status;
        if (unavailable) {
            status = 503;
            body = "{\"message\":\"Catalog unavailable\"}".getBytes(StandardCharsets.UTF_8);
        } else {
            status = 200;
            if (audienceDocs != null) {
                int page = Integer.parseInt(parameters.getOrDefault("page", "1"));
                int start = Math.min((page - 1) * 24, audienceDocs.size());
                int end = Math.min(start + 24, audienceDocs.size());
                // Deliberately return mixed audiences even if q excludes them.
                body = json.writeValueAsBytes(Map.of("docs", audienceDocs.subList(start, end), "numFound", audienceDocs.size()));
                sendResponse(exchange, status, body);
                return;
            }
            if (pagedFixture || sparseFixture) {
                int page = Integer.parseInt(parameters.getOrDefault("page", "1"));
                int total = sparseFixture ? 8 * 24 : fixtureTotal;
                List<Map<String, Object>> docs = new ArrayList<>();
                for (int index = (page - 1) * 24; index < Math.min(page * 24, total); index++) {
                    double rating = sparseFixture && index % 24 != 0 ? 3.0 : 4.8;
                    docs.add(book("OL" + (91000000 + index) + "W", "Lantern " + index, rating, List.of("Fantasy")));
                }
                body = json.writeValueAsBytes(Map.of("docs", docs, "numFound", total));
                sendResponse(exchange, status, body);
                return;
            }
            String query = parameters.getOrDefault("q", "").toLowerCase(Locale.ROOT);
            List<Map<String, Object>> docs = new ArrayList<>();
            boolean hasGenre = query.contains("subject:\"fantasy\"") || query.contains("subject:\"mystery\"");
            if (!hasGenre || query.contains("fantasy")) {
                docs.add(book("OL90000001W", "Lantern of the Dragon", 4.8, List.of("Fantasy fiction")));
                docs.add(book("OL90000002W", "Lantern of Ash", 3.8, List.of("Fantasy")));
                docs.add(book("OL90000003W", "Lantern of Mist", null, List.of("Fantasy")));
            }
            if (!hasGenre || query.contains("mystery") || query.contains("detective")) {
                docs.add(book("OL90000004W", "The Lantern Case", 4.7, List.of("Mystery", "Detective fiction")));
                // Open Library's broad subjects do not always contain our exact display genre.
                docs.add(book("OL90000005W", "The Unrated Lantern Case", null, List.of("Crime fiction")));
                docs.add(book("OL90000006W", "The Second Lantern Case", 4.2, List.of("Mystery")));
            }
            // Include low/null ratings deliberately: the app must enforce its
            // selected threshold even if an upstream response is inconsistent.
            body = json.writeValueAsBytes(Map.of("docs", docs, "numFound", docs.size()));
        }
        sendResponse(exchange, status, body);
    }

    private void sendResponse(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private Map<String, Object> book(String id, String title, Double rating, List<String> subjects) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("key", "/works/" + id);
        doc.put("title", title);
        doc.put("author_name", List.of("Test Author"));
        doc.put("editions", Map.of("docs", List.of(Map.of("title", title, "language", List.of("eng")))));
        doc.put("subject", subjects);
        doc.put("first_publish_year", java.time.Year.now(java.time.ZoneOffset.UTC).getValue());
        if (rating != null) {
            doc.put("ratings_average", rating);
            doc.put("ratings_count", 20);
        }
        return doc;
    }

    private Map<String, String> queryParameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (query == null) return parameters;
        for (String parameter : query.split("&")) {
            String[] parts = parameter.split("=", 2);
            parameters.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }
}

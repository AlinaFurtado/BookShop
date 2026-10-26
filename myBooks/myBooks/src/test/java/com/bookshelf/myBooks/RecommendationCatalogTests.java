package com.bookshelf.myBooks;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protect the reader's filters across the boundary between two different catalogs. */
class RecommendationCatalogTests {
    private final OpenLibraryService ol = mock(OpenLibraryService.class);
    private final GoogleBooksService google = mock(GoogleBooksService.class);
    private final CatalogService bundled = mock(CatalogService.class);
    private RecommendationCatalog catalog;
    private final int year = Year.now(ZoneOffset.UTC).getValue();

    @BeforeEach void setup() {
        catalog = new RecommendationCatalog(ol, google, bundled);
        when(ol.recommendationPage(anyList(), anyString(), nullable(Double.class), anyBoolean()))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(), 0, true));
        when(ol.verifyRecommendation(any(), anyString(), nullable(Double.class), anyBoolean()))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(), 0, true));
        when(google.search(anyString(), anyString(), anyBoolean())).thenReturn(List.of());
        when(bundled.curatedBooks()).thenReturn(List.of());
    }

    @Test void selectedGenreWinsAndKidsCannotBeInferred() {
        var preferences = new RecommendationPreferences("Kids", List.of("magic"), List.of());
        catalog.candidates(preferences, "Mystery", null, false);
        verify(ol).recommendationPage(List.of("magic"), "Mystery", null, false);
        catalog.candidates(preferences, "", null, false);
        verify(ol).recommendationPage(List.of("magic"), "", null, false);
    }

    @Test void newGoogleEditionOfOldWorkCannotPassTheRecentOriginalRule() {
        BookPreview edition = book("googlebooks:newEdition", "A Reprint", "Writer", "Fantasy", year, 4.8, "A description");
        when(google.search(anyString(), eq("Fantasy"), eq(false))).thenReturn(List.of(edition));
        when(ol.verifyRecommendation(edition, "Fantasy", null, false)).thenReturn(new OpenLibraryService.SearchPage(
                List.of(book("/works/OL1W", "A Reprint", "Writer", "Fantasy", 1980, 4.8, "")), 1, true));
        var response = catalog.candidates(preferences(), "Fantasy", null, false);
        assertTrue(response.books().isEmpty());
        assertTrue(response.note().contains("omitted"));
        assertTrue(response.sources().isEmpty(), "A rejected edition is not a contributing source");
    }

    @Test void verifiedRecentGoogleEditionUsesCanonicalWorkAndKeepsOriginalRatingYear() {
        BookPreview edition = book("googlebooks:newBook", "New Book", "Writer", "Fantasy", year, 3.1, "Google description");
        BookPreview work = book("/works/OL2W", "New Book", "Writer", "Fantasy", year - 1, 4.7, "");
        when(google.search(anyString(), eq("Fantasy"), eq(false))).thenReturn(List.of(edition));
        when(ol.verifyRecommendation(edition, "Fantasy", 4.5, false)).thenReturn(new OpenLibraryService.SearchPage(List.of(work), 1, true));
        var response = catalog.candidates(preferences(), "Fantasy", 4.5, false);
        assertEquals(1, response.books().size());
        assertEquals("/works/OL2W", response.books().getFirst().id());
        assertEquals(year - 1, response.books().getFirst().year());
        assertEquals(4.7, response.books().getFirst().rating());
        assertEquals("Google description", response.books().getFirst().description());
        assertEquals(List.of("Open Library", "Google Books"), response.sources());
        assertFalse(response.note().contains("omitted"));
    }

    @Test void exclusionsMatchWholeWordsAndUnknownAuthorsCannotCrossEnrich() {
        BookPreview warm = book("/works/OL3W", "A Warm Story", "Writer", "Fantasy", year, 4.7, "Award-winning warm friendship");
        BookPreview war = book("/works/OL4W", "The War", "Writer", "Fantasy", year, 4.8, "War and loss");
        when(ol.recommendationPage(anyList(), eq("Fantasy"), isNull(), eq(false)))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(warm, war), 2, true));
        assertEquals(List.of(warm), catalog.candidates(new RecommendationPreferences("Fantasy", List.of("friendship"), List.of("war")), "Fantasy", null, false).books());
        BookPreview unknown = book("/works/OL5W", "Same Title", "Unknown author", "Fantasy", year, null, "");
        BookPreview unrelated = book("googlebooks:another", "Same Title", "Unknown author", "Fantasy", year, null, "Unverified prose");
        assertSame(unknown, RecommendationCatalog.matchingDescription(unknown, List.of(unrelated)));
    }

    @Test void olderToggleAllowsGoogleRecordsButNotFutureBooksUnratedOrKids() {
        List<BookPreview> editions = List.of(
                book("googlebooks:old", "Old Book", "Writer", "Fantasy", 1980, 4.8, "A story"),
                book("googlebooks:future", "Future Book", "Writer", "Fantasy", year + 1, 4.9, "A story"),
                book("googlebooks:unrated", "Unrated Book", "Writer", "Fantasy", year, null, "A story"),
                book("googlebooks:kids", "Kids Book", "Writer", "Kids", year, 4.9, "A story"));
        when(google.search(anyString(), eq("Fantasy"), eq(true))).thenReturn(editions);
        var response = catalog.candidates(preferences(), "Fantasy", 4.5, true);
        assertEquals(List.of("googlebooks:old"), response.books().stream().map(BookPreview::id).toList());
        assertTrue(response.note().contains("editions"));
        verify(ol, never()).verifyRecommendation(any(), anyString(), any(), anyBoolean());
    }

    private RecommendationPreferences preferences() { return new RecommendationPreferences("Fantasy", List.of("magic"), List.of()); }
    private BookPreview book(String id, String title, String author, String genre, Integer publication, Double rating, String description) {
        return new BookPreview(id, title, author, genre, null, rating, rating == null ? null : 5, publication,
                description, id.startsWith("googlebooks:") ? "googlebooks" : "openlibrary", List.of(genre), null);
    }
}

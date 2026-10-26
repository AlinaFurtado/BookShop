package com.bookshelf.myBooks;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression coverage for real Google-first candidates, source failures and recent books. */
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
        when(google.searchQueries(anyList(), anyString(), anyBoolean())).thenReturn(result(List.of(), "empty"));
        when(bundled.curatedBooks()).thenReturn(List.of());
    }

    @Test void selectedGenreWinsAndKidsCannotBeInferred() {
        var preferences = new RecommendationPreferences("Kids", List.of("magic"), List.of());
        catalog.candidates(preferences, "Mystery", null, false);
        verify(google).searchQueries(anyList(), eq("Mystery"), eq(false));
        catalog.candidates(preferences, "", null, false);
        verify(google).searchQueries(anyList(), eq(""), eq(false));
    }

    @Test void currentGoogleBooksDoNotRequireAnOpenLibraryDuplicate() {
        BookPreview modern = book("googlebooks:modern", "Modern Dragons", "Writer", "Fantasy", year, 4.8, "Dragon riders and friendship.");
        when(google.searchQueries(anyList(), eq("Fantasy"), eq(false))).thenReturn(result(List.of(modern), "ok"));
        var response = catalog.candidates(preferences(), "Fantasy", null, false);
        assertEquals(List.of(modern), response.books());
        assertEquals(List.of("Google Books"), response.sources());
        assertEquals("ok", response.providers().getFirst().state());
        assertEquals(1, response.providers().getFirst().accepted());
        assertTrue(response.note().contains("editions"), "Do not label an edition date as an original year");
        verify(ol, never()).verifyRecommendation(any(), anyString(), any(), anyBoolean());
    }

    @Test void knownOldOriginalIsNotPromotedBecauseOfANewGoogleEdition() {
        BookPreview reprint = book("googlebooks:reprint", "An Old Work", "Writer", "Fantasy", year, 4.8, "Dragon riders.");
        when(google.searchQueries(anyList(), eq("Fantasy"), eq(false))).thenReturn(result(List.of(reprint), "ok"));
        when(bundled.curatedBooks()).thenReturn(List.of(book("/works/OL1W", "An Old Work", "Writer", "Fantasy", 1980, 4.8, "")));
        var response = catalog.candidates(preferences(), "Fantasy", null, false);
        assertTrue(response.books().isEmpty());
        assertEquals("filtered", response.providers().getFirst().state());
        assertTrue(response.note().contains("Known older originals"));
    }

    @Test void referenceLookupUsesAnActualBookRecordAndDoesNotSearchItsTitleAsATrait() {
        BookPreview anchor = book("googlebooks:anchor", "Fourth Wing", "Rebecca Yarros", "Fantasy", year - 1, 4.5, "A military academy and dragon riders.");
        when(google.lookupReference("Fourth Wing")).thenReturn(result(List.of(anchor), "ok"));
        assertEquals(anchor, catalog.resolveReference("Fourth Wing").book());
        verifyNoInteractions(ol);
        var preferences = new RecommendationPreferences("Fantasy", List.of("dragon riders", "romance"), List.of(), "Fourth Wing", List.of("Fourth Wing", "dragon riders"));
        catalog.candidates(preferences, "", null, false);
        verify(google).searchQueries(argThat(queries -> !queries.contains("Fourth Wing") && queries.contains("dragon riders") && queries.size() <= 2), eq("Fantasy"), eq(false));
    }

    @Test void googleFailureStaysVisibleWhenSupportingCatalogHasMatches() {
        when(google.searchQueries(anyList(), anyString(), anyBoolean())).thenReturn(result(List.of(), "unavailable"));
        BookPreview support = book("/works/OL2W", "Modern Dragons", "Writer", "Fantasy", year, 4.7, "Dragon riders.");
        when(ol.recommendationPage(anyList(), eq("Fantasy"), isNull(), eq(true)))
                .thenReturn(new OpenLibraryService.SearchPage(List.of(support), 1, true));
        var response = catalog.candidates(preferences(), "Fantasy", null, false);
        assertEquals(List.of(support), response.books());
        assertEquals("unavailable", response.providers().getFirst().state());
        assertEquals(List.of("Open Library"), response.sources());
    }

    @Test void exclusionsMatchWholeWordsAndMissingAuthorsCannotCrossEnrich() {
        BookPreview warm = book("googlebooks:warm", "A Warm Story", "Writer", "Fantasy", year, 4.7, "Award-winning warm friendship.");
        BookPreview war = book("googlebooks:war", "The War", "Writer", "Fantasy", year, 4.8, "War and loss.");
        when(google.searchQueries(anyList(), anyString(), anyBoolean())).thenReturn(result(List.of(warm, war), "ok"));
        assertEquals(List.of(warm), catalog.candidates(new RecommendationPreferences("Fantasy", List.of("friendship"), List.of("war")), "Fantasy", null, false).books());
        BookPreview unknown = book("/works/OL3W", "Same Title", "Unknown author", "Fantasy", year, null, "");
        assertSame(unknown, RecommendationCatalog.matchingDescription(unknown,
                List.of(book("googlebooks:another", "Same Title", "Unknown author", "Fantasy", year, null, "Wrong prose"))));
    }

    @Test void recommendationsRejectStudyAidsKidsFutureUnknownAndLowRatings() {
        List<BookPreview> records = List.of(
                book("googlebooks:real", "Dragon Academy", "Writer", "Fantasy", year, 4.8, "Dragon riders."),
                book("googlebooks:study", "Study Guide to Dragon Academy", "Writer", "Fantasy", year, 4.8, "A study guide."),
                book("googlebooks:future", "Future Dragons", "Writer", "Fantasy", year + 1, 4.9, "Dragon riders."),
                book("googlebooks:unknown", "A Mystery Record", "Unknown author", "Fantasy", year, 4.9, "Dragon riders."),
                book("googlebooks:kids", "Little Dragons", "Writer", "Kids", year, 4.9, "Dragon riders."),
                book("googlebooks:unrated", "Unrated Dragons", "Writer", "Fantasy", year, null, "Dragon riders."));
        when(google.searchQueries(anyList(), anyString(), anyBoolean())).thenReturn(result(records, "ok"));
        assertEquals(List.of("googlebooks:real"), catalog.candidates(preferences(), "Fantasy", 4.5, false).books().stream().map(BookPreview::id).toList());
    }

    private GoogleBooksService.SearchResult result(List<BookPreview> books, String state) {
        return new GoogleBooksService.SearchResult(books, new ProviderOutcome("Google Books", state,
                state.equals("unavailable") ? "Google Books could not be reached." : "Catalog request finished.", books.size(), books.size()));
    }
    private RecommendationPreferences preferences() { return new RecommendationPreferences("Fantasy", List.of("dragon riders"), List.of()); }
    private BookPreview book(String id, String title, String author, String genre, Integer publication, Double rating, String description) {
        return new BookPreview(id, title, author, genre, null, rating, rating == null ? null : 5, publication,
                description, id.startsWith("googlebooks:") ? "googlebooks" : "openlibrary", List.of(genre), null);
    }
}

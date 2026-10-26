package com.bookshelf.myBooks;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookDetailsTests {
    private final OpenLibraryService ol = mock(OpenLibraryService.class);
    private final GoogleBooksService google = mock(GoogleBooksService.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private BookDetailsController details;

    @BeforeEach void setup() {
        when(catalog.curatedBooks()).thenReturn(List.of());
        when(ol.description("/works/OL1W")).thenReturn("");
        details = new BookDetailsController(ol, google, catalog);
    }

    @Test void exactWorkDescriptionHasItsOwnSourceAndNeedsNoGoogleRequest() {
        when(ol.description("/works/OL1W")).thenReturn("An actual work description.");
        var response = details.details("/works/OL1W", "Title", "Writer");
        assertEquals("Open Library", response.descriptionSource());
        assertEquals("An actual work description.", response.book().description());
        verifyNoInteractions(google);
    }

    @Test void callerCannotAttachAnUnrelatedGoogleDescriptionToAWorkId() {
        when(ol.metadata("/works/OL1W")).thenReturn(book("Real Title", "Real Writer", ""));
        var response = details.details("/works/OL1W", "Other Title", "Other Writer");
        assertTrue(response.book().description().isBlank());
        assertTrue(response.note().contains("verified"));
        verifyNoInteractions(google);
    }

    @Test void googleFallbackRequiresVerifiedCatalogIdentityAndDoesNotInventRatings() {
        BookPreview canonical = book("Title", "Writer", "");
        when(ol.metadata("/works/OL1W")).thenReturn(canonical);
        when(google.enrich(canonical)).thenReturn(book("Title", "Writer", "Description from Google."));
        var response = details.details("/works/OL1W", "Title", "Writer");
        assertEquals("Google Books", response.descriptionSource());
        assertEquals("/works/OL1W", response.book().id());
        assertNull(response.book().rating());
        assertNull(response.book().year());
        assertEquals("Description from Google.", response.book().description());
    }

    @Test void invalidIdentifiersCannotSelectArbitraryProviderPaths() {
        for (String id : List.of("https://example.com/secret", "googlebooks:../../secret", "/works/OL" + "1".repeat(150) + "W"))
            assertThrows(ResponseStatusException.class, () -> details.details(id, "Title", "Writer"));
        verifyNoInteractions(ol, google);
    }

    private BookPreview book(String title, String author, String text) {
        return new BookPreview("/works/OL1W", title, author, "General", null, null, null, null,
                text, "openlibrary", List.of(), null);
    }
}

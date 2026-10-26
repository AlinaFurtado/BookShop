package com.bookshelf.myBooks;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationServiceTests {
    @Test
    void repeatedRequestsAreCachedPerReaderAndFiltersReachTheCatalog() {
        var ai = mock(GroqRecommendationClient.class);
        var catalog = mock(RecommendationCatalog.class);
        var preferences = new RecommendationPreferences("Fantasy", List.of("friendship"), List.of("horror"));
        var book = new BookPreview("/works/OL1W", "A real title", "An author", "Fantasy", null, null, null,
                Year.now(ZoneOffset.UTC).getValue(), "A source description", "Open Library", List.of(), null);
        when(ai.available()).thenReturn(true);
        when(ai.extract(anyString(), eq("Fantasy"))).thenReturn(preferences);
        when(catalog.candidates(preferences, "Fantasy", 4.0, false)).thenReturn(
                new RecommendationCatalog.CandidateBatch(List.of(book), List.of("Open Library"), ""));
        when(ai.rank(anyString(), eq(preferences), eq(List.of(book)))).thenReturn(List.of(book));
        var service = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        var request = new RecommendationService.Request("A cosy adventure about friendship", "Fantasy", 4.0, false);
        var initial = service.recommend("alice", request);
        assertFalse(initial.cached());
        assertEquals(Year.now(ZoneOffset.UTC).getValue() - 4, initial.minPublicationYear());
        assertTrue(service.recommend("alice", request).cached());
        assertFalse(service.recommend("bob", request).cached(), "One reader's search must not read another's private cache");
        verify(ai, times(2)).extract(request.prompt(), "Fantasy");
        verify(catalog, times(2)).candidates(preferences, "Fantasy", 4.0, false);
    }

    @Test
    void invalidInputsNeverReachAiAndProviderErrorsPauseFreshRequests() {
        var ai = mock(GroqRecommendationClient.class);
        var catalog = mock(RecommendationCatalog.class);
        when(ai.available()).thenReturn(true);
        var service = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        assertThrows(ResponseStatusException.class, () -> service.recommend("alice", new RecommendationService.Request("short", "", null, false)));
        assertThrows(ResponseStatusException.class, () -> service.recommend("alice", new RecommendationService.Request("A longer request", "", Double.NaN, false)));
        verify(ai, never()).extract(anyString(), anyString());
        when(ai.extract(anyString(), anyString())).thenThrow(new GroqRecommendationClient.ProviderFailure(429, 120, "Temporary free-tier limit."));
        var request = new RecommendationService.Request("A longer valid request", "", null, false);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.recommend("alice", request)).getStatusCode().value());
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.recommend("bob", request)).getStatusCode().value());
        verify(ai, times(1)).extract(anyString(), anyString());
        verifyNoInteractions(catalog);
    }
}

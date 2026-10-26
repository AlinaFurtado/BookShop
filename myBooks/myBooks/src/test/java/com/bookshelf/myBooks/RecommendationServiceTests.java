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
        assertEquals("ok", initial.providers().getLast().state());
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
        var failure = service.recommend("alice", request);
        assertTrue(failure.books().isEmpty());
        assertEquals("quota_limited", failure.providers().getLast().state());
        assertEquals("Temporary free-tier limit.", failure.message());
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.recommend("bob", request)).getStatusCode().value());
        verify(ai, times(1)).extract(anyString(), anyString());
        verifyNoInteractions(catalog);
    }

    @Test
    void refreshBypassesCacheButStillConsumesReaderBudgetAndANewPromptIsIndependent() {
        var ai = mock(GroqRecommendationClient.class);
        var catalog = mock(RecommendationCatalog.class);
        var preferences = new RecommendationPreferences("Fantasy", List.of("friendship"), List.of());
        var book = book("/works/OL2W", "A match", "An author");
        when(ai.available()).thenReturn(true);
        when(ai.extract(anyString(), anyString())).thenReturn(preferences);
        when(catalog.candidates(any(), anyString(), isNull(), eq(false))).thenReturn(
                new RecommendationCatalog.CandidateBatch(List.of(book), List.of("Google Books"), ""));
        when(ai.rank(anyString(), any(), anyList())).thenAnswer(call -> call.getArgument(2));
        var service = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        var original = new RecommendationService.Request("A cosy story about friendship", "", null, false);
        var refresh = new RecommendationService.Request(original.prompt(), "", null, false, true, List.of());
        assertFalse(service.recommend("reader", original).cached());
        assertTrue(service.recommend("reader", original).cached());
        assertFalse(service.recommend("reader", refresh).cached());
        assertFalse(service.recommend("reader", new RecommendationService.Request("A different mystery request", "", null, false)).cached());
        assertThrows(ResponseStatusException.class, () -> service.recommend("reader", refresh), "Refresh must not bypass the three-per-minute allowance");
        verify(ai, times(3)).extract(anyString(), anyString());
    }

    @Test
    void exclusionListsArePartOfThePrivateCacheKeyAndAreValidated() {
        var ai = mock(GroqRecommendationClient.class);
        var catalog = mock(RecommendationCatalog.class);
        var preferences = new RecommendationPreferences("Fantasy", List.of("magic"), List.of());
        var first = book("/works/OL1W", "First", "An author");
        var second = book("googlebooks:second", "Second", "Another author");
        when(ai.available()).thenReturn(true);
        when(ai.extract(anyString(), anyString())).thenReturn(preferences);
        when(catalog.candidates(any(), anyString(), isNull(), eq(false))).thenReturn(
                new RecommendationCatalog.CandidateBatch(List.of(first, second), List.of("Open Library", "Google Books"), ""));
        when(ai.rank(anyString(), any(), anyList())).thenAnswer(call -> call.getArgument(2));
        var service = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        var excluding = new RecommendationService.Request("A magical reading request", "", null, false, false, List.of(first.id()));
        assertEquals(List.of(second), service.recommend("reader", excluding).books());
        assertEquals(List.of("Google Books"), service.recommend("reader", excluding).sources(),
                "Only the selected book contributes to sources; checked providers are separate diagnostics");
        assertTrue(service.recommend("reader", excluding).cached());
        var ordinary = new RecommendationService.Request(excluding.prompt(), "", null, false);
        assertEquals(List.of(first, second), service.recommend("reader", ordinary).books());
        assertThrows(ResponseStatusException.class, () -> service.recommend("reader", new RecommendationService.Request(
                ordinary.prompt(), "", null, false, true, List.of("https://example.test/book"))));
        assertThrows(ResponseStatusException.class, () -> service.recommend("reader", new RecommendationService.Request(
                ordinary.prompt(), "", null, false, true, java.util.Collections.nCopies(13, first.id()))));
        verify(ai, times(2)).extract(anyString(), anyString());
    }

    @Test
    void verifiedReferenceIsRefinedAndExcludedAcrossProviderIdsBeforeRanking() {
        var ai = mock(GroqRecommendationClient.class);
        var catalog = mock(RecommendationCatalog.class);
        var extracted = new RecommendationPreferences("Fantasy", List.of(), List.of(), "Fourth Wing", List.of());
        var refined = new RecommendationPreferences("Fantasy", List.of("dragons"), List.of(), "Fourth Wing", List.of("dragons", "military academy"));
        var reference = book("/works/OL1W", "Fourth Wing", "Rebecca Yarros");
        var duplicate = book("googlebooks:duplicate", "Fourth Wing: illustrated edition", "A different edition credit");
        var excluded = book("/works/OL2W", "Seen already", "Another author");
        var fresh = book("/works/OL3W", "A fresh match", "A third author");
        var referenceLookup = new ProviderOutcome("Google Books", "ok", "Reference found.", 1, 1);
        var searchLookup = new ProviderOutcome("Open Library", "ok", "Candidates found.", 4, 4);
        when(ai.available()).thenReturn(true);
        when(ai.extract(anyString(), anyString())).thenReturn(extracted);
        when(catalog.resolveReference("Fourth Wing")).thenReturn(new RecommendationCatalog.ReferenceResult(reference, List.of(referenceLookup)));
        when(ai.referencePreferences(anyString(), eq(extracted), eq(reference))).thenReturn(refined);
        when(catalog.candidates(refined, "", null, false)).thenReturn(new RecommendationCatalog.CandidateBatch(
                List.of(reference, duplicate, excluded, fresh), List.of("Open Library"), "", List.of(searchLookup), null));
        when(ai.rank(anyString(), eq(refined), anyList())).thenAnswer(call -> call.getArgument(2));
        var service = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        var result = service.recommend("reader", new RecommendationService.Request("Something like Fourth Wing please", "", null, false,
                true, List.of(excluded.id())));
        assertEquals(List.of(fresh), result.books());
        assertEquals(reference, result.referenceBook());
        assertEquals(referenceLookup, result.providers().getFirst());
        assertEquals(searchLookup, result.providers().get(1));
        verify(ai).rank(anyString(), eq(refined), eq(List.of(fresh)));

        when(catalog.resolveReference("Fourth Wing")).thenReturn(new RecommendationCatalog.ReferenceResult(null, List.of()));
        when(catalog.candidates(extracted, "", null, false)).thenReturn(new RecommendationCatalog.CandidateBatch(
                List.of(duplicate, fresh), List.of("Google Books", "Open Library"), ""));
        when(ai.rank(anyString(), eq(extracted), anyList())).thenAnswer(call -> call.getArgument(2));
        var unresolvedService = new RecommendationService(ai, catalog, new RecommendationRateLimiter());
        var unresolved = unresolvedService.recommend("another-reader", new RecommendationService.Request("Something like Fourth Wing please", "", null, false));
        assertNull(unresolved.referenceBook());
        assertEquals(List.of(fresh), unresolved.books(), "Even a failed reference lookup must not recommend the anchor title itself");
        assertEquals(List.of("Open Library"), unresolved.sources(), "Only the selected book contributes to sources; checked providers are separate diagnostics");
    }

    private static BookPreview book(String id, String title, String author) {
        return new BookPreview(id, title, author, "Fantasy", null, null, null,
                Year.now(ZoneOffset.UTC).getValue(), "A real source description",
                id.startsWith("googlebooks:") ? "googlebooks" : "openlibrary", List.of(), null);
    }
}

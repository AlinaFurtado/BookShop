package com.bookshelf.myBooks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationRateLimiterTests {
    @Test
    void onlyOneSearchRunsAtATimeAndClosingReleasesItsSlot() {
        var limiter = new RecommendationRateLimiter();
        try (var first = limiter.enter("alice")) {
            assertThrows(ResponseStatusException.class, () -> limiter.enter("bob"));
        }
        assertDoesNotThrow(() -> { try (var second = limiter.enter("bob")) { } });
    }

    @Test
    void userLimitsResetByMinuteAndUtcDay() {
        var clock = new MutableClock();
        var limiter = new RecommendationRateLimiter(clock);
        for (int index = 0; index < 3; index++) try (var ticket = limiter.enter("alice")) { }
        assertTrue(assertThrows(ResponseStatusException.class, () -> limiter.enter("alice")).getReason().contains("minute"));
        for (int index = 3; index < 10; index++) {
            clock.advance(Duration.ofMinutes(1));
            try (var ticket = limiter.enter("alice")) { }
        }
        assertTrue(assertThrows(ResponseStatusException.class, () -> limiter.enter("alice")).getReason().contains("today's 10"));
        clock.advance(Duration.ofDays(1));
        assertDoesNotThrow(() -> { try (var ticket = limiter.enter("alice")) { } });
    }

    @Test
    void globalDailyBudgetAndProviderCooldownStopNewWork() {
        var clock = new MutableClock();
        var limiter = new RecommendationRateLimiter(clock);
        limiter.pause(Duration.ofSeconds(120));
        assertThrows(ResponseStatusException.class, () -> limiter.enter("reader"));
        clock.advance(Duration.ofSeconds(121));
        for (int index = 0; index < 40; index++) try (var ticket = limiter.enter("reader-" + index)) { }
        assertTrue(assertThrows(ResponseStatusException.class, () -> limiter.enter("new-reader")).getReason().contains("shared AI allowance"));
        clock.advance(Duration.ofDays(1));
        assertDoesNotThrow(() -> { try (var ticket = limiter.enter("new-reader")) { } });
    }

    private static final class MutableClock extends Clock {
        private Instant value = Instant.parse("2026-10-27T12:00:00Z");
        void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}

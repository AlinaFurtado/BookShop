package com.bookshelf.myBooks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Small free-tier budget. Counters are in memory and reset on restart or a new UTC day. */
@Component
public class RecommendationRateLimiter {
    static final int GLOBAL_DAILY = 40;
    static final int USER_DAILY = 10;
    static final int USER_MINUTE = 3;
    private final Clock clock;
    private final Semaphore inFlight = new Semaphore(1);
    private final Map<String, Usage> readers = new HashMap<>();
    private LocalDate day;
    private int total;
    private Instant pausedUntil = Instant.MIN;

    private static final class Usage {
        int daily;
        final ArrayDeque<Instant> recent = new ArrayDeque<>();
    }

    public RecommendationRateLimiter() { this(Clock.systemUTC()); }
    RecommendationRateLimiter(Clock clock) { this.clock = clock; }

    public synchronized Ticket enter(String reader) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (!today.equals(day)) { day = today; total = 0; readers.clear(); }
        if (now.isBefore(pausedUntil)) reject("The AI provider is taking a short pause. Please try again later.");
        if (total >= GLOBAL_DAILY) reject("Today's shared AI allowance has been used. Try again tomorrow; regular book search is still available.");
        Usage usage = readers.get(reader);
        if (usage == null) usage = new Usage();
        if (usage.daily >= USER_DAILY) reject("You have used today's 10 AI searches. Try again tomorrow; regular book search is still available.");
        while (!usage.recent.isEmpty() && !usage.recent.peekFirst().isAfter(now.minusSeconds(60))) usage.recent.removeFirst();
        if (usage.recent.size() >= USER_MINUTE) reject("Please wait a minute before another AI search.");
        if (!inFlight.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Pip is helping another reader. Please try again in a moment.");
        total++;
        readers.put(reader, usage);
        usage.daily++;
        usage.recent.addLast(now);
        return new Ticket();
    }

    public synchronized void pause(Duration duration) {
        long seconds = Math.max(30, Math.min(3600, duration.toSeconds()));
        Instant until = clock.instant().plusSeconds(seconds);
        if (until.isAfter(pausedUntil)) pausedUntil = until;
    }

    private static void reject(String reason) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason); }

    public final class Ticket implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            if (!closed) { closed = true; inFlight.release(); }
        }
    }
}

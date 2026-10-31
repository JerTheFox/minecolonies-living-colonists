package me.bytefox.livingcolonists.llm;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A circuit breaker per provider (#40): after a few requests in a row failed even with retries,
 * background work pauses for a while, so it stops spending retries on a provider that is down;
 * players' answers keep trying, and the first success closes it again. Any thread.
 */
final class Breaker {
    static final int FAILURES = 3;
    static final long PAUSE_MILLIS = 3 * 60 * 1000;

    private final LongSupplier clock;
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> openUntil = new HashMap<>();

    Breaker(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized boolean open(String provider) {
        return clock.getAsLong() < openUntil.getOrDefault(provider, Long.MIN_VALUE);
    }

    synchronized void failed(String provider) {
        int count = failures.merge(provider, 1, Integer::sum);
        if (count >= FAILURES) {
            openUntil.put(provider, clock.getAsLong() + PAUSE_MILLIS);
            failures.remove(provider);
        }
    }

    synchronized void succeeded(String provider) {
        failures.remove(provider);
        openUntil.remove(provider);
    }
}

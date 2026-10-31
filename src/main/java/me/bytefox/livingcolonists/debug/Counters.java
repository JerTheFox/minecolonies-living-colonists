package me.bytefox.livingcolonists.debug;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts of what the mod did since the server started, for {@code /lcdebug stats} (#40): lines said
 * and held back by kind, events that went stale, repeats caught, orders turned down, model requests
 * shed. Not saved. Any thread.
 */
public final class Counters {
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private Counters() {
    }

    public static void add(String key) {
        COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /** Every count so far, sorted by key. */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        COUNTS.forEach((key, count) -> out.put(key, count.get()));
        return out;
    }

    public static void clear() {
        COUNTS.clear();
    }
}

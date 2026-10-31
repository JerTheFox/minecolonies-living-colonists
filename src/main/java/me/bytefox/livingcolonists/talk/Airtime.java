package me.bytefox.livingcolonists.talk;

import java.util.HashMap;
import java.util.Map;

import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.debug.Counters;

/**
 * One budget for every line colonists say unprompted (#40): a token bucket per colony chat and per
 * player's private talks, refilled over time. Each line takes one token; lower kinds need more left
 * over, so small talk gives way first and a death is never held back. Answers to a player don't ask.
 * Server thread only.
 */
public final class Airtime {
    /** Lines a bucket holds when full. */
    static final double CAPACITY = 6;

    /** What a line is, from the most to the least important: how much must be left over for it. */
    public enum Kind {
        URGENT(Double.NEGATIVE_INFINITY),
        NOTE(0),
        NEWS(0.5),
        GREETING(1.5),
        FIRST_LINE(2),
        CHATTER(2.5);

        final double reserve;

        Kind(double reserve) {
            this.reserve = reserve;
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final class Bucket {
        double tokens = CAPACITY;
        long at;
    }

    private final Map<String, Bucket> buckets = new HashMap<>();

    /** Ticks for one token to come back: a line every four minutes by default, half as often on low. */
    static long refillTicks(Level level) {
        return switch (level) {
            case LOW -> 8 * 60 * 20;
            case HIGH -> 2 * 60 * 20;
            default -> 4 * 60 * 20;
        };
    }

    /** Whether a line of this kind may go out now; counts it as held back if not. */
    boolean allows(Kind kind, String bucketKey, long now, Level level) {
        Bucket bucket = refill(bucketKey, now, level);
        boolean ok = bucket.tokens - 1 >= kind.reserve;
        if (!ok) {
            Counters.add("held." + kind.key());
        }
        return ok;
    }

    /** A line went out: one token less. */
    void spend(String bucketKey, long now) {
        spend(bucketKey, now, 1);
    }

    /** A part of a token, for what costs less than a line of its own (each line of a long talk). */
    void spend(String bucketKey, long now, double amount) {
        Bucket bucket = buckets.computeIfAbsent(bucketKey, key -> {
            Bucket fresh = new Bucket();
            fresh.at = now;
            return fresh;
        });
        bucket.tokens = Math.max(0, bucket.tokens - amount);
    }

    void clear() {
        buckets.clear();
    }

    /** Drops buckets that are full again: they hold nothing a fresh one wouldn't. */
    void sweep(long now, Level level) {
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            return bucket.tokens + (double) (now - bucket.at) / refillTicks(level) >= CAPACITY;
        });
    }

    static String colony(int colonyId) {
        return "c:" + colonyId;
    }

    static String player(java.util.UUID player) {
        return "p:" + player;
    }

    private Bucket refill(String key, long now, Level level) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            Bucket fresh = new Bucket();
            fresh.at = now;
            return fresh;
        });
        if (now > bucket.at) {
            bucket.tokens = Math.min(CAPACITY, bucket.tokens + (double) (now - bucket.at) / refillTicks(level));
            bucket.at = now;
        }
        return bucket;
    }
}

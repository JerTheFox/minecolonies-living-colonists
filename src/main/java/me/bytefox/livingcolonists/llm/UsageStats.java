package me.bytefox.livingcolonists.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * Requests and tokens per model, for this run and in total. Recorded from HTTP threads, read on
 * the server thread, so every method is synchronized.
 */
public final class UsageStats {
    public record Counts(long requests, long failures, long promptTokens, long completionTokens, long cachedTokens,
                         long latencyMillis) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0, 0, 0);

        Counts plus(Counts other) {
            return new Counts(requests + other.requests, failures + other.failures, promptTokens + other.promptTokens,
                    completionTokens + other.completionTokens, cachedTokens + other.cachedTokens,
                    latencyMillis + other.latencyMillis);
        }

        public long averageLatencyMillis() {
            return requests == 0 ? 0 : latencyMillis / requests;
        }
    }

    private final Map<String, Counts> session = new HashMap<>();
    private Map<String, Counts> saved = Map.of();
    private boolean dirty;

    public synchronized void record(String model, ChatResult result, long latencyMillis) {
        Counts one = switch (result) {
            case ChatResult.Reply reply -> new Counts(1, 0, reply.usage().promptTokens(),
                    reply.usage().completionTokens(), reply.usage().cachedTokens(), latencyMillis);
            case ChatResult.Failure failure -> new Counts(1, 1, 0, 0, 0, latencyMillis);
        };
        session.merge(model, one, Counts::plus);
        dirty = true;
    }

    /** Totals from earlier runs, loaded with the world. */
    public synchronized void restore(Map<String, Counts> totals) {
        saved = Map.copyOf(totals);
    }

    public synchronized void clearSession() {
        session.clear();
        saved = Map.of();
        dirty = false;
    }

    public synchronized Map<String, Counts> session() {
        return Map.copyOf(session);
    }

    /** Earlier runs plus this one: what gets saved with the world. */
    public synchronized Map<String, Counts> total() {
        Map<String, Counts> total = new HashMap<>(saved);
        session.forEach((model, counts) -> total.merge(model, counts, Counts::plus));
        return Map.copyOf(total);
    }

    /** Whether anything was recorded since the last call. */
    public synchronized boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }
}

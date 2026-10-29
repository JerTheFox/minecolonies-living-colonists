package me.bytefox.livingcolonists.behaviour;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** How many messages to colonists each player may send in an hour of server time. Server thread only. */
public final class HourlyLimit {
    /** One hour in ticks. */
    private static final long WINDOW_TICKS = 72000;

    private final Map<UUID, Deque<Long>> sent = new HashMap<>();

    /**
     * @param limit messages per hour; 0 means no limit
     * @return empty if the player may send now (and the message is counted), otherwise the ticks to wait
     */
    public Optional<Long> tryUse(UUID player, long now, int limit) {
        if (limit <= 0) {
            return Optional.empty();
        }
        Deque<Long> times = sent.computeIfAbsent(player, id -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_TICKS) {
            times.removeFirst();
        }
        if (times.size() >= limit) {
            return Optional.of(WINDOW_TICKS - (now - times.peekFirst()));
        }
        times.addLast(now);
        return Optional.empty();
    }

    public void clear() {
        sent.clear();
    }
}

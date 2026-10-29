package me.bytefox.livingcolonists.errands;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orders waiting for a player's yes, the last order each player may undo, and a log of what was
 * ordered. Each player has their own: one player's "yes" never confirms another's order. Pure; ticks
 * come from the caller.
 *
 * @param <T> the order
 */
public final class OrderBook<T> {
    /** How long an order waits for a yes: one minute. */
    public static final long WAIT_TICKS = 60 * 20;
    private static final int LOG_SIZE = 50;

    public enum Answer { YES, NO, NONE }

    public record Pending<T>(T order, long expires) {
    }

    public record Undo<T>(T order, Runnable undo) {
    }

    private static final Set<String> YES = Set.of("да", "ага", "угу", "давай", "конечно", "ок", "окей", "хорошо",
            "делай", "действуй", "подтверждаю", "yes", "yeah", "yep", "sure", "ok", "okay", "confirm");
    private static final Set<String> NO = Set.of("нет", "неа", "отмена", "отменить", "стоп", "отбой", "no",
            "nope", "cancel", "stop", "don't", "dont");

    private final Map<UUID, Pending<T>> pending = new HashMap<>();
    private final Map<UUID, Undo<T>> undo = new HashMap<>();
    private final Deque<String> log = new ArrayDeque<>();

    /** A short reply to a confirmation question: its first word decides, longer messages are talk. */
    public static Answer answer(String message) {
        String[] words = message.toLowerCase(Locale.ROOT).strip().split("[^\\p{L}']+");
        if (words.length == 0 || words.length > 4 || words[0].isEmpty()) {
            return Answer.NONE;
        }
        if (YES.contains(words[0])) {
            return Answer.YES;
        }
        boolean notNeeded = words[0].equals("не") && words.length > 1 && Set.of("надо", "нужно", "делай").contains(words[1]);
        return NO.contains(words[0]) || notNeeded ? Answer.NO : Answer.NONE;
    }

    /** Puts an order up for the player's yes, replacing one they haven't answered. */
    public void propose(UUID player, T order, long now) {
        pending.put(player, new Pending<>(order, now + WAIT_TICKS));
    }

    /** The order waiting for this player's answer, if it hasn't expired. */
    public Optional<T> pending(UUID player, long now) {
        Pending<T> waiting = pending.get(player);
        if (waiting == null) {
            return Optional.empty();
        }
        if (waiting.expires() < now) {
            pending.remove(player);
            return Optional.empty();
        }
        return Optional.of(waiting.order());
    }

    /** Takes the waiting order off the book, to carry it out or drop it. */
    public Optional<T> take(UUID player, long now) {
        Optional<T> order = pending(player, now);
        pending.remove(player);
        return order;
    }

    /** Notes a carried out order; with {@code undo}, it becomes the one the player can take back. */
    public void done(UUID player, T order, Runnable undo) {
        if (undo != null) {
            this.undo.put(player, new Undo<>(order, undo));
        }
    }

    public Optional<Undo<T>> takeUndo(UUID player) {
        return Optional.ofNullable(undo.remove(player));
    }

    public void log(String line) {
        log.addLast(line);
        while (log.size() > LOG_SIZE) {
            log.removeFirst();
        }
    }

    /** The log, newest last. */
    public List<String> log() {
        return new ArrayList<>(log);
    }

    public void clear() {
        pending.clear();
        undo.clear();
        log.clear();
    }
}

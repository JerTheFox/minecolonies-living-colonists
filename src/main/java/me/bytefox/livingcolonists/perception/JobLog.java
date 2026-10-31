package me.bytefox.livingcolonists.perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Which job each colonist had when last seen, so a job MineColonies gave or took a moment ago is told
 * plainly: a talk about who should be the cook outweighs a quiet line saying you are one. Not saved.
 */
final class JobLog {
    /** How long a change counts as new, in game ticks (half a game day). */
    static final long RECENT = 12000;

    /** A job change: either side null for no job. */
    record Change(String from, String to, long at) {
    }

    private final Map<String, String> seen = new HashMap<>();
    private final Map<String, Change> changes = new HashMap<>();

    void note(String key, String job, long gameTime) {
        boolean known = seen.containsKey(key);
        String was = seen.put(key, job);
        if (known && !Objects.equals(was, job)) {
            changes.put(key, new Change(was, job, gameTime));
        }
    }

    /** The colonist's latest job change if it is still new, else null. */
    Change recent(String key, long gameTime) {
        Change change = changes.get(key);
        return change == null || gameTime - change.at() > RECENT ? null : change;
    }

    void forget(Predicate<String> key) {
        seen.keySet().removeIf(key);
        changes.keySet().removeIf(key);
    }

    void clear() {
        seen.clear();
        changes.clear();
    }
}

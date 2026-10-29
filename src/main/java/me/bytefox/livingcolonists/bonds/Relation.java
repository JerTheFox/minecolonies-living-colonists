/*
 * Based on RelationStore.Relation from Colonist Errands by Lovkar, licensed GPL-3.0-or-later
 * (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/RelationStore.java). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.List;

/**
 * How one player treats one colonist: a rapport score and the last few moments behind it.
 *
 * @param rapport -100 (hostile) to 100 (warm)
 */
public record Relation(int rapport, List<Note> notes) {
    public static final int MAX_NOTES = 4;
    public static final Relation EMPTY = new Relation(0, List.of());

    /** A moment the colonist remembers, kind or not. */
    public record Note(long time, String text, boolean kind) {
    }

    public Relation {
        notes = List.copyOf(notes);
    }

    /** After a moment that changes the rapport by {@code delta}. */
    public Relation with(int delta, String note, long time) {
        List<Note> next = new ArrayList<>(notes);
        next.add(new Note(time, note, delta > 0));
        while (next.size() > MAX_NOTES) {
            next.remove(0);
        }
        return new Relation(Math.max(-100, Math.min(100, rapport + delta)), next);
    }

    public boolean isEmpty() {
        return rapport == 0 && notes.isEmpty();
    }
}

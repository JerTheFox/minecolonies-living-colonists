package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Messages colonists pass on and reminders they give, kept until delivered. Pure; times are world
 * game time in ticks, which keeps running across restarts.
 */
public final class NoteBook {
    public enum Kind { MAIL, REMINDER }

    /**
     * @param to   who gets it: the addressee of a message, the asker of a reminder
     * @param from who asked for it
     * @param due  when it may go out: at once for a message, at the set time for a reminder
     */
    public record Note(Kind kind, int colonyId, int citizenId, UUID from, String fromName, UUID to, String toName,
                       String text, long made, long due) {
    }

    /** Most notes one player may have waiting, of each kind. */
    public static final int MAX_PER_PLAYER = 5;
    /** A message nobody came for in a week of game days is dropped. */
    public static final long MAIL_KEEP = 7 * 24000L;
    /** A reminder its player missed by more than a game day is dropped. */
    public static final long REMINDER_GRACE = 24000L;
    /** The longest a reminder may be set ahead: a real-time day. */
    public static final long MAX_DELAY = 24 * 60 * 60 * 20L;

    private final List<Note> notes = new ArrayList<>();

    /** @return false when the player already has too many of this kind waiting */
    public boolean add(Note note) {
        long waiting = notes.stream().filter(n -> n.kind() == note.kind() && n.from().equals(note.from())).count();
        if (waiting >= MAX_PER_PLAYER) {
            return false;
        }
        notes.add(note);
        return true;
    }

    /** Notes that may go out now, oldest first; expired ones are dropped on the way. */
    public List<Note> due(long now) {
        notes.removeIf(note -> note.kind() == Kind.MAIL ? now - note.made() > MAIL_KEEP : now - note.due() > REMINDER_GRACE);
        return notes.stream().filter(note -> note.due() <= now).toList();
    }

    public void delivered(Note note) {
        notes.remove(note);
    }

    public void removeIf(Predicate<Note> which) {
        notes.removeIf(which);
    }

    public List<Note> all() {
        return List.copyOf(notes);
    }

    public void clear() {
        notes.clear();
    }

    /** How many ticks "in N minutes" is, kept between a minute and a day. */
    public static long delay(double minutes) {
        long ticks = Math.round(minutes * 60 * 20);
        return Math.max(60 * 20, Math.min(MAX_DELAY, ticks));
    }
}

package me.bytefox.livingcolonists.memory;

import java.util.Collection;
import java.util.UUID;

/**
 * Something a colonist remembers from a conversation, written down by the character model.
 *
 * @param importance 1 (trivia) to 5 (life-changing); decides how long it is kept
 * @param created    world game time when it was written down
 * @param expires    world game time after which it is forgotten
 * @param source     where it was heard: {@link #COLONY_CHAT} or {@link #privateTalk}
 * @param told       already passed on as gossip, so it isn't retold
 * @param about      whom it is mainly about: {@code c:<citizen id>}, {@code p:<player uuid>}, {@code colony}
 *                   for colony news, or empty when the model didn't say
 */
public record Fact(String text, int importance, long created, long expires, String source, boolean told, String about) {
    public static final String COLONY_CHAT = "colony";
    public static final String ABOUT_COLONY = "colony";
    private static final String PRIVATE = "private:";

    /** A fact about nobody in particular. */
    public Fact(String text, int importance, long created, long expires, String source, boolean told) {
        this(text, importance, created, expires, source, told, "");
    }

    /** The source of a fact heard from this player in a private talk. */
    public static String privateTalk(UUID player) {
        return PRIVATE + player;
    }

    /** The same by name, as facts were kept before players were known by their UUID. */
    public static String privateTalk(String player) {
        return PRIVATE + player;
    }

    public static String aboutCitizen(int citizenId) {
        return "c:" + citizenId;
    }

    public static String aboutPlayer(UUID player) {
        return "p:" + player;
    }

    public boolean isPrivate() {
        return source.startsWith(PRIVATE);
    }

    /** The player who told it in private, as kept: a UUID, or a name in older facts; empty for the colony chat. */
    public String teller() {
        return isPrivate() ? source.substring(PRIVATE.length()) : "";
    }

    /** The UUID of the player who told it in private, if the fact keeps one. */
    public UUID tellerId() {
        try {
            return isPrivate() ? UUID.fromString(teller()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether this player told it in private: by UUID, or in older facts by one of their names. */
    public boolean toldBy(UUID player, Collection<String> names) {
        UUID id = tellerId();
        return id != null ? id.equals(player) : isPrivate() && names.contains(teller());
    }

    Fact asTold() {
        return new Fact(text, importance, created, expires, source, true, about);
    }
}

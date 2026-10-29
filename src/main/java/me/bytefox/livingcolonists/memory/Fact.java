package me.bytefox.livingcolonists.memory;

/**
 * Something a colonist remembers from a conversation, written down by the character model.
 *
 * @param importance 1 (trivia) to 5 (life-changing); decides how long it is kept
 * @param created    world game time when it was written down
 * @param expires    world game time after which it is forgotten
 * @param source     where it was heard: {@link #COLONY_CHAT} or {@link #privateTalk}
 * @param told       already passed on as gossip, so it isn't retold
 */
public record Fact(String text, int importance, long created, long expires, String source, boolean told) {
    public static final String COLONY_CHAT = "colony";
    private static final String PRIVATE = "private:";

    /** The source of a fact heard from this player in a private talk. */
    public static String privateTalk(String player) {
        return PRIVATE + player;
    }

    public boolean isPrivate() {
        return source.startsWith(PRIVATE);
    }

    /** The player who told it in private; empty for the colony chat. */
    public String teller() {
        return isPrivate() ? source.substring(PRIVATE.length()) : "";
    }

    Fact asTold() {
        return new Fact(text, importance, created, expires, source, true);
    }
}

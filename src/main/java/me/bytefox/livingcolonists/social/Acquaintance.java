package me.bytefox.livingcolonists.social;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.friendship.FriendshipLevels;

/**
 * What the talk menu offers as a friendship grows (#68), as in Stardew Valley: friends tell about themselves, good
 * friends share the colony's news, and the menu shows how far the friendship has come in hearts.
 */
public final class Acquaintance {
    /** The menu for everyone. */
    public static final List<String> BASE = List.of("how", "work", "likes", "birthday", "compliment");
    /** Hearts shown: from thinking well of the player (1) to best friend (5). */
    public static final int MAX_HEARTS = 5;
    /** How many origins, pastimes and dreams the language files have (livingcolonists.about.<part>.<n>). */
    public static final int ORIGINS = 12;
    public static final int PASTIMES = 12;
    public static final int DREAMS = 12;

    private Acquaintance() {
    }

    /** What the player can say to a colonist who feels {@code rapport} about them, "bye" last. */
    public static List<String> options(int rapport) {
        List<String> out = new ArrayList<>(BASE);
        int rank = FriendshipLevels.level(rapport).rank();
        if (rank >= rank("friend")) {
            out.add("about");
        }
        if (rank >= rank("good")) {
            out.add("news");
        }
        out.add("bye");
        return out;
    }

    /** Hearts for the menu's header: 0 below "thinks well of you". */
    public static int hearts(int rapport) {
        return Math.max(0, Math.min(MAX_HEARTS, FriendshipLevels.level(rapport).rank() - rank("neutral")));
    }

    /** The colonist's own story, the same each time: which origin, pastime and dream, from 1, by their key. */
    public static int[] story(String citizenKey) {
        int hash = citizenKey.hashCode();
        return new int[] {1 + Math.floorMod(hash, ORIGINS), 1 + Math.floorMod(hash / 7, PASTIMES), 1 + Math.floorMod(hash / 49, DREAMS)};
    }

    private static int rank(String id) {
        return FriendshipLevels.byId(id).map(FriendshipLevels.Level::rank).orElse(Integer.MAX_VALUE);
    }
}

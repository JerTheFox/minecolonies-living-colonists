package me.bytefox.livingcolonists.bonds;

import java.util.HashMap;
import java.util.Map;

/**
 * Gifts a player puts into a colonist's inventory through MineColonies' inventory window (#11): what
 * came in between opening and closing it, and how much it warms the colonist.
 */
public final class Gifts {
    /** One gift warms a colonist toward a player at most this often, in game ticks (a game day). */
    public static final long WARMS_EVERY = 24000;

    private Gifts() {
    }

    /** Item id to how many more there are now than before; only what grew. */
    public static Map<String, Integer> added(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> added = new HashMap<>();
        after.forEach((item, count) -> {
            int more = count - before.getOrDefault(item, 0);
            if (more > 0) {
                added.put(item, more);
            }
        });
        return added;
    }

    /** How much a gift of this worth (1 a trifle, 5 something precious) warms the colonist. */
    public static int warmth(int worth) {
        return Math.min(Bonds.KEPT, 1 + worth);
    }
}

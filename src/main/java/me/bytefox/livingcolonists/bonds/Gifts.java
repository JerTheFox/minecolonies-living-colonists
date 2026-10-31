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

    /**
     * Whether a gift is what a promise was about: one of the gift's item names (in English, as the
     * promise is written) shares a word of five letters or more with the promise ("an emerald", "planks").
     */
    public static boolean fulfils(String promise, java.util.List<String> items) {
        String text = promise.toLowerCase(java.util.Locale.ROOT);
        for (String item : items) {
            for (String word : item.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+")) {
                String stem = word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
                if (stem.length() >= 5 && text.contains(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How much a gift of this worth (1 a trifle, 5 something precious) warms the colonist. */
    public static int warmth(int worth) {
        return Math.min(Bonds.KEPT, 1 + worth);
    }
}

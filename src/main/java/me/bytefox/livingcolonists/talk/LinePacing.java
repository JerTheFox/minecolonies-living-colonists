package me.bytefox.livingcolonists.talk;

import java.util.Random;

/**
 * Paces colonists' lines like people typing: no answer sooner than a moment after the player's
 * message, and several answers in one colony a moment apart instead of one wall of text.
 */
public final class LinePacing {
    /** Shortest pause: 2 seconds. */
    public static final long MIN_GAP_TICKS = 40;
    /** Longest pause: 4 seconds. */
    public static final long MAX_GAP_TICKS = 80;

    private LinePacing() {
    }

    /**
     * When a line that is ready at {@code now} may go out.
     *
     * @param earliest     the player's message tick plus the colonist's reading-and-typing pause
     * @param colonyFreeAt when the previous colony-wide line's pause ends
     */
    public static long slot(long now, long earliest, long colonyFreeAt) {
        return Math.max(now, Math.max(earliest, colonyFreeAt));
    }

    /** A random pause: before a reply, or between two lines in the same colony. */
    public static long gap(Random random) {
        return MIN_GAP_TICKS + random.nextInt((int) (MAX_GAP_TICKS - MIN_GAP_TICKS) + 1);
    }
}

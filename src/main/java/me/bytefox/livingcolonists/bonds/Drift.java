package me.bytefox.livingcolonists.bonds;

import java.util.Optional;

/**
 * Relations between colonists that change by themselves from real life in the colony (#59), not from talks
 * the model makes up: sharing a workplace or a home moves a pair closer or apart over the days, as their chemistry
 * and characters go, so not every pair ends up friends. Crossing a threshold is a fact both remember,
 * with its real cause. No requests to the model.
 */
public final class Drift {
    /** Rapport at which two colonists count as friends, or (negative) as at odds. */
    public static final int FRIENDS = 40;
    /** Contrariness from which two housemates grate on each other rather than grow close. */
    public static final double CONTRARY = 0.55;
    /** How far rapport goes from living and working together alone, either way. */
    public static final int CAP = 60;

    public enum Turn {
        FRIENDS, AT_ODDS
    }

    private Drift() {
    }

    /**
     * How a game day changes two colonists' rapport. Sharing a workplace or a home moves a pair the way their
     * {@link #chemistry} goes, and faster when they share both; contrary characters lean towards falling out.
     * Neither way goes past {@link #CAP}: a closer bond than that takes real events, not just living side by side.
     */
    public static int daily(boolean coworkers, boolean housemates, boolean compatible, double chemistry, int rapport) {
        int shared = (coworkers ? 1 : 0) + (housemates ? 1 : 0);
        double tendency = chemistry + (compatible ? 0.3 : -0.5);
        if (shared == 0 || Math.abs(tendency) <= 0.25) {
            return 0;
        }
        return tendency > 0 ? Math.max(0, Math.min(shared, CAP - rapport)) : -Math.max(0, Math.min(shared, rapport + CAP));
    }

    /** How well two colonists click, -1 to 1, the same for the pair every time ({@code pair} names them). */
    public static double chemistry(String pair) {
        int h = pair.hashCode() * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) / (double) (1 << 24) * 2 - 1;
    }

    /** The threshold a change crossed, if any. */
    public static Optional<Turn> crossed(int before, int after) {
        if (before < FRIENDS && after >= FRIENDS) {
            return Optional.of(Turn.FRIENDS);
        }
        if (before > -FRIENDS && after <= -FRIENDS) {
            return Optional.of(Turn.AT_ODDS);
        }
        return Optional.empty();
    }
}

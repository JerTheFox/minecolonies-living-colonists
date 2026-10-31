package me.bytefox.livingcolonists.bonds;

import java.util.Optional;

/**
 * Relations between colonists that change by themselves from real life in the colony (#59), not from talks
 * the model makes up: working in the same building brings two colonists closer over the days; living under
 * one roof does too, unless both are contrary characters. Crossing a threshold is a fact both remember,
 * with its real cause. No requests to the model.
 */
public final class Drift {
    /** Rapport at which two colonists count as friends, or (negative) as at odds. */
    public static final int FRIENDS = 40;
    /** Contrariness from which two housemates grate on each other rather than grow close. */
    public static final double CONTRARY = 0.55;

    public enum Turn {
        FRIENDS, AT_ODDS
    }

    private Drift() {
    }

    /** How a game day changes two colonists' rapport. */
    public static int daily(boolean coworkers, boolean housemates, boolean compatible) {
        int change = coworkers ? 2 : 0;
        if (housemates) {
            change += compatible ? 1 : -2;
        }
        return change;
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

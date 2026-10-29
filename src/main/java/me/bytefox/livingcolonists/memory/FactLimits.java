package me.bytefox.livingcolonists.memory;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * How much a colonist remembers for long, by memory level.
 *
 * @param facts         facts kept at most
 * @param lifetimeTicks how long a fact of middle importance (3) is kept
 * @param rumorChance   share of lines in which a colonist passes on something heard in private
 */
public record FactLimits(int facts, long lifetimeTicks, double rumorChance) {
    private static final long DAY = 24000;

    public static FactLimits of(Level level) {
        return switch (level) {
            case OFF -> new FactLimits(0, 0, 0);
            case LOW -> new FactLimits(5, 2 * DAY, 0.1);
            case DEFAULT -> new FactLimits(15, 7 * DAY, 0.15);
            case HIGH -> new FactLimits(60, 30 * DAY, 0.25);
        };
    }

    /** How long a fact of this importance is kept: from a third (trivia) to five thirds (life-changing). */
    public long lifetime(int importance) {
        return lifetimeTicks * importance / 3;
    }
}

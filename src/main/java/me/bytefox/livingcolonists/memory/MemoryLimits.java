package me.bytefox.livingcolonists.memory;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * How much of a private conversation a colonist remembers, by memory level.
 *
 * @param lines       lines kept, both sides together
 * @param maxAgeTicks lines older than this are forgotten (one Minecraft day is 24000 ticks)
 */
public record MemoryLimits(int lines, long maxAgeTicks) {
    private static final long DAY = 24000;

    public static MemoryLimits of(Level level) {
        return switch (level) {
            case OFF -> new MemoryLimits(0, 0);
            case LOW -> new MemoryLimits(4, DAY);
            case DEFAULT -> new MemoryLimits(10, 3 * DAY);
            case HIGH -> new MemoryLimits(40, 10 * DAY);
        };
    }
}

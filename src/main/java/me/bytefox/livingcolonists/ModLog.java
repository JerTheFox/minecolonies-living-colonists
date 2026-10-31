package me.bytefox.livingcolonists;

import me.bytefox.livingcolonists.behaviour.LogLevel;

/** The mod's log lines, by kind, as much of them as {@code logLevel} lets through. Errors always go to {@link LivingColonists#LOGGER}. */
public final class ModLog {
    private ModLog() {
    }

    /** A line said in the colony chat or a private talk. */
    public static void chat(String format, Object... args) {
        if (level().chat()) {
            LivingColonists.LOGGER.info(format, args);
        }
    }

    /** What colonists did: a look-up, an order, a promise, a note, who a message was for. */
    public static void action(String format, Object... args) {
        if (level().actions()) {
            LivingColonists.LOGGER.info(format, args);
        }
    }

    /** Start-up and settings lines. */
    public static void info(String format, Object... args) {
        if (level().technical()) {
            LivingColonists.LOGGER.info(format, args);
        }
    }

    public static void warn(String format, Object... args) {
        if (level().technical()) {
            LivingColonists.LOGGER.warn(format, args);
        }
    }

    private static LogLevel level() {
        return Config.SPEC.isLoaded() ? Config.LOG_LEVEL.get() : LogLevel.ALL;
    }
}

package me.bytefox.livingcolonists.talk;

import java.util.Locale;
import java.util.Random;

/** Values for prompt templates and clean-up of model output, kept free of game classes for unit tests. */
public final class Prompts {
    /** Share of replies in which a colonist may bring up one of their own worries unasked. */
    public static final double INITIATIVE_CHANCE = 0.25;
    /** Longest background kept, in characters. */
    public static final int MAX_BIO_LENGTH = 1200;
    /** Longest reply that goes to chat, in characters. */
    public static final int MAX_REPLY_LENGTH = 400;

    private Prompts() {
    }

    /**
     * The profession from the job registry path ({@code stone_smelter} becomes
     * {@code stone smelter}); never from the citizen's AI state, which gives values like DECIDE.
     */
    public static String jobName(String registryPath) {
        if (registryPath == null || registryPath.isBlank()) {
            return "unemployed";
        }
        return registryPath.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /** Minecraft day time: 0 is 6 am, 6000 noon, 12000 6 pm, 18000 midnight. */
    public static String timeOfDay(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (t < 6000) {
            return "morning";
        }
        if (t < 12000) {
            return "afternoon";
        }
        if (t < 13000) {
            return "evening";
        }
        if (t < 23000) {
            return "night";
        }
        return "dawn";
    }

    /**
     * Strips what models add around a chat line: a name prefix, wrapping quotes, line breaks, and
     * Minecraft formatting codes, which could hide or garble text in chat.
     */
    public static String cleanReply(String text, String name) {
        String line = text.replaceAll("§.?", "").strip().replaceAll("\\s*\\R+\\s*", " ");
        String prefix = name + ":";
        if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
            line = line.substring(prefix.length()).strip();
        }
        if (line.length() >= 2 && isQuotePair(line.charAt(0), line.charAt(line.length() - 1))) {
            line = line.substring(1, line.length() - 1).strip();
        }
        if (line.length() > MAX_REPLY_LENGTH) {
            line = line.substring(0, MAX_REPLY_LENGTH - 1) + "…";
        }
        return line;
    }

    private static boolean isQuotePair(char first, char last) {
        return (first == '"' && last == '"') || (first == '«' && last == '»') || (first == '“' && last == '”');
    }

    /**
     * Whether this reply may bring up a worry of the colonist's own. Told "only sometimes", models do
     * it every time, so the code decides per reply.
     */
    public static String initiative(Random random) {
        return random.nextDouble() < INITIATIVE_CHANCE
                ? "Answer what the player said. If it fits, you may add one thing that bothers you, in passing."
                : "Answer only what the player said. Don't bring up your work, materials or needs unless the player asks about them.";
    }

    /** A background as one paragraph of plain text, without formatting codes, within a sane length. */
    public static String cleanBio(String text) {
        String bio = text.replaceAll("§.?", "").replaceAll("\\s+", " ").strip();
        return bio.length() > MAX_BIO_LENGTH ? bio.substring(0, MAX_BIO_LENGTH - 1) + "…" : bio;
    }
}

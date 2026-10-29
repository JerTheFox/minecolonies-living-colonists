package me.bytefox.livingcolonists.talk;

import java.util.Locale;

/** Prompt text and reply clean-up, kept free of game classes so it can be unit-tested. */
public final class Prompts {
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
     * @param wholeColony the player wrote to the whole colony rather than to this colonist alone
     */
    public static String system(String name, String job, String colony, String timeOfDay, String playerName,
                                boolean wholeColony) {
        String situation = wholeColony
                ? "%s, a player, wrote a message to the whole colony in the game chat, and you answer it.".formatted(playerName)
                : "%s, a player, is talking to you privately in the game chat.".formatted(playerName);
        return """
                You are %s, a %s living in the colony "%s" in Minecraft with the MineColonies mod. \
                It is %s. %s
                Stay in character as a colonist with your own temper and worries. \
                Reply like a chat message: one or two short sentences, in the same language the player writes in. \
                No stage directions, no quotation marks, no name in front of the reply. \
                If you don't know something about the colony, say so instead of making it up."""
                .formatted(name, job, colony, timeOfDay, situation);
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
}

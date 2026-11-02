package me.bytefox.livingcolonists.social;

import java.util.Map;

/**
 * How a colonist sounds in set lines (#68): eleven characters come down to four voices, so a grumbler doesn't answer
 * "How are you?" with "Great!". A line of the voice is tried first ({@code <occasion>.<voice>.<n>}), then the plain one.
 */
public final class Voices {
    public static final String CHEERFUL = "cheerful";
    public static final String GRUMPY = "grumpy";
    public static final String QUIET = "quiet";
    public static final String CHATTY = "chatty";

    private static final Map<String, String> BY_ARCHETYPE = Map.ofEntries(
            Map.entry("optimist", CHEERFUL), Map.entry("joker", CHEERFUL), Map.entry("dreamer", CHEERFUL),
            Map.entry("grumbler", GRUMPY), Map.entry("idler", GRUMPY), Map.entry("workhorse", GRUMPY),
            Map.entry("quiet", QUIET), Map.entry("thinker", QUIET), Map.entry("worrier", QUIET),
            Map.entry("chatterbox", CHATTY), Map.entry("gossip", CHATTY));

    private Voices() {
    }

    /** The voice of a character archetype; an unknown one (a server's own) sounds plain. */
    public static String of(String archetype) {
        return archetype == null ? "" : BY_ARCHETYPE.getOrDefault(archetype, "");
    }
}

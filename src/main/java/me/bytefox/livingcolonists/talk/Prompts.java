package me.bytefox.livingcolonists.talk;

import java.util.Locale;
import java.util.Random;

/** Values for prompt templates and clean-up of model output, kept free of game classes for unit tests. */
public final class Prompts {
    /** Share of replies in which a colonist may bring up one of their own worries unasked. */
    public static final double INITIATIVE_CHANCE = 0.25;
    /** The same while colonists chat among themselves, where worries would snowball. */
    public static final double CHAT_INITIATIVE_CHANCE = 0.1;
    /** From this many colonist lines into a thread, the subject changes more often. */
    public static final int TOPIC_FATIGUE_TURNS = 3;
    /** How often a colonist changes the subject once a thread runs long. */
    public static final double CHANGE_TOPIC_CHANCE = 0.4;
    private static final String STEER = "Change the subject to something new that interests you and nobody has mentioned; "
            + "don't refer to the last topic at all.";
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

    /**
     * Asks the colonist to pass on something heard in someone's private talk. Whether to gossip at
     * all is the code's dice roll (see {@code MemoryService#rumor}).
     */
    public static String gossip(String fact) {
        return "If it fits at all, also pass on a bit of gossip, as hearsay (\"they say\", \"I heard\"); you may "
                + "keep vague who told you: " + fact;
    }

    /** Moods a colonist may be in for a day, with how common each is for a colonist of middling happiness. */
    private static final String[] MOODS = {
            "in a good mood", "calm, nothing special", "tired and not very talkative",
            "irritable: little things get on your nerves", "distracted, thinking about your own business",
            "bored", "gloomy"};
    private static final double[] MOOD_WEIGHTS = {15, 30, 15, 10, 15, 10, 5};

    /**
     * The colonist's mood for this Minecraft day. Models are cheerful and eager by default, so the
     * code rolls a mood: the same all day for one colonist, worse moods more often when they are
     * unhappy (happiness is MineColonies' 0 to 10).
     */
    public static String mood(int citizenId, long day, double happiness) {
        double[] weights = MOOD_WEIGHTS.clone();
        if (happiness < 5) {
            weights[0] *= 0.4;
            weights[3] *= 2.5;
            weights[6] *= 3;
        } else if (happiness > 8) {
            weights[0] *= 2;
            weights[3] *= 0.5;
            weights[6] *= 0.3;
        }
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        double roll = new Random(citizenId * 1_000_003L + day * 7_919L).nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) {
                return MOODS[i];
            }
        }
        return MOODS[1];
    }

    /** Whether a reply means the colonist chose to say nothing: "[silence]", or the same in other words. */
    public static boolean isSilence(String text) {
        String bare = text.replaceAll("[\\[\\]().*_\\s]", "").toLowerCase(Locale.ROOT);
        return bare.isEmpty() || bare.equals("silence") || bare.equals("молчание") || bare.equals("тишина") || bare.equals("…");
    }

    /** {@link #initiative} for colonists chatting among themselves: worries come up more rarely. */
    public static String chatInitiative(Random random) {
        return random.nextDouble() < CHAT_INITIATIVE_CHANCE
                ? "If it fits, you may mention one thing that bothers you, in passing."
                : "Don't bring up your own work, materials or needs unless the conversation is about them.";
    }

    /**
     * How a colonist reacts in a colony conversation this time. Models drift into agreeing with
     * everyone, so the code picks: disagree (as often as the archetype's contrariness), agree but
     * with a twist of one's own, change the subject, or ask someone a question.
     */
    public static String stance(double contrariness, Random random) {
        return stance(contrariness, 0, random);
    }

    /**
     * The same, {@code turns} colonist lines into a thread: from the third line on, models keep
     * circling one joke or topic, so the subject changes much more often.
     */
    public static String stance(double contrariness, int turns, Random random) {
        if (turns >= TOPIC_FATIGUE_TURNS && random.nextDouble() < CHANGE_TOPIC_CHANCE) {
            return STEER;
        }
        double roll = random.nextDouble();
        if (roll < contrariness) {
            return "This time you don't agree with what was said last: say why, in your own way, and stand by your view.";
        }
        double rest = (roll - contrariness) / (1 - contrariness);
        if (rest < 0.45) {
            return "You partly agree, but add your own angle or a doubt; don't just echo the others.";
        }
        if (rest < 0.75) {
            return STEER;
        }
        return "Ask someone in the chat a question, by name.";
    }

    /** A background as one paragraph of plain text, without formatting codes, within a sane length. */
    public static String cleanBio(String text) {
        String bio = text.replaceAll("§.?", "").replaceAll("\\s+", " ").strip();
        return bio.length() > MAX_BIO_LENGTH ? bio.substring(0, MAX_BIO_LENGTH - 1) + "…" : bio;
    }
}

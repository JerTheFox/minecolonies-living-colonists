package me.bytefox.livingcolonists.talk;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Values for prompt templates and clean-up of model output, kept free of game classes for unit tests. */
public final class Prompts {
    /** Share of replies in which a colonist may bring up one of their own worries unasked. */
    public static final double INITIATIVE_CHANCE = 0.1;
    /** The same while colonists chat among themselves, where worries would snowball. */
    public static final double CHAT_INITIATIVE_CHANCE = 0.05;
    /** From this many colonist lines into a thread, the subject changes more often. */
    public static final int TOPIC_FATIGUE_TURNS = 3;
    /** How often a colonist changes the subject once a thread runs long. */
    public static final double CHANGE_TOPIC_CHANCE = 0.3;
    /**
     * What a colonist may turn the talk to, picked by code: left to itself a model talks about the
     * weather and the time of day, because those are in its prompt. Broad on purpose, so the colonist
     * fills in the details from their own life.
     */
    static final List<String> TOPICS = List.of(
            "something that happened at your work today", "a trick of your trade you are proud of",
            "a tool or piece of equipment you like or hate", "a mistake you made once and learned from",
            "a neighbour's habit", "someone in the colony you admire", "an old friend you miss",
            "your family", "a childhood memory", "a place you lived before the colony",
            "a dream you had", "something you'd like to learn", "a small thing that annoyed you today",
            "something funny you saw", "a rumour you only half believe", "what you'd build if you could",
            "a plan for your day off", "a dish you like or one you can't stand", "a recipe from home",
            "an animal you noticed around the colony", "a plant or flower somewhere near",
            "a song or a tune stuck in your head", "a story or a legend you know", "a superstition",
            "how the colony has changed since you came", "what the colony needs most, in your view",
            "a bet or a little contest with someone", "a gift you'd like to give someone",
            "something you lost or found", "your health or a small ache", "how you slept",
            "something you want to buy or trade for", "a skill someone else has that you envy",
            "an argument you had and who was right", "a promise someone made you",
            "a job you'd never want to do", "what makes a good day for you", "the night sky or the stars",
            "the season and what it brings", "a festival or celebration you'd like", "a hobby of yours",
            "a question you've wondered about for a while", "advice you'd give a newcomer",
            "something you're secretly afraid of", "a compliment you got or gave", "a habit you want to drop",
            "the weather");
    /** How a colonist puts a line this time: models fall into one shape and one length of line. */
    private static final String[] FORMS = {
            "This time just react, in one to four words.",
            "This time say it as a short question.",
            "This time you may tell a little more: two sentences, a small detail or a story."};
    private static final double[] FORM_CHANCES = {0.2, 0.15, 0.1};
    /** Ways of speaking; each colonist keeps one for life, picked by code from their name. */
    static final List<String> VOICES = List.of(
            "You speak briefly and to the point.", "You are chatty and wander off the subject a little.",
            "You like old sayings and proverbs.", "You speak plainly, with simple everyday words.",
            "You speak a bit bookishly, choosing careful words.", "You grumble a little whatever you talk about.",
            "You joke dryly, with a straight face.", "You are shy and soft-spoken.", "You are blunt and say what you think.",
            "You often ask others what they think.", "You speak warmly and kindly.", "You are a little pompous.",
            "You speak quickly, in bits and pieces.", "You are calm and slow, never in a hurry.");
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

    /**
     * The time of day with the hour, like "midday, about 12 o'clock", so the model doesn't guess
     * where the sun is. Minecraft day time: 0 is 6 am, 6000 noon, 12000 6 pm, 18000 midnight.
     */
    public static String timeOfDay(long dayTime) {
        int hour = (int) ((Math.floorMod(dayTime, 24000L) / 1000 + 6) % 24);
        String part;
        if (hour == 5) {
            part = "dawn";
        } else if (hour >= 6 && hour < 11) {
            part = "morning";
        } else if (hour >= 11 && hour < 14) {
            part = "midday";
        } else if (hour >= 14 && hour < 18) {
            part = "afternoon";
        } else if (hour >= 18 && hour < 21) {
            part = "evening";
        } else {
            part = "night";
        }
        return part + ", about " + hour + " o'clock";
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
                : "Answer only what the player said. Don't bring up your work, home, materials or needs unless the player asks about them.";
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

    /** The model's sample lines in a colonist's voice: at most four, one per line, without numbers or quotes. */
    public static String voiceLines(String text) {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : text.split("\\R")) {
            String clean = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").strip();
            if (clean.length() >= 2 && (clean.startsWith("\"") && clean.endsWith("\"") || clean.startsWith("«") && clean.endsWith("»"))) {
                clean = clean.substring(1, clean.length() - 1).strip();
            }
            if (!clean.isEmpty() && clean.length() <= 120 && lines.size() < 4) {
                lines.add(clean);
            }
        }
        return String.join("\n", lines);
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
                : "Don't bring up your own work, home, materials, aches or needs unless the conversation is about them.";
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
        return stance(contrariness, turns, random, List.of());
    }

    /** The same, changing the subject to a topic the thread hasn't had yet. */
    public static String stance(double contrariness, int turns, Random random, Collection<String> usedTopics) {
        if (turns >= TOPIC_FATIGUE_TURNS && random.nextDouble() < CHANGE_TOPIC_CHANCE) {
            return steer(random, usedTopics);
        }
        double roll = random.nextDouble();
        if (roll < contrariness) {
            return "This time you don't agree with what was said last: say why, in your own way, and stand by your view.";
        }
        double rest = (roll - contrariness) / (1 - contrariness);
        // Whoever answers the line that opened a talk answers it rather than change the subject at once.
        if (rest < 0.5 || rest < 0.7 && turns <= 1) {
            return "You partly agree, but add your own angle or a doubt; don't just echo the others.";
        }
        if (rest < 0.7) {
            return steer(random, usedTopics);
        }
        return "Ask someone in the chat a question, by name.";
    }

    /** Changes the subject to one topic, not yet had in this thread, picked by the dice. */
    static String steer(Random random, Collection<String> usedTopics) {
        List<String> fresh = TOPICS.stream().filter(topic -> !usedTopics.contains(topic)).toList();
        String topic = (fresh.isEmpty() ? TOPICS : fresh).get(random.nextInt((fresh.isEmpty() ? TOPICS : fresh).size()));
        return "Change the subject to " + topic + ", in your own way; don't refer to the last topic at all.";
    }

    /** The topic a steer line names, if it is one, so the thread won't come back to it. */
    public static String topicOf(String stance) {
        String prefix = "Change the subject to ";
        int end = stance.indexOf(", in your own way");
        return stance.startsWith(prefix) && end > 0 ? stance.substring(prefix.length(), end) : null;
    }

    /** The shape of this line, or nothing for an ordinary one; an answer to a player is never a bare question. */
    public static String form(Random random, boolean answeringPlayer) {
        double roll = random.nextDouble();
        for (int i = 0; i < FORMS.length; i++) {
            if (roll < FORM_CHANCES[i]) {
                return answeringPlayer && i == 1 ? "" : FORMS[i];
            }
            roll -= FORM_CHANCES[i];
        }
        return "";
    }

    /** How this colonist speaks, the same for life: from their name, so it survives restarts. */
    public static String voice(String name) {
        return VOICES.get(Math.floorMod(name.hashCode() * 31 + 7, VOICES.size()));
    }

    /** Keeps the first {@code max} sentences: chat lines stay chat lines whatever the model wrote. */
    public static String firstSentences(String text, int max) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…') && (i + 1 == text.length() || text.charAt(i + 1) == ' ')
                    && ++count == max) {
                return text.substring(0, i + 1).strip();
            }
        }
        return text;
    }

    /** A background as one paragraph of plain text, without formatting codes, within a sane length. */
    public static String cleanBio(String text) {
        String bio = text.replaceAll("§.?", "").replaceAll("\\s+", " ").strip();
        return bio.length() > MAX_BIO_LENGTH ? bio.substring(0, MAX_BIO_LENGTH - 1) + "…" : bio;
    }
}

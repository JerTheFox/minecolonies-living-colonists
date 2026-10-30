package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * When a colonist writes to a player first, unprompted, and about what: a budget of such lines per
 * player in a rolling window, and topics weighed by the dice, so the model never decides it (it
 * would talk all the time, or about the same thing). Pure; ticks come from the caller.
 */
public final class InitiativePolicy {
    public static final long MINUTE = 60 * 20;

    private InitiativePolicy() {
    }

    /** How many unprompted lines one player may get in {@link #window}: none when off. */
    public static int budget(Level level) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> 1;
            case DEFAULT -> 2;
            case HIGH -> 4;
        };
    }

    /** The rolling window the budget counts in. */
    public static long window(Level level) {
        return level == Level.LOW ? 40 * MINUTE : 20 * MINUTE;
    }

    /** One colonist writes first to the same player at most this often. */
    public static long perColonist(Level level) {
        return level == Level.HIGH ? 20 * MINUTE : 45 * MINUTE;
    }

    /** The usual pause before a colonist next writes first to a player, by level. */
    static long meanGap(Level level) {
        return switch (level) {
            case OFF -> Long.MAX_VALUE / 4;
            case LOW -> 30 * MINUTE;
            case DEFAULT -> 12 * MINUTE;
            case HIGH -> 6 * MINUTE;
        };
    }

    /** Now and then the colony keeps quiet much longer than usual. */
    static final double LONG_SILENCE = 0.15;

    /**
     * How long until the next unprompted line to a player: spread widely around the level's usual
     * pause, and sometimes much longer, so nobody can tell the clock by it. At least two minutes.
     */
    public static long gap(Level level, Random random) {
        double spread = 0.35 + random.nextDouble() * 1.3;
        if (random.nextDouble() < LONG_SILENCE) {
            spread *= 2.5;
        }
        return Math.max(2 * MINUTE, Math.round(meanGap(level) * spread));
    }

    /** A moment that didn't suit (the player busy, a chat going on) is tried again this much later. */
    public static long retry(Random random) {
        return Math.round((20 + random.nextDouble() * 70) * 20);
    }

    /** The same topic with the same player comes back no sooner than this. */
    public static final long SAME_TOPIC = 3 * 60 * MINUTE;
    /** Asking about the same unsolved problem again waits at least this long. */
    public static final long COMPLAINT_GAP = 30 * MINUTE;
    /** A player quiet for this long since their last message is no longer in a conversation. */
    public static final long QUIET = 90 * 20;

    /** A player's recent unprompted lines, oldest first. */
    public static final class Budget {
        private final Deque<Long> lines = new ArrayDeque<>();

        public boolean allows(long now, Level level) {
            while (!lines.isEmpty() && now - lines.peekFirst() > window(level)) {
                lines.removeFirst();
            }
            return lines.size() < budget(level);
        }

        public void spend(long now) {
            lines.addLast(now);
        }
    }

    /** A colonist who spoke up first this long ago is fully "due" again; sooner, much less likely. */
    static final long TURN = 2 * 60 * MINUTE;

    /**
     * How likely a colonist is to be the one who speaks up. Everybody gets turns: the talkative a bit
     * more often, but whoever hasn't spoken up for a long time (or ever) comes first, and whoever
     * just did steps back, so the same few with the same troubles don't fill the chat.
     *
     * @param since ticks since this colonist last wrote first to anyone, or negative if never
     */
    public static double speakerWeight(double talkativeness, long since, int rapport) {
        double turn = since < 0 ? 2.0 : Math.max(0.1, Math.min(1.5, (double) since / TURN));
        return (0.4 + talkativeness) * turn * (rapport <= PlayerNames.COLD ? 0.5 : 1);
    }

    /** A colonist who complained to anyone within this long leans toward other topics. */
    public static final long COMPLAINED_LATELY = 2 * 60 * MINUTE;

    /** GOAL: something the colonist hoped for came true. */
    public enum Kind { COMPLAINT, FOLLOW_UP, NEWS, SMALL_TALK, GOAL }

    /**
     * Something a colonist could bring up.
     *
     * @param key    stays the same for the same subject, against repeats ("need:home", "fact:…")
     * @param raised how many times this complaint was already raised to the player, unsolved
     */
    public record Topic(Kind kind, String key, String text, int raised) {
    }

    /**
     * Picks a topic by weight: a real problem of the colonist's first, then following up what the
     * player told them, then news, then small talk. A colonist who dislikes the player keeps to
     * complaints; a friend chats more.
     */
    public static Optional<Topic> pick(List<Topic> topics, int rapport, Random random) {
        return pick(topics, rapport, false, random);
    }

    /** The same; a colonist who complained lately brings up a problem much less readily. */
    public static Optional<Topic> pick(List<Topic> topics, int rapport, boolean complainedLately, Random random) {
        double total = 0;
        double[] weights = new double[topics.size()];
        for (int i = 0; i < topics.size(); i++) {
            Topic topic = topics.get(i);
            double weight = switch (topic.kind()) {
                case COMPLAINT -> complainedLately ? 0.7 : 3;
                case FOLLOW_UP -> 2;
                case NEWS -> 1;
                case SMALL_TALK -> 1;
                case GOAL -> 3;
            };
            if (topic.kind() != Kind.COMPLAINT) {
                weight *= rapport <= PlayerNames.COLD ? 0.2 : rapport >= PlayerNames.CLOSE ? 1.5 : 1;
            }
            weights[i] = weight;
            total += weight;
        }
        if (total <= 0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < topics.size(); i++) {
            roll -= weights[i];
            if (roll < 0) {
                return Optional.of(topics.get(i));
            }
        }
        return Optional.of(topics.get(topics.size() - 1));
    }

    /** What a colonist hopes for, in words: {@code place} names the building for a {@code place:} goal. */
    public static String goalHope(String kind, int level, String place) {
        return switch (kind.contains(":") ? "place" : kind) {
            case "home" -> "a better home: your house grown to level " + level;
            case "work" -> "your workplace, the " + place + ", improved to level " + level;
            default -> "the colony getting " + place;
        };
    }

    /** The same goal, come true. */
    public static String goalDone(String kind, int level, String place) {
        return switch (kind.contains(":") ? "place" : kind) {
            case "home" -> "your house was improved to level " + level + ", as you had hoped";
            case "work" -> "your workplace, the " + place + ", was improved to level " + level + ", as you had hoped";
            default -> "the colony got " + place + ", as you had hoped";
        };
    }

    /** After this many unanswered mentions a colonist gives up on a problem with this player. */
    public static final int GIVES_UP_AFTER = 3;

    /**
     * How a problem is raised, by how often it was raised to this player already without a fix:
     * helpfully first, then pointedly, then frustrated, and in the end the colonist gives up asking.
     */
    public static String complaint(String issue, int raised) {
        return switch (Math.min(raised, GIVES_UP_AFTER)) {
            case 0 -> "to bring up, briefly and helpfully, a problem of yours: " + issue + ". Say what would help.";
            case 1 -> "to bring up again a problem you already told them about and that is still not solved: " + issue
                    + ". Be a bit more pointed.";
            case 2 -> "about a problem you have asked them about several times, and nothing changed: " + issue
                    + ". You are fed up and it shows, in your own way.";
            default -> "about a problem nobody fixed however often you asked: " + issue + ". Say, briefly and in "
                    + "your own way, that you won't bother them with it any more.";
        };
    }
}

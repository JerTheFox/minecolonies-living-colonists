package me.bytefox.livingcolonists.memory;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import me.bytefox.livingcolonists.behaviour.Level;

/**
 * When a colony conversation goes on and who speaks next. The code decides the odds; the chosen
 * colonist may still stay silent, which ends the thread.
 */
public final class ThreadPolicy {
    private ThreadPolicy() {
    }

    /** Colonist turns a thread may run without a player speaking. */
    public static int maxTurns(Level level) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> 4;
            case DEFAULT -> 8;
            case HIGH -> 16;
        };
    }

    /**
     * Chance that another colonist speaks after {@code turns} colonist lines since a player last
     * spoke; zero once the level's limit is reached.
     */
    public static double continueChance(Level level, int turns) {
        if (turns >= maxTurns(level)) {
            return 0;
        }
        // Base chance after the first line, and how much each further line lowers it. On high a
        // thread runs about five lines on average and four in ten reach six or more.
        double base = switch (level) {
            case OFF -> 0;
            case LOW -> 0.5;
            case DEFAULT -> 0.8;
            case HIGH -> 0.9;
        };
        double decay = switch (level) {
            case OFF, LOW -> 0.85;
            case DEFAULT -> 0.92;
            case HIGH -> 0.96;
        };
        return base * Math.pow(decay, Math.max(0, turns - 1));
    }

    /** What the conversation is about now, which decides how readily others join in. */
    public enum Kind {
        /** Colonists among themselves, no player talking lately. */
        CHATTER,
        /** A player is talking with the colony: others join in now and then, not after every line. */
        TALK,
        /** A player's request was looked up or ordered: after the answer the chat mostly stays quiet. */
        BUSINESS
    }

    /** The same, for the kind of conversation: while a player talks, others join far less. */
    public static double continueChance(Level level, int turns, Kind kind) {
        if (kind == Kind.CHATTER || turns >= maxTurns(level)) {
            return continueChance(level, turns);
        }
        double base = kind == Kind.BUSINESS ? 0.15 : 0.3;
        double scale = switch (level) {
            case OFF -> 0;
            case LOW -> 0.5;
            case DEFAULT -> 1;
            case HIGH -> 1.5;
        };
        return base * scale * Math.pow(0.5, Math.max(0, turns - 1));
    }

    /**
     * How much life a colonists' conversation has when it starts, about how many lines it would run
     * if nothing livened it up or killed it: drawn wide, so one dies after a reply and another
     * grows into a long talk.
     */
    public static double startEnergy(Level level, Random random) {
        double mean = switch (level) {
            case OFF -> 0;
            case LOW -> 1.2;
            case DEFAULT -> 2.5;
            case HIGH -> 4.5;
        };
        return -Math.log(1 - random.nextDouble()) * mean;
    }

    /** What a line did to the conversation, as code can tell. */
    public record LineTraits(boolean question, boolean namesSomeone, boolean disagreed, boolean shortReaction) {
    }

    /**
     * The energy after a line: every line spends some; a question, a name called or an argument
     * gives the others something to answer; a bare reaction lets the talk wind down.
     */
    public static double afterLine(double energy, LineTraits line, Random random) {
        double change = -1 + (random.nextDouble() - 0.5) * 0.6;
        change += line.question() ? 0.8 : 0;
        change += line.namesSomeone() ? 0.6 : 0;
        change += line.disagreed() ? 0.6 : 0;
        change -= line.shortReaction() ? 0.4 : 0;
        return energy + change;
    }

    /** A safety stop only: conversations end by losing their energy, not by counting. */
    public static int hardCap(Level level) {
        return switch (level) {
            case OFF -> 0;
            case LOW -> 6;
            case DEFAULT -> 14;
            case HIGH -> 24;
        };
    }

    public static boolean goesOn(double energy, int turns, Level level) {
        return energy > 0 && turns < hardCap(level);
    }

    /** Nobody is ever ruled out of a conversation: the smallest weight a colonist keeps. */
    public static final double MIN_WEIGHT = 0.15;

    /**
     * How readily a colonist joins in next, besides talkativeness and their relation to the last
     * speaker: near them more, far less; a coworker more; whoever already said a lot steps back.
     *
     * @param distance blocks from the last speaker, or negative if either isn't loaded
     */
    public static double joinWeight(double base, double distance, boolean coworker, int linesSoFar) {
        double near = distance < 0 ? 0.5 : distance <= 16 ? 1.5 : distance <= 48 ? 1 : 0.7;
        double weight = base * near * (coworker ? 1.3 : 1) * Math.pow(0.7, linesSoFar);
        return Math.max(MIN_WEIGHT, weight);
    }

    /**
     * The chance an unprompted moment goes to the colony chat rather than a private word: even by
     * default, rarer when the colonist has a problem of their own to tell, and a little likelier the
     * longer the other channel has had its turns in a row.
     *
     * @param streak positive: so many colony chats in a row lately; negative: so many private words
     */
    public static double colonyChance(boolean personalProblem, int streak) {
        double chance = personalProblem ? 0.25 : 0.5;
        chance -= Math.max(-3, Math.min(3, streak)) * 0.08;
        return Math.max(0.1, Math.min(0.9, chance));
    }

    /** @param weight how likely this colonist speaks next, relative to the others */
    public record Candidate<T>(T id, double weight) {
    }

    /** A weighted pick that never gives the floor to the one who just spoke. */
    public static <T> Optional<T> pickSpeaker(List<Candidate<T>> candidates, T lastSpeaker, Random random) {
        List<Candidate<T>> eligible = candidates.stream()
                .filter(c -> !c.id().equals(lastSpeaker) && c.weight() > 0).toList();
        double total = eligible.stream().mapToDouble(Candidate::weight).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        double roll = random.nextDouble() * total;
        for (Candidate<T> candidate : eligible) {
            roll -= candidate.weight();
            if (roll < 0) {
                return Optional.of(candidate.id());
            }
        }
        return Optional.of(eligible.get(eligible.size() - 1).id());
    }
}

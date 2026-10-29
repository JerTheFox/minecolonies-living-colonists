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

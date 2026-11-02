package me.bytefox.livingcolonists.mood;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One colony's mood (#65): what events stirred it, each fading over its own time. The strongest one now leads; below
 * {@link MoodTable#calm()} the colony is calm, which it is most of the time. A weak event doesn't drown a strong one,
 * the same occasion doesn't stir it again before its cooldown, and a second mood joins in when it is at least half as
 * strong. Kept in memory: after a restart the colony starts calm. Server thread only.
 */
public final class ColonyMood {
    /** The mood that leads now, and how strongly it is felt. */
    public record Leading(Mood mood, double intensity) {
    }

    /** What stirred the mood: who it touches closely (a dead colonist's family) feel it harder. */
    private record Stir(String occasion, Mood mood, double strength, long at, long fade, String cause, Set<Integer> close) {
        double intensity(long now) {
            return now < at ? 0 : strength * Math.max(0, 1 - (double) (now - at) / fade);
        }
    }

    private final List<Stir> stirs = new ArrayList<>();
    private final Map<String, Long> lastRaised = new HashMap<>();

    /** An occasion from {@link MoodTable} happened; {@code cause} says what, in English, for the model. */
    public void raise(String occasion, String cause, Set<Integer> close, long now) {
        MoodTable.Row row = MoodTable.row(occasion);
        if (row.mood() == Mood.CALM || row.strength() <= 0) {
            return;
        }
        Long last = lastRaised.get(occasion);
        if (last != null && now - last < row.cooldownTicks()) {
            return;
        }
        lastRaised.put(occasion, now);
        stirs.removeIf(stir -> stir.intensity(now) <= 0);
        stirs.add(new Stir(occasion, row.mood(), row.strength(), now, row.fadeTicks(), cause, Set.copyOf(close)));
    }

    public Leading leading(long now) {
        List<Stir> felt = felt(now);
        return felt.isEmpty() ? new Leading(Mood.CALM, 0) : new Leading(felt.get(0).mood(), felt.get(0).intensity(now));
    }

    /**
     * How the model is told of the colony's mood for one colonist ({@code citizenId}, or -1 for nobody in particular):
     * the leading mood with its cause, a second one if it is strong enough, and a line more for those it touches
     * closely. Empty while the colony is calm.
     */
    public Optional<String> tone(long now, int citizenId) {
        List<Stir> felt = felt(now);
        if (felt.isEmpty()) {
            return Optional.empty();
        }
        Stir first = felt.get(0);
        StringBuilder tone = new StringBuilder(MoodTable.tone(first.mood()).replace("{cause}", first.cause()));
        felt.stream().skip(1)
                .filter(stir -> stir.mood() != first.mood() && stir.intensity(now) >= first.intensity(now) / 2)
                .findFirst()
                .ifPresent(second -> tone.append(" At the same time: ")
                        .append(MoodTable.tone(second.mood()).replace("{cause}", second.cause())));
        felt.stream().filter(stir -> stir.close().contains(citizenId)).findFirst()
                .ifPresent(own -> tone.append(' ').append(MoodTable.closeLine(own.mood())));
        return Optional.of(tone.toString());
    }

    /** What is felt now, strongest first. */
    private List<Stir> felt(long now) {
        return stirs.stream().filter(stir -> stir.intensity(now) >= MoodTable.calm())
                .sorted((a, b) -> Double.compare(b.intensity(now), a.intensity(now))).toList();
    }

    /** For {@code /lcdebug mood}: every stir still felt at all, strongest first. */
    public List<String> describe(long now) {
        return stirs.stream().filter(stir -> stir.intensity(now) > 0)
                .sorted((a, b) -> Double.compare(b.intensity(now), a.intensity(now)))
                .map(stir -> String.format(java.util.Locale.ROOT, "%s %.1f (%s): %s", stir.mood(), stir.intensity(now),
                        stir.occasion(), stir.cause()))
                .toList();
    }
}

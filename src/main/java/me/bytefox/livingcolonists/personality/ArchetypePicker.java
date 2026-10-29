package me.bytefox.livingcolonists.personality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks an archetype for a colonist: mostly by their MineColonies skills, partly by chance (so
 * not every clever colonist ends up a philosopher), and for children sometimes after a parent.
 */
public final class ArchetypePicker {
    /** Share of colonists whose archetype ignores their skills. */
    static final double CHANCE_SHARE = 0.2;
    /** How strongly skills pull: affinity times the skill's standing among the colonist's own skills. */
    private static final double SKILL_STRENGTH = 2.0;

    private ArchetypePicker() {
    }

    /**
     * @param skills         skill name to level
     * @param parents        archetype ids of the colonist's parents; empty for adults
     * @param inheritChance  probability that a child takes a parent's archetype
     */
    public static Archetype pick(List<Archetype> archetypes, Map<String, Integer> skills, List<String> parents,
                                 double inheritChance, Random random) {
        if (archetypes.isEmpty()) {
            return Archetype.ORDINARY;
        }
        List<Archetype> parental = archetypes.stream().filter(a -> parents.contains(a.id())).toList();
        if (!parental.isEmpty() && random.nextDouble() < inheritChance) {
            return parental.get(random.nextInt(parental.size()));
        }
        boolean bySkills = !skills.isEmpty() && random.nextDouble() >= CHANCE_SHARE;
        List<Double> scores = new ArrayList<>();
        for (Archetype archetype : archetypes) {
            scores.add(bySkills ? archetype.weight() * Math.exp(SKILL_STRENGTH * affinity(archetype, skills))
                    : archetype.weight());
        }
        return weighted(archetypes, scores, random);
    }

    /** How well the archetype fits: affinities times how far each skill stands from the colonist's average. */
    private static double affinity(Archetype archetype, Map<String, Integer> skills) {
        double mean = skills.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = skills.values().stream().mapToDouble(level -> (level - mean) * (level - mean)).average().orElse(0);
        double spread = Math.max(1, Math.sqrt(variance));
        double sum = 0;
        for (Map.Entry<String, Double> entry : archetype.skills().entrySet()) {
            Integer level = skills.get(entry.getKey());
            if (level != null) {
                sum += entry.getValue() * (level - mean) / spread;
            }
        }
        return sum;
    }

    private static Archetype weighted(List<Archetype> archetypes, List<Double> scores, Random random) {
        double total = scores.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) {
            return archetypes.get(random.nextInt(archetypes.size()));
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < archetypes.size(); i++) {
            roll -= scores.get(i);
            if (roll < 0) {
                return archetypes.get(i);
            }
        }
        return archetypes.get(archetypes.size() - 1);
    }
}

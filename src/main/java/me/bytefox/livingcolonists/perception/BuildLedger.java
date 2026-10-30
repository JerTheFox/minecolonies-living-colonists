package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the builder's resource scroll shows, in words: for each material of a construction, how much
 * was needed, how much is used already, how much the builder holds and how much is on the way.
 * MineColonies keeps only what is still needed, which shrinks as the builder places blocks, so the
 * amount at the start is the largest one seen for that order. Server thread only; free of game
 * classes for unit tests.
 */
public final class BuildLedger {
    private static final int MAX_ORDERS = 200;

    /** @param needed still needed for the rest of the build; @param held at the builder's; @param coming being delivered */
    public record Material(String name, int needed, int held, int coming) {
        int missing() {
            return Math.max(0, needed - held - coming);
        }
    }

    /** By order, the most of each material it was seen to need. */
    private final Map<String, Map<String, Integer>> started = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Integer>> eldest) {
            return size() > MAX_ORDERS;
        }
    };

    /**
     * Lines like "Spruce Stairs: 66 of 106 used, 20 at hand, 20 on the way" for the materials still
     * needed, those missing first, at most {@code max}.
     *
     * @param order which construction, the same key every time it is looked at
     */
    public List<String> lines(String order, List<Material> materials, int max) {
        Map<String, Integer> start = started.computeIfAbsent(order, key -> new HashMap<>());
        for (Material material : materials) {
            start.merge(material.name(), material.needed(), Math::max);
        }
        List<String> lines = new ArrayList<>();
        materials.stream().filter(m -> m.needed() > 0)
                .sorted(Comparator.comparingInt(Material::missing).thenComparingInt(Material::needed).reversed())
                .limit(max)
                .forEach(m -> lines.add(line(m, start.getOrDefault(m.name(), m.needed()))));
        return lines;
    }

    /** About how much of the materials is used, 0 to 100, from what was seen of this order. */
    public int percentUsed(String order, List<Material> materials) {
        Map<String, Integer> start = started.getOrDefault(order, Map.of());
        int total = 0;
        int left = 0;
        for (Map.Entry<String, Integer> entry : start.entrySet()) {
            total += entry.getValue();
            left += materials.stream().filter(m -> m.name().equals(entry.getKey())).mapToInt(Material::needed).sum();
        }
        return total == 0 ? 0 : Math.max(0, Math.min(100, 100 * (total - left) / total));
    }

    static String line(Material m, int atStart) {
        int used = Math.max(0, atStart - m.needed());
        StringBuilder text = new StringBuilder(m.name()).append(": ");
        text.append(used > 0 ? used + " of " + atStart + " used" : atStart + " needed");
        text.append(", ").append(m.held()).append(" at hand");
        if (m.coming() > 0) {
            text.append(", ").append(m.coming()).append(" on the way");
        }
        text.append(m.missing() > 0 ? ", " + m.missing() + " still to bring" : ", nothing more to bring");
        return text.toString();
    }
}

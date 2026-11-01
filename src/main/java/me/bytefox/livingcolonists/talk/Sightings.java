package me.bytefox.livingcolonists.talk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When colonists speak up about enemies they see (#62), as plain rules: a lone mob is no news unless it attacks
 * someone, a place told about stays quiet for a while, the colony hears one sighting at a time, and near the end of
 * a raid the last few raiders are pointed out once. No Minecraft here; the game side is {@code perception.Enemies}.
 */
public final class Sightings {
    /** How long one place stays quiet after colonists spoke of enemies there. */
    public static final long PLACE_QUIET_TICKS = 5 * 60 * 20;
    /** The shortest time between two sightings told in one colony. */
    public static final long COLONY_GAP_TICKS = 60 * 20;
    /** At most this many raiders left count as the last ones. */
    public static final int STRAGGLERS = 3;
    /** How long the last raiders must hold out before colonists say where they are. */
    public static final long STRAGGLERS_AFTER_TICKS = 60 * 20;

    /**
     * Enemies seen together near one place.
     *
     * @param key    the place, the same each time (a building's position)
     * @param text   what and where, for the prompt: "3 Pillager by the Farm"
     * @param seenBy the colonist who sees them, or -1
     * @param x      where one of them is, to tell the way from someone
     * @param z      the same
     */
    public record Group(String key, String text, int count, boolean attacking, int seenBy, int x, int z) {
    }

    private final Map<String, Long> told = new HashMap<>();
    private final Map<Integer, Long> colonyTold = new HashMap<>();
    private final Map<Integer, Long> fewSince = new HashMap<>();
    private final Set<Integer> stragglersTold = new HashSet<>();

    /** The sighting to tell now, if any: the worst of those worth it, attackers and big groups first. */
    public List<Group> worthTelling(int colonyId, List<Group> groups, long now) {
        told.values().removeIf(at -> now - at >= PLACE_QUIET_TICKS);
        Long last = colonyTold.get(colonyId);
        if (last != null && now - last < COLONY_GAP_TICKS) {
            return List.of();
        }
        return groups.stream()
                .filter(group -> group.count() >= 2 || group.attacking())
                .filter(group -> !told.containsKey(colonyId + ":" + group.key()))
                .max(Comparator.comparing(Group::attacking).thenComparingInt(Group::count))
                .map(group -> {
                    told.put(colonyId + ":" + group.key(), now);
                    colonyTold.put(colonyId, now);
                    return List.of(group);
                })
                .orElse(List.of());
    }

    /** Whether to tell where the last raiders are now: a few left for a minute, once a raid. */
    public boolean stragglers(int colonyId, boolean raid, int left, long now) {
        if (!raid) {
            fewSince.remove(colonyId);
            stragglersTold.remove(colonyId);
            return false;
        }
        if (left == 0 || left > STRAGGLERS) {
            fewSince.remove(colonyId);
            return false;
        }
        long since = fewSince.computeIfAbsent(colonyId, id -> now);
        return now - since >= STRAGGLERS_AFTER_TICKS && stragglersTold.add(colonyId);
    }

    public void clear() {
        told.clear();
        colonyTold.clear();
        fewSince.clear();
        stragglersTold.clear();
    }
}

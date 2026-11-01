package me.bytefox.livingcolonists.perception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.colonyEvents.IColonyRaidEvent;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.errands.Compass;
import me.bytefox.livingcolonists.talk.Sightings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Enemies the colonists can see (#62): hostile mobs near the colony's loaded colonists, and a raid's raiders from
 * MineColonies' own raid events. Looking only around colonists keeps it cheap: no scan of the whole colony.
 * Server thread only.
 */
public final class Enemies {
    /** How far a colonist sees an enemy, in blocks. */
    private static final double SIGHT = 16;
    /** Closer than this to a building is "by" it, in blocks. */
    private static final double BY = 24;

    /** An enemy and the colonist nearest to it who sees it, or -1 for a raider nobody sees. */
    public record Seen(Mob mob, int seenBy) {
    }

    private Enemies() {
    }

    /** Hostile mobs within sight of the colony's loaded colonists, each once, with the nearest colonist. */
    public static List<Seen> around(IColony colony) {
        Map<Integer, Seen> seen = new LinkedHashMap<>();
        Map<Integer, Double> nearest = new HashMap<>();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            var entity = citizen.getEntity();
            if (entity.isEmpty() || !entity.get().isAlive()) {
                continue;
            }
            AbstractEntityCitizen colonist = entity.get();
            for (Mob mob : colonist.level().getEntitiesOfClass(Mob.class, colonist.getBoundingBox().inflate(SIGHT),
                    mob -> mob instanceof Enemy && mob.isAlive())) {
                double distance = mob.distanceToSqr(colonist);
                if (distance < nearest.getOrDefault(mob.getId(), Double.MAX_VALUE)) {
                    nearest.put(mob.getId(), distance);
                    seen.put(mob.getId(), new Seen(mob, citizen.getId()));
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** The raiders of the colony's raids still alive, wherever they are. */
    public static List<Seen> raiders(IColony colony) {
        List<Seen> raiders = new ArrayList<>();
        for (var event : colony.getEventManager().getEvents().values()) {
            if (event instanceof IColonyRaidEvent raid) {
                for (Entity entity : raid.getEntities()) {
                    if (entity instanceof Mob mob && mob.isAlive()) {
                        raiders.add(new Seen(mob, -1));
                    }
                }
            }
        }
        return raiders;
    }

    /** The enemies grouped by the building they are nearest to: "3 Pillager by the Farm", most first. */
    /**
     * The enemies in the colony at this moment, for a talk that mentioned enemies a while ago: by the next line they may
     * be dead or gone, and the colonists must not go on about them as if they were still there.
     */
    public static String now(IColony colony) {
        List<Sightings.Group> groups = groups(colony, around(colony));
        return groups.isEmpty() ? "No enemies are in the colony at this moment: any mentioned earlier are dead or gone."
                : "Enemies in the colony at this moment: " + groups.stream().map(Sightings.Group::text)
                .collect(java.util.stream.Collectors.joining("; ")) + ".";
    }

    public static List<Sightings.Group> groups(IColony colony, List<Seen> seen) {
        Map<String, List<Seen>> byPlace = new LinkedHashMap<>();
        Map<String, String> places = new HashMap<>();
        for (Seen one : seen) {
            BlockPos at = one.mob().blockPosition();
            IBuilding building = nearestBuilding(colony, at);
            String key = building == null ? "colony" : building.getPosition().toShortString();
            byPlace.computeIfAbsent(key, k -> new ArrayList<>()).add(one);
            places.putIfAbsent(key, place(colony, building, at));
        }
        List<Sightings.Group> groups = new ArrayList<>();
        byPlace.forEach((key, list) -> {
            Map<String, Integer> kinds = new TreeMap<>();
            boolean attacking = false;
            for (Seen one : list) {
                kinds.merge(one.mob().getType().getDescription().getString(), 1, Integer::sum);
                LivingEntity target = one.mob().getTarget();
                attacking |= target instanceof AbstractEntityCitizen || target instanceof Player;
            }
            List<String> named = new ArrayList<>();
            kinds.forEach((kind, count) -> named.add(count + " " + kind));
            int seenBy = list.stream().mapToInt(Seen::seenBy).filter(id -> id >= 0).findFirst().orElse(-1);
            groups.add(new Sightings.Group(key, String.join(", ", named) + " " + places.get(key)
                    + (attacking ? ", attacking someone" : ""), list.size(), attacking, seenBy,
                    list.get(0).mob().getBlockX(), list.get(0).mob().getBlockZ()));
        });
        groups.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return groups;
    }

    /** "by the Farm (in Russian: …)", or "about 40 blocks north of the Town Hall" when no building is close. */
    private static String place(IColony colony, IBuilding building, BlockPos at) {
        if (building == null) {
            return "in the colony";
        }
        String name = ItemNames.named(Component.translatable(building.getBuildingType().getTranslationKey()));
        BlockPos from = building.getPosition();
        return from.distSqr(at) <= BY * BY ? "by the " + name
                : Compass.way(at.getX() - from.getX(), at.getZ() - from.getZ()) + " of the " + name;
    }

    private static IBuilding nearestBuilding(IColony colony, BlockPos at) {
        IBuilding nearest = null;
        double best = Double.MAX_VALUE;
        for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
            double distance = building.getPosition().distSqr(at);
            if (distance < best) {
                best = distance;
                nearest = building;
            }
        }
        return nearest;
    }
}

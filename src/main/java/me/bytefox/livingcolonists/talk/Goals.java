package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.bytefox.livingcolonists.perception.ItemNames;
import net.minecraft.network.chat.Component;

/**
 * Each colonist's personal goal: one thing they hope for that code can tell came true (their home
 * or workplace a level up, the colony getting a hospital or a tavern). It never goes into ordinary
 * replies, or the colonist would bring it up in every line; it comes up only as something they
 * write about now and then, and once, gladly, when it comes true. Server thread only.
 */
final class Goals {
    private static final long DAY = 24000;
    /** Buildings a colonist may hope the colony gets, by MineColonies' registry name. */
    private static final Map<String, String> PLACES = new LinkedHashMap<>();

    static {
        PLACES.put("hospital", "a hospital");
        PLACES.put("tavern", "a tavern");
        PLACES.put("cook", "a restaurant");
        PLACES.put("library", "a library");
        PLACES.put("university", "a university");
        PLACES.put("school", "a school");
    }

    private Goals() {
    }

    /**
     * The colonist's goal now, marked as come true when it did. A goal stays until it came true and
     * was told; a day later the colonist picks a new one. Empty if there is nothing to hope for.
     */
    static Optional<InitiativeData.Goal> current(InitiativeData data, ICitizenData citizen, Random random) {
        String key = citizen.getColony().getID() + ":" + citizen.getId();
        long now = citizen.getColony().getWorld().getGameTime();
        InitiativeData.Goal goal = data.goals.get(key);
        if (goal != null && goal.doneAt() == 0 && cameTrue(goal, citizen)) {
            goal = goal.cameTrue(now);
            data.goals.put(key, goal);
            data.setDirty();
        }
        if (goal == null || goal.told() && now - goal.doneAt() > DAY) {
            goal = pick(citizen, random, now);
            if (goal == null) {
                data.goals.remove(key);
            } else {
                data.goals.put(key, goal);
            }
            data.setDirty();
        }
        return Optional.ofNullable(goal);
    }

    static void told(InitiativeData data, ICitizenData citizen) {
        String key = citizen.getColony().getID() + ":" + citizen.getId();
        InitiativeData.Goal goal = data.goals.get(key);
        if (goal != null) {
            data.goals.put(key, goal.toldNow());
            data.setDirty();
        }
    }

    /** What the colonist hopes for, in words. */
    static String hope(InitiativeData.Goal goal, ICitizenData citizen) {
        return InitiativePolicy.goalHope(goal.kind(), goal.level(), place(goal, citizen));
    }

    static String done(InitiativeData.Goal goal, ICitizenData citizen) {
        return InitiativePolicy.goalDone(goal.kind(), goal.level(), place(goal, citizen));
    }

    private static String place(InitiativeData.Goal goal, ICitizenData citizen) {
        if (goal.kind().startsWith("place:")) {
            return PLACES.getOrDefault(goal.kind().substring("place:".length()), "a new building");
        }
        IBuilding work = citizen.getWorkBuilding();
        return work == null ? "workplace" : ItemNames.named(Component.translatable(work.getBuildingType().getTranslationKey()));
    }

    private static boolean cameTrue(InitiativeData.Goal goal, ICitizenData citizen) {
        return switch (goal.kind().contains(":") ? "place" : goal.kind()) {
            case "home" -> citizen.getHomeBuilding() != null && citizen.getHomeBuilding().getBuildingLevel() >= goal.level();
            case "work" -> citizen.getWorkBuilding() != null && citizen.getWorkBuilding().getBuildingLevel() >= goal.level();
            default -> has(citizen.getColony(), goal.kind().substring("place:".length()));
        };
    }

    private static InitiativeData.Goal pick(ICitizenData citizen, Random random, long now) {
        if (citizen.isChild()) {
            return null;
        }
        List<InitiativeData.Goal> options = new ArrayList<>();
        IBuilding home = citizen.getHomeBuilding();
        if (home != null && home.getBuildingLevel() < home.getMaxBuildingLevel()) {
            options.add(new InitiativeData.Goal("home", home.getBuildingLevel() + 1, now, 0, false));
        }
        IBuilding work = citizen.getWorkBuilding();
        if (work != null && work.getBuildingLevel() > 0 && work.getBuildingLevel() < work.getMaxBuildingLevel()) {
            options.add(new InitiativeData.Goal("work", work.getBuildingLevel() + 1, now, 0, false));
        }
        PLACES.keySet().stream().filter(type -> !has(citizen.getColony(), type))
                .forEach(type -> options.add(new InitiativeData.Goal("place:" + type, 0, now, 0, false)));
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    private static boolean has(IColony colony, String type) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .anyMatch(building -> building.getBuildingLevel() > 0 && building.getBuildingType() != null
                        && building.getBuildingType().getRegistryName().getPath().equals(type));
    }
}

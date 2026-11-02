package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Friendship levels with players (#67), from {@code config/livingcolonists/content/friendship.json}: one scale of
 * rapport for the notebook, the model's tone and milestones, and how a friendship cools without contact.
 */
public final class FriendshipLevels {
    /**
     * @param from the lowest rapport of the level
     * @param rank its place on the scale, 0 the coldest
     */
    public record Level(String id, int from, int rank) {
    }

    private static volatile List<Level> levels = List.of(new Level("neutral", -100, 0));
    private static volatile Map<String, String> milestones = Map.of();
    private static volatile int graceDays = 3;
    private static volatile int perDay = 1;
    private static volatile int warmFloor = 20;
    private static volatile int coldFloor = -20;

    private FriendshipLevels() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Level> read = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("levels")) {
            JsonObject level = element.getAsJsonObject();
            read.add(new Level(level.get("id").getAsString(), level.get("from").getAsInt(), read.size()));
        }
        Map<String, String> said = new HashMap<>();
        root.getAsJsonObject("milestones").entrySet().forEach(entry -> said.put(entry.getKey(), entry.getValue().getAsString()));
        JsonObject cooling = root.getAsJsonObject("cooling");
        graceDays = cooling.get("graceDays").getAsInt();
        perDay = cooling.get("perDay").getAsInt();
        warmFloor = cooling.get("warmFloor").getAsInt();
        coldFloor = cooling.get("coldFloor").getAsInt();
        levels = List.copyOf(read);
        milestones = Map.copyOf(said);
    }

    public static Level level(int rapport) {
        Level found = levels.get(0);
        for (Level level : levels) {
            if (rapport >= level.from()) {
                found = level;
            }
        }
        return found;
    }

    public static Optional<Level> byId(String id) {
        return levels.stream().filter(level -> level.id().equals(id)).findFirst();
    }

    /** What the colonist tells the player the first time they reach the level. */
    public static Optional<String> milestone(String level) {
        return Optional.ofNullable(milestones.get(level));
    }

    /** Rapport after a calendar day {@code daysSinceContact} days after the last contact. */
    public static int cooled(int rapport, int daysSinceContact) {
        if (daysSinceContact <= graceDays) {
            return rapport;
        }
        if (rapport > warmFloor) {
            return Math.max(warmFloor, rapport - perDay);
        }
        if (rapport < coldFloor) {
            return Math.min(coldFloor, rapport + perDay);
        }
        return rapport;
    }
}

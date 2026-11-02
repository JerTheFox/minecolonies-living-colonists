package me.bytefox.livingcolonists.mood;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What stirs the colony's mood (#65), from {@code config/livingcolonists/content/moods.json}: for each occasion (a death,
 * a raid, a birth) the mood, how strong (1-5), how long it takes to fade, how soon the same occasion may stir it again,
 * and how much colonists want to talk about it. A server owner tunes it there, without a rebuild.
 */
public final class MoodTable {
    /**
     * @param interest how much longer a talk about it runs: 0 for a passing remark, 3 for a raid or a death
     */
    public record Row(Mood mood, double strength, long fadeTicks, long cooldownTicks, int interest) {
    }

    private static final long MINUTE = 60 * 20;
    private static final Row NONE = new Row(Mood.CALM, 0, 1, 0, 0);

    private static volatile double calm = 1.5;
    private static volatile Map<String, Row> rows = Map.of();
    private static volatile Map<Mood, String> tones = Map.of();
    private static volatile Map<Mood, String> close = Map.of();

    private MoodTable() {
    }

    /** Takes the table from the file's text. */
    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Row> read = new HashMap<>();
        root.getAsJsonObject("occasions").entrySet().forEach(entry -> {
            JsonObject row = entry.getValue().getAsJsonObject();
            read.put(entry.getKey(), new Row(Mood.valueOf(row.get("mood").getAsString()), row.get("strength").getAsDouble(),
                    Math.max(1, Math.round(row.get("fadeMinutes").getAsDouble() * MINUTE)),
                    Math.round(row.get("cooldownMinutes").getAsDouble() * MINUTE), row.get("interest").getAsInt()));
        });
        rows = Map.copyOf(read);
        tones = moods(root.getAsJsonObject("tones"));
        close = moods(root.getAsJsonObject("close"));
        calm = root.has("calm") ? root.get("calm").getAsDouble() : 1.5;
    }

    private static Map<Mood, String> moods(JsonObject object) {
        Map<Mood, String> out = new EnumMap<>(Mood.class);
        if (object != null) {
            object.entrySet().forEach(entry -> out.put(Mood.valueOf(entry.getKey()), entry.getValue().getAsString()));
        }
        return out;
    }

    /** The row for an occasion; an unknown one stirs nothing. */
    public static Row row(String occasion) {
        return rows.getOrDefault(occasion, NONE);
    }

    /** Below this a mood isn't felt: the colony is calm. */
    public static double calm() {
        return calm;
    }

    /** How the model is told of the colony's mood, with {@code {cause}} in it. */
    public static String tone(Mood mood) {
        return tones.getOrDefault(mood, "");
    }

    /** What is added for those it touches closely (a dead colonist's family). */
    public static String closeLine(Mood mood) {
        return close.getOrDefault(mood, "");
    }
}

package me.bytefox.livingcolonists.talk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Which advancements colonists hear about (#39): a player's big deeds, told later as hearsay, and the
 * colony's milestones, told as colony news. Server owners add their own with {@code extraAdvancements}.
 */
public final class Rumours {
    /** A player's deeds by advancement id, as colonists would put it after "word has it they…". */
    private static volatile Map<String, String> deeds = Map.of();
    /** The colony's milestones, as news for the colony chat. */
    private static volatile Map<String, String> colony = Map.of();

    private Rumours() {
    }

    /** Takes {@code content/rumours.json}: {@code deeds} and {@code colony}, each advancement id to words. */
    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        deeds = read(root, "deeds");
        colony = read(root, "colony");
    }

    private static Map<String, String> read(JsonObject root, String section) {
        Map<String, String> out = new LinkedHashMap<>();
        if (root.has(section)) {
            root.getAsJsonObject(section).entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return Map.copyOf(out);
    }

    /** {@code extraAdvancements}: "id" or "id=what colonists say", by id; an empty value means the title. */
    static Map<String, String> extra(List<? extends String> entries) {
        Map<String, String> extra = new LinkedHashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            String id = (eq < 0 ? entry : entry.substring(0, eq)).strip();
            if (!id.isEmpty()) {
                extra.put(id, eq < 0 ? "" : entry.substring(eq + 1).strip());
            }
        }
        return extra;
    }

    /** What colonists would hear the player did, or null if this advancement is no news. */
    static String deed(String id, Map<String, String> extra, String title) {
        String deed = deeds.get(id);
        if (deed != null) {
            return deed;
        }
        String words = extra.get(id);
        if (words == null) {
            return null;
        }
        return words.isEmpty() ? "earned \"" + title + "\"" : words;
    }

    /** The colony news this advancement means, or null. */
    static String colonyNews(String id) {
        return colony.get(id);
    }
}

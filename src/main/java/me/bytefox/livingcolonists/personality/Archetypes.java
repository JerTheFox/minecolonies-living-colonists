package me.bytefox.livingcolonists.personality;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Reads archetype files. */
public final class Archetypes {
    private Archetypes() {
    }

    /**
     * @param id the file name without {@code .json}
     * @throws IllegalArgumentException if the file isn't valid, with the reason
     */
    public static Archetype parse(String id, String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("not a JSON object", e);
        }
        String prompt = string(root, "prompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("\"prompt\" is missing");
        }
        String name = string(root, "name");
        Map<String, Double> skills = new HashMap<>();
        JsonElement skillsElement = root.get("skills");
        if (skillsElement != null && skillsElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : skillsElement.getAsJsonObject().entrySet()) {
                skills.put(entry.getKey(), entry.getValue().getAsDouble());
            }
        }
        return new Archetype(id, name.isBlank() ? id : name, prompt.strip(),
                Math.max(0, number(root, "weight", 1)), Math.clamp(number(root, "talkativeness", 0.5), 0, 1), skills);
    }

    private static String string(JsonObject root, String key) {
        JsonElement element = root.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static double number(JsonObject root, String key, double fallback) {
        JsonElement element = root.get(key);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsDouble();
    }
}

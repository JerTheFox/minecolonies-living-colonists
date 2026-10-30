package me.bytefox.livingcolonists.errands;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.bytefox.livingcolonists.llm.ToolSpec;

/**
 * How the colony works, from a handbook bundled with the mod: short articles written for MineColonies
 * 1.1.1405 (food, storage, requests, building...). Left alone the model guesses how MineColonies works
 * and sounds sure of it; with this it retells what is true, in the colonist's own words.
 */
public final class Handbook implements Errand {
    private static final Map<String, String> ARTICLES = load();

    @Override
    public String name() {
        return "colony_how";
    }

    @Override
    public ToolSpec spec() {
        return Errands.spec(name(), "How things work in the colony, from the colony's handbook: use it when someone asks how "
                + "something works (where colonists get food, what couriers carry, how building or hiring works) instead "
                + "of guessing.", "topic", "One of: " + String.join(", ", ARTICLES.keySet()));
    }

    @Override
    public boolean isOrder() {
        return false;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String topic = Errand.string(arguments, "topic").toLowerCase(Locale.ROOT).strip();
        String article = ARTICLES.get(topic);
        if (article == null) {
            article = ARTICLES.entrySet().stream().filter(e -> topic.contains(e.getKey()) || e.getKey().contains(topic))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (article == null) {
            return new Result.Answer(Errand.error("No such topic. Topics: " + String.join(", ", ARTICLES.keySet())));
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("how_it_works", article);
        reply.addProperty("info", "This is how it works; say it plainly in your own words, only the part that answers "
                + "the question.");
        return new Result.Answer(reply);
    }

    private static Map<String, String> load() {
        Map<String, String> articles = new LinkedHashMap<>();
        try (InputStream in = Handbook.class.getResourceAsStream("/defaults/handbook.json")) {
            if (in != null) {
                JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                        .entrySet().forEach(e -> articles.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("bundled handbook unreadable", e);
        }
        return articles;
    }
}

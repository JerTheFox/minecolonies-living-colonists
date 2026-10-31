package me.bytefox.livingcolonists.errands;

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
    /** Articles by topic, from {@code content/handbook.json}. */
    private static volatile Map<String, String> articles = Map.of();

    @Override
    public String name() {
        return "colony_how";
    }

    @Override
    public ToolSpec spec() {
        return Errands.spec(name(), "How things work in the colony, from the colony's handbook: use it when someone asks how "
                + "something works (where colonists get food, what couriers carry, how building or hiring works) instead "
                + "of guessing.", "topic", "One of: " + String.join(", ", articles.keySet()));
    }

    @Override
    public boolean isOrder() {
        return false;
    }

    @Override
    public Result run(Context context, JsonObject arguments) {
        String topic = Errand.string(arguments, "topic").toLowerCase(Locale.ROOT).strip();
        String article = articles.get(topic);
        if (article == null) {
            article = articles.entrySet().stream().filter(e -> topic.contains(e.getKey()) || e.getKey().contains(topic))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (article == null) {
            return new Result.Answer(Errand.error("No such topic. Topics: " + String.join(", ", articles.keySet())));
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("how_it_works", article);
        reply.addProperty("info", "This is how it works; say it plainly in your own words, only the part that answers "
                + "the question.");
        return new Result.Answer(reply);
    }

    /** Takes {@code content/handbook.json}: a topic to its article. */
    public static void use(String json) {
        Map<String, String> read = new LinkedHashMap<>();
        JsonParser.parseString(json).getAsJsonObject().entrySet()
                .forEach(e -> read.put(e.getKey(), e.getValue().getAsString()));
        articles = read;
    }
}

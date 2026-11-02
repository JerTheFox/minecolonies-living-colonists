package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The shape of a talk colonists start among themselves (#65), from {@code config/livingcolonists/content/talk.json}: an
 * ordinary chat most of the time, now and then teasing, a complaint, gossip, an argument, a yarn, a plan, a memory,
 * advice, a dream, rarely a secret or a confession, by weighted dice. About one talk in four comes to an ending (a
 * decision, a laugh); the rest just fade. The text is for the model, which reads it, so it stays as it is.
 */
public final class TalkShapes {
    /**
     * @param opening what the one who starts the talk is told
     * @param others  what everyone after them is told
     */
    public record Shape(String key, double weight, String opening, String others) {
    }

    private static final Shape ORDINARY = new Shape("ordinary", 1, "", "");

    private static volatile List<Shape> shapes = List.of();
    private static volatile double endingChance;
    private static volatile List<String> endings = List.of();

    private TalkShapes() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Shape> read = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("shapes")) {
            JsonObject shape = element.getAsJsonObject();
            read.add(new Shape(shape.get("key").getAsString(), Math.max(0, shape.get("weight").getAsDouble()),
                    shape.get("opening").getAsString(), shape.get("others").getAsString()));
        }
        shapes = List.copyOf(read);
        JsonObject ending = root.getAsJsonObject("ending");
        endingChance = ending == null ? 0 : ending.get("chance").getAsDouble();
        List<String> texts = new ArrayList<>();
        if (ending != null) {
            ending.getAsJsonArray("texts").forEach(text -> texts.add(text.getAsString()));
        }
        endings = List.copyOf(texts);
    }

    public static List<Shape> all() {
        return shapes;
    }

    /** A shape by its weight; an ordinary chat when the table is empty. */
    public static Shape pick(Random random) {
        List<Shape> table = shapes;
        double total = table.stream().mapToDouble(Shape::weight).sum();
        if (total <= 0) {
            return ORDINARY;
        }
        double roll = random.nextDouble() * total;
        for (Shape shape : table) {
            roll -= shape.weight();
            if (roll < 0) {
                return shape;
            }
        }
        return table.get(table.size() - 1);
    }

    /** How this talk ends, or null for most talks, which just fade. */
    public static String ending(Random random) {
        List<String> texts = endings;
        if (texts.isEmpty() || random.nextDouble() >= endingChance) {
            return null;
        }
        return texts.get(random.nextInt(texts.size()));
    }
}

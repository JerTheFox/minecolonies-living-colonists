package me.bytefox.livingcolonists.friendship;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;

/** How often friends write and what they send (#67), from {@code config/livingcolonists/content/letters.json}. */
public final class LetterTable {
    private static volatile Map<String, Double> chance = Map.of();
    private static volatile Map<String, Integer> variants = Map.of();
    private static volatile Map<String, List<String>> gifts = Map.of();
    private static volatile List<String> keepsakes = List.of();
    private static volatile int weekDays = 7;
    private static volatile int perPlayerPerDay = 2;

    private LetterTable() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Double> odds = new HashMap<>();
        root.getAsJsonObject("chance").entrySet().forEach(e -> odds.put(e.getKey(), e.getValue().getAsDouble()));
        Map<String, Integer> texts = new HashMap<>();
        root.getAsJsonObject("variants").entrySet().forEach(e -> texts.put(e.getKey(), Math.max(1, e.getValue().getAsInt())));
        Map<String, List<String>> byJob = new HashMap<>();
        root.getAsJsonObject("gifts").entrySet().forEach(e -> byJob.put(e.getKey(), strings(e.getValue())));
        chance = Map.copyOf(odds);
        variants = Map.copyOf(texts);
        gifts = Map.copyOf(byJob);
        keepsakes = strings(root.get("keepsakes"));
        weekDays = root.get("weekDays").getAsInt();
        perPlayerPerDay = root.get("perPlayerPerDay").getAsInt();
    }

    private static List<String> strings(JsonElement array) {
        return array.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList();
    }

    /** The chance a colonist at this friendship level writes on a calendar day; 0 below friend. */
    public static double chance(String level) {
        return chance.getOrDefault(level, 0.0);
    }

    /** The kind of letter a colonist at this level writes: friend, close or best; null below friend. */
    public static String kind(String level) {
        return switch (level) {
            case "friend", "good" -> "friend";
            case "close" -> "close";
            case "best" -> "best";
            default -> null;
        };
    }

    public static int variants(String kind) {
        return variants.getOrDefault(kind, 1);
    }

    /** A small gift from the job's list, else from everyone's; made from nothing. */
    public static ItemStack gift(String job, Random random) {
        return pick(gifts.getOrDefault(job, gifts.getOrDefault("*", List.of())), random);
    }

    public static ItemStack keepsake(Random random) {
        return pick(keepsakes, random);
    }

    private static ItemStack pick(List<String> list, Random random) {
        return list.isEmpty() ? ItemStack.EMPTY : ItemSpecs.of(list.get(random.nextInt(list.size())));
    }

    /** Calendar days between two letters from the same colonist to the same player. */
    public static int weekDays() {
        return weekDays;
    }

    public static int perPlayerPerDay() {
        return perPlayerPerDay;
    }
}

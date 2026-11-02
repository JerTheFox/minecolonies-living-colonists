package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;

/** Who helps a hurt player and how, by job (#67), from {@code config/livingcolonists/content/help.json}. */
public final class HelpTable {
    /**
     * @param kind  heal, give, repair, fight, wall, torches or call
     * @param items what is handed over, for give and torches
     */
    public record Action(String kind, List<String> items) {
        /** One of the items: a cook hands over one dish, not the whole menu. */
        public List<ItemStack> items(Random random) {
            if (items.isEmpty()) {
                return List.of();
            }
            ItemStack stack = ItemSpecs.of(items.get(random.nextInt(items.size())));
            return stack.isEmpty() ? List.of() : List.of(stack);
        }
    }

    private static final Action CALL = new Action("call", List.of());

    private static volatile Map<String, Action> jobs = Map.of();
    private static volatile Action fallback = CALL;
    private static volatile List<String> order = List.of("fight", "heal", "wall", "give", "repair", "torches", "call");
    private static volatile double healthShare = 0.4;
    private static volatile int radius = 16;
    private static volatile long cooldownTicks = 5 * 60 * 20;
    private static volatile long playerCooldownTicks = 30 * 20;
    private static volatile long wallTicks = 60 * 20;
    private static volatile String wallBlock = "minecraft:cobblestone";

    private HelpTable() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Action> byJob = new HashMap<>();
        root.getAsJsonObject("jobs").entrySet().forEach(e -> byJob.put(e.getKey(), action(e.getValue().getAsJsonObject())));
        List<String> ranks = new ArrayList<>();
        root.getAsJsonArray("order").forEach(e -> ranks.add(e.getAsString()));
        jobs = Map.copyOf(byJob);
        fallback = root.has("default") ? action(root.getAsJsonObject("default")) : CALL;
        order = List.copyOf(ranks);
        healthShare = root.get("healthShare").getAsDouble();
        radius = root.get("radius").getAsInt();
        cooldownTicks = Math.round(root.get("cooldownMinutes").getAsDouble() * 60 * 20);
        playerCooldownTicks = Math.round(root.get("playerCooldownSeconds").getAsDouble() * 20);
        wallTicks = Math.round(root.get("wallSeconds").getAsDouble() * 20);
        wallBlock = root.get("wallBlock").getAsString();
    }

    private static Action action(JsonObject object) {
        List<String> items = object.has("items")
                ? object.getAsJsonArray("items").asList().stream().map(JsonElement::getAsString).toList() : List.of();
        return new Action(object.get("do").getAsString(), items);
    }

    public static Action action(String job) {
        return jobs.getOrDefault(job, fallback);
    }

    /** Where a kind of help stands among others: lower comes first. */
    public static int rank(String kind) {
        int index = order.indexOf(kind);
        return index < 0 ? order.size() : index;
    }

    public static double healthShare() {
        return healthShare;
    }

    public static int radius() {
        return radius;
    }

    public static long cooldownTicks() {
        return cooldownTicks;
    }

    public static long playerCooldownTicks() {
        return playerCooldownTicks;
    }

    public static long wallTicks() {
        return wallTicks;
    }

    public static String wallBlock() {
        return wallBlock;
    }
}

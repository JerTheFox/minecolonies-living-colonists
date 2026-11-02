package me.bytefox.livingcolonists.friendship;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * What colonists think of gifts (#67), from {@code config/livingcolonists/content/gifts.json}: a love of their own picked
 * for each colonist from a list, then their job's tastes, then everyone's; the character bends it a little (an optimist
 * takes a dull gift kindly, a grumbler isn't pleased by much). Code decides it, not the model.
 */
public final class GiftTastes {
    /** One group of rules (everyone's, or a job's): entries for each taste. */
    private record Rules(Map<Taste, List<String>> entries) {
        Taste match(String id, ItemStack stack) {
            // A specific dislike beats a general like: rotten flesh is food, but nobody likes it.
            for (Taste taste : List.of(Taste.LOVE, Taste.HATE, Taste.DISLIKE, Taste.LIKE)) {
                for (String entry : entries.getOrDefault(taste, List.of())) {
                    if (fits(entry, id, stack)) {
                        return taste;
                    }
                }
            }
            return null;
        }
    }

    private static volatile Rules everyone = new Rules(Map.of());
    private static volatile Map<String, Rules> jobs = Map.of();
    private static volatile Map<String, String> bends = Map.of();
    private static volatile List<String> oddLoves = List.of();

    private GiftTastes() {
    }

    public static void use(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        everyone = rules(root.getAsJsonObject("everyone"));
        Map<String, Rules> byJob = new HashMap<>();
        root.getAsJsonObject("jobs").entrySet().forEach(entry -> byJob.put(entry.getKey(), rules(entry.getValue().getAsJsonObject())));
        jobs = Map.copyOf(byJob);
        Map<String, String> read = new HashMap<>();
        root.getAsJsonObject("archetypes").entrySet().stream().filter(entry -> !entry.getKey().startsWith("_"))
                .forEach(entry -> read.put(entry.getKey(), entry.getValue().getAsString()));
        bends = Map.copyOf(read);
        oddLoves = root.getAsJsonArray("oddLoves").asList().stream().map(JsonElement::getAsString).toList();
    }

    private static Rules rules(JsonObject object) {
        Map<Taste, List<String>> entries = new EnumMap<>(Taste.class);
        for (Taste taste : Taste.values()) {
            String key = taste.name().toLowerCase(java.util.Locale.ROOT);
            if (object != null && object.has(key)) {
                entries.put(taste, object.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList());
            }
        }
        return new Rules(entries);
    }

    /** Whether the item fits any of the entries (an item id, "food" or "~word"). */
    public static boolean matches(List<String> entries, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return entries.stream().anyMatch(entry -> fits(entry, id, stack));
    }

    private static boolean fits(String entry, String id, ItemStack stack) {
        if (entry.equals("food")) {
            return stack.has(DataComponents.FOOD);
        }
        if (entry.startsWith("~")) {
            return id.contains(entry.substring(1));
        }
        return entry.equals(id);
    }

    /**
     * @param job        the colonist's job, as MineColonies names it ("cook"), or "" for none
     * @param archetype  the colonist's character archetype ("grumbler")
     * @param citizenKey {@code colony:citizen}, which picks their odd love
     */
    public static Taste taste(String job, String archetype, String citizenKey, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!oddLoves.isEmpty() && oddLove(citizenKey).getItem() == stack.getItem()) {
            return Taste.LOVE;
        }
        Rules forJob = jobs.get(job);
        Taste taste = forJob == null ? null : forJob.match(id, stack);
        if (taste == null) {
            taste = everyone.match(id, stack);
        }
        if (taste == null) {
            taste = Taste.NEUTRAL;
        }
        return switch (bends.getOrDefault(archetype, "")) {
            case "kinder" -> taste == Taste.DISLIKE ? Taste.NEUTRAL : taste == Taste.LIKE ? Taste.LOVE : taste;
            case "harder" -> taste == Taste.LIKE ? Taste.NEUTRAL : taste;
            default -> taste;
        };
    }

    /** A gift of several things counts by the best of them; with nothing liked, by the worst. */
    public static Taste gift(String job, String archetype, String citizenKey, List<ItemStack> stacks) {
        List<Taste> tastes = stacks.stream().filter(stack -> !stack.isEmpty()).map(stack -> taste(job, archetype, citizenKey, stack)).toList();
        if (tastes.contains(Taste.LOVE)) {
            return Taste.LOVE;
        }
        if (tastes.contains(Taste.LIKE)) {
            return Taste.LIKE;
        }
        if (tastes.contains(Taste.HATE)) {
            return Taste.HATE;
        }
        return tastes.contains(Taste.DISLIKE) ? Taste.DISLIKE : Taste.NEUTRAL;
    }

    /** The items named in the job's and everyone's loves and likes (not "food" or "~word"), for the talk menu (#68). */
    public static List<String> namedItems(String job) {
        List<String> out = new java.util.ArrayList<>();
        Rules forJob = jobs.get(job);
        for (Rules rules : forJob == null ? List.of(everyone) : List.of(forJob, everyone)) {
            for (Taste taste : List.of(Taste.LOVE, Taste.LIKE)) {
                rules.entries().getOrDefault(taste, List.of()).stream()
                        .filter(entry -> entry.contains(":") && !out.contains(entry)).forEach(out::add);
            }
        }
        return out;
    }

    /** The one thing this colonist loves more than anyone would guess. */
    public static ItemStack oddLove(String citizenKey) {
        List<String> list = oddLoves;
        if (list.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String id = list.get(Math.floorMod(citizenKey.hashCode(), list.size()));
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    }
}

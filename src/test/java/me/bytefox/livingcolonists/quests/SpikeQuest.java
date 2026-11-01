package me.bytefox.livingcolonists.quests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A hand-written quest from the spike of #60, kept for the tests: a colonist asks for three loaves of bread
 * and gives two emeralds.
 * Texts are written out, not translation keys: a key the client doesn't know is shown as it is.
 */
final class SpikeQuest {
    private SpikeQuest() {
    }

    static JsonObject json(String giver) {
        JsonObject quest = new JsonObject();
        quest.addProperty("name", giver + " needs bread");
        quest.addProperty("max-occurrences", 1);
        quest.add("parents", new JsonArray());
        JsonArray triggers = new JsonArray();
        // Only an unlock starts it on its own, and nothing unlocks it: we offer it ourselves.
        JsonObject unlock = new JsonObject();
        unlock.addProperty("type", "minecolonies:unlock");
        triggers.add(unlock);
        quest.add("triggers", triggers);

        JsonArray objectives = new JsonArray();
        objectives.add(dialogue("We're short of bread for the table tonight. Could you bring me three loaves?",
                option("Sure, I'll bring them.", advance(1)), option("Not now.", type("minecolonies:cancel")), null));
        JsonObject delivery = type("minecolonies:delivery");
        JsonObject details = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("id", "minecraft:bread");
        item.addProperty("count", 3);
        details.add("item", item);
        details.addProperty("next-objective", 2);
        details.addProperty("target", 0);
        delivery.add("details", details);
        objectives.add(delivery);
        JsonArray unlocks = new JsonArray();
        unlocks.add(0);
        objectives.add(dialogue("Thank you! Here, take these for your trouble.", option("Glad to help.", advance(3)), null, unlocks));
        quest.add("objectives", objectives);

        JsonArray rewards = new JsonArray();
        JsonObject reward = type("minecolonies:item");
        JsonObject rewardDetails = new JsonObject();
        JsonObject emeralds = new JsonObject();
        emeralds.addProperty("id", "minecraft:emerald");
        emeralds.addProperty("count", 2);
        rewardDetails.add("item", emeralds);
        reward.add("details", rewardDetails);
        rewards.add(reward);
        quest.add("rewards", rewards);
        return quest;
    }

    private static JsonObject dialogue(String text, JsonObject first, JsonObject second, JsonArray unlocks) {
        JsonObject dialogue = type("minecolonies:dialogue");
        dialogue.addProperty("text", text);
        dialogue.addProperty("target", 0);
        JsonArray options = new JsonArray();
        options.add(first);
        if (second != null) {
            options.add(second);
        }
        dialogue.add("options", options);
        if (unlocks != null) {
            dialogue.add("unlocks-rewards", unlocks);
        }
        return dialogue;
    }

    private static JsonObject option(String answer, JsonObject result) {
        JsonObject option = new JsonObject();
        option.addProperty("answer", answer);
        option.add("result", result);
        return option;
    }

    private static JsonObject advance(int to) {
        JsonObject result = type("minecolonies:advanceobjective");
        result.addProperty("go-to", to);
        return result;
    }

    private static JsonObject type(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        return object;
    }
}

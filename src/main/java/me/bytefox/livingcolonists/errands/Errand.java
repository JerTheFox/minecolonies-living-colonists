package me.bytefox.livingcolonists.errands;

import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Something a colonist can look up or do for a player, offered to the model as a function. A look-up
 * answers at once. An order only prepares the work: code checks who asks and that the player really
 * asked, then waits for their yes before anything changes in the world.
 */
public interface Errand {
    String name();

    ToolSpec spec();

    /** Orders change the world; look-ups only read it. */
    boolean isOrder();

    /** Runs on the server thread; never throws for bad arguments, it answers with an error instead. */
    Result run(Context context, JsonObject arguments);

    /**
     * @param player      the player the colonist answers; null when no player asked
     * @param playerLines what that player wrote lately, newest last
     */
    record Context(ICitizenData citizen, IColony colony, ServerPlayer player, List<String> playerLines) {
    }

    sealed interface Result {
        /** The answer for the model. */
        record Answer(JsonObject reply) implements Result {
        }

        /** An order ready to be carried out once the player says yes. */
        record Proposal(Order order) implements Result {
        }
    }

    /**
     * @param summary  what will be done, in English, for the model and the order log
     * @param question the same for the player, translated, asked with yes and no buttons
     * @param execute  carries the order out on the server thread, for the player who said yes
     */
    record Order(String tool, String summary, Component question, Function<ServerPlayer, Outcome> execute) {
    }

    /** @param undo takes the order back, or null if it can't be */
    record Outcome(boolean done, Component message, Runnable undo) {
    }

    static JsonObject error(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", message);
        return result;
    }

    /** Nothing fits the words; a name in another language than English gets one more try. */
    static JsonObject notFound(String what, String words) {
        boolean english = words.chars().filter(Character::isLetter).allMatch(c -> c < 128);
        return error("There is no " + what + " called \"" + words + "\"." + (english ? ""
                : " Names are looked up in English: call again with its English name."));
    }

    static String string(JsonObject arguments, String key) {
        return arguments.has(key) && arguments.get(key).isJsonPrimitive() ? arguments.get(key).getAsString().strip() : "";
    }
}

package me.bytefox.livingcolonists.quests;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.quests.IQuestInstance;
import com.minecolonies.api.quests.IQuestManager;
import com.minecolonies.api.quests.IQuestTemplate;
import com.minecolonies.api.quests.ITriggerReturnData;
import com.minecolonies.core.datalistener.QuestJsonListener;
import com.minecolonies.core.quests.QuestInstance;
import com.minecolonies.core.quests.triggers.CitizenTriggerReturnData;
import me.bytefox.livingcolonists.ModLog;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Quests of our own in MineColonies' quest engine (#60, spike): a template made at runtime is added where the
 * engine keeps the datapack's quests, on the server and in what it sends to clients, so the "!" over a colonist,
 * the dialogue window, hand-ins and rewards are MineColonies' own. A quest is then offered by a colonist of our
 * choosing. The engine's classes used here are in {@code core}, not {@code api}: a newer MineColonies may break
 * this, so every call is guarded and a failure only means no quests. Server thread only.
 */
public final class QuestBridge {
    /** Our templates, re-added whenever MineColonies reloads its own (start, {@code /reload}). */
    private static final Map<ResourceLocation, JsonObject> ours = new LinkedHashMap<>();

    private QuestBridge() {
    }

    /** Registers a template on the server and sends the quest list again to everyone online; whether it took. */
    public static boolean register(MinecraftServer server, ResourceLocation id, JsonObject json) {
        try {
            IQuestTemplate template = QuestJsonListener.loadDataFromJson(server.registryAccess(), id, json);
            IQuestManager.GLOBAL_SERVER_QUESTS.put(id, template);
            synced().put(id, json);
            ours.put(id, json);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                QuestJsonListener.sendGlobalQuestPackets(player);
            }
            return true;
        } catch (Exception | LinkageError e) {
            ModLog.warn("Could not register the quest {}", id, e);
            return false;
        }
    }

    /** Puts our templates back after MineColonies loaded its own, before they go to the players. */
    public static void restore(MinecraftServer server) {
        if (!ours.isEmpty()) {
            ModLog.info("Putting back {} quests of our own after MineColonies loaded its quests", ours.size());
        }
        ours.forEach((id, json) -> {
            try {
                IQuestManager.GLOBAL_SERVER_QUESTS.put(id, QuestJsonListener.loadDataFromJson(server.registryAccess(), id, json));
                synced().put(id, json);
            } catch (Exception | LinkageError e) {
                ModLog.warn("Could not restore the quest {}", id, e);
            }
        });
    }

    /**
     * The colonist {@code giver} offers the quest: the "!" shows over them. {@code others} are the quest's other
     * participants, by their order in the template ({@code target} 1, 2…). Null if the engine refused.
     */
    public static IQuestInstance offer(IColony colony, ResourceLocation id, ICitizenData giver, List<ICitizenData> others) {
        try {
            List<ITriggerReturnData<?>> found = new ArrayList<>();
            found.add(new CitizenTriggerReturnData(giver));
            others.forEach(other -> found.add(new CitizenTriggerReturnData(other)));
            IQuestInstance quest = new QuestInstance(id, colony, found);
            colony.getQuestManager().injectAvailableQuest(quest);
            return quest;
        } catch (Exception | LinkageError e) {
            ModLog.warn("Could not offer the quest {} in colony {}", id, colony.getName(), e);
            return null;
        }
    }

    /** What MineColonies sends to clients as its quests, by id: the datapack's JSON, ours added. */
    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, JsonElement> synced() throws ReflectiveOperationException {
        Field field = QuestJsonListener.class.getDeclaredField("globalJsonElementMap");
        field.setAccessible(true);
        Map<ResourceLocation, JsonElement> map = (Map<ResourceLocation, JsonElement>) field.get(null);
        if (map == null) {
            map = new LinkedHashMap<>();
            field.set(null, map);
        }
        try {
            map.put(null, null);
            map.remove(null);
        } catch (UnsupportedOperationException | NullPointerException e) {
            // An unmodifiable map from the reload: replaced by a copy we can add to.
            map = new LinkedHashMap<>(map);
            field.set(null, map);
        }
        return map;
    }
}

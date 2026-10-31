package me.bytefox.livingcolonists.errands;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.data.DataVersions;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Enemies each guard has killed, saved with the world, for the guards' leaderboard. Idea from
 * Colonist Errands ({@code GuardScore}, Lovkar), counted anew and simpler: kills only.
 */
public final class GuardTally {
    private static final int SHOWN = 10;

    /** The saved counts, by {@code colonyId:citizenId}. */
    public static final class Data extends SavedData {
        public static final String NAME = "livingcolonists_guards";
        /** Format of the saved file; raise it with a migration step in {@link DataVersions#read}. */
        static final int VERSION = 1;
        final Map<String, Integer> kills = new HashMap<>();

        public static SavedData.Factory<Data> factory() {
            return new SavedData.Factory<>(Data::new, (tag, provider) -> {
                DataVersions.read(NAME, tag, VERSION);
                Data data = new Data();
                CompoundTag kills = tag.getCompound("kills");
                kills.getAllKeys().forEach(key -> data.kills.put(key, kills.getInt(key)));
                return data;
            }, null);
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            DataVersions.stamp(tag, VERSION);
            CompoundTag kills = new CompoundTag();
            this.kills.forEach(kills::putInt);
            tag.put("kills", kills);
            return tag;
        }
    }

    private Data data;

    public void attach(Data data) {
        this.data = data;
    }

    public void detach() {
        data = null;
    }

    /** Something died: a guard who killed a monster or a raider scores a point; players and colonists don't count. */
    public void onDeath(LivingEntity dead, Entity killer) {
        if (data == null || dead.level().isClientSide() || dead instanceof Player || dead instanceof AbstractEntityCitizen
                || !(killer instanceof AbstractEntityCitizen guard)) {
            return;
        }
        ICitizenData citizen = guard.getCitizenData();
        if (citizen == null || citizen.getJob() == null || !citizen.getJob().isGuard()) {
            return;
        }
        data.kills.merge(citizen.getColony().getID() + ":" + citizen.getId(), 1, Integer::sum);
        data.setDirty();
    }

    JsonObject board(IColony colony) {
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        JsonArray board = new JsonArray();
        if (data != null) {
            String prefix = colony.getID() + ":";
            data.kills.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).limit(SHOWN)
                    .forEach(e -> {
                        ICitizenData guard = colony.getCitizenManager().getCivilian(Integer.parseInt(e.getKey().substring(prefix.length())));
                        if (guard != null) {
                            board.add(guard.getName() + ": " + e.getValue());
                        }
                    });
        }
        reply.add("kills", board);
        if (board.isEmpty()) {
            reply.addProperty("info", "No guard has killed an enemy yet, as far as the colony's tally goes.");
        }
        return reply;
    }

    /** Drops gone colonists' counts. */
    public void forget(Predicate<String> key) {
        if (data != null && data.kills.keySet().removeIf(key)) {
            data.setDirty();
        }
    }
}

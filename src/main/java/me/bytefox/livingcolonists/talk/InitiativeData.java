package me.bytefox.livingcolonists.talk;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What colonists already brought up to players unprompted, saved with the world: how often each
 * unsolved problem was raised (and when), and which things a player told them they already asked
 * about. Keys start with {@code colonyId:citizenId:}.
 */
public final class InitiativeData extends SavedData {
    public static final String NAME = "livingcolonists_initiative";

    record Raised(int times, long last) {
    }

    /** {@code colonyId:citizenId:playerUuid:issueKey}. */
    final Map<String, Raised> complaints = new HashMap<>();
    /** {@code colonyId:citizenId:playerUuid:topicKey}, with the game time it came up. */
    final Map<String, Long> done = new HashMap<>();

    public static SavedData.Factory<InitiativeData> factory() {
        return new SavedData.Factory<>(InitiativeData::new, (tag, provider) -> {
            InitiativeData data = new InitiativeData();
            CompoundTag complaints = tag.getCompound("complaints");
            for (String key : complaints.getAllKeys()) {
                CompoundTag c = complaints.getCompound(key);
                data.complaints.put(key, new Raised(c.getInt("times"), c.getLong("last")));
            }
            CompoundTag done = tag.getCompound("done");
            done.getAllKeys().forEach(key -> data.done.put(key, done.getLong(key)));
            return data;
        }, null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag complaints = new CompoundTag();
        this.complaints.forEach((key, raised) -> {
            CompoundTag c = new CompoundTag();
            c.putInt("times", raised.times());
            c.putLong("last", raised.last());
            complaints.put(key, c);
        });
        tag.put("complaints", complaints);
        CompoundTag done = new CompoundTag();
        this.done.forEach(done::putLong);
        tag.put("done", done);
        return tag;
    }

    /** Drops gone colonists' records, by their {@code colonyId:citizenId} key. */
    void forget(Predicate<String> citizenKey) {
        boolean changed = complaints.keySet().removeIf(key -> citizenKey.test(citizenOf(key)))
                | done.keySet().removeIf(key -> citizenKey.test(citizenOf(key)));
        if (changed) {
            setDirty();
        }
    }

    private static String citizenOf(String key) {
        int second = key.indexOf(':', key.indexOf(':') + 1);
        return second < 0 ? key : key.substring(0, second);
    }
}

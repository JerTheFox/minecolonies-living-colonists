package me.bytefox.livingcolonists.personality;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import me.bytefox.livingcolonists.data.DataVersions;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Colonists' archetypes and backgrounds, saved with the world in {@code data/livingcolonists_personalities.dat}. */
public final class PersonalityData extends SavedData {
    public static final String NAME = "livingcolonists_personalities";
    /** Format of the saved file; raise it with a migration step in {@link DataVersions#read}. */
    static final int VERSION = 1;

    /** Key: {@code colonyId:citizenId}. Server thread only. */
    private final Map<String, Personality> personalities = new HashMap<>();

    public static SavedData.Factory<PersonalityData> factory() {
        return new SavedData.Factory<>(PersonalityData::new, (tag, provider) -> load(DataVersions.read(NAME, tag, VERSION)), null);
    }

    public static String key(int colonyId, int citizenId) {
        return colonyId + ":" + citizenId;
    }

    public Personality get(String key) {
        return personalities.get(key);
    }

    public void put(String key, Personality personality) {
        personalities.put(key, personality);
        setDirty();
    }

    /** Drops the colonists whose key matches; returns how many. */
    public int removeIf(Predicate<String> key) {
        int before = personalities.size();
        personalities.keySet().removeIf(key);
        if (personalities.size() != before) {
            setDirty();
        }
        return before - personalities.size();
    }

    private static PersonalityData load(CompoundTag tag) {
        PersonalityData data = new PersonalityData();
        for (String key : tag.getAllKeys()) {
            if (key.equals(DataVersions.KEY)) {
                continue;
            }
            CompoundTag entry = tag.getCompound(key);
            data.personalities.put(key, new Personality(entry.getString("archetype"), entry.getString("bio"),
                    entry.getBoolean("child"), entry.getString("voice")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        DataVersions.stamp(tag, VERSION);
        personalities.forEach((key, personality) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("archetype", personality.archetype());
            entry.putString("bio", personality.bio());
            entry.putBoolean("child", personality.child());
            entry.putString("voice", personality.voice());
            tag.put(key, entry);
        });
        return tag;
    }
}

package me.bytefox.livingcolonists.memory;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Recent conversations, saved with the world in {@code data/livingcolonists_memory.dat}: each
 * colonist's private talks per player, and each colony's chat. Server thread only.
 */
public final class MemoryData extends SavedData {
    public static final String NAME = "livingcolonists_memory";

    /** Key: {@code colonyId:citizenId:playerUuid}. */
    final Map<String, Transcript> privateTalks = new HashMap<>();
    /** Key: colony id. */
    final Map<Integer, Transcript> colonyChats = new HashMap<>();

    public static SavedData.Factory<MemoryData> factory() {
        return new SavedData.Factory<>(MemoryData::new, (tag, provider) -> load(tag), null);
    }

    private static MemoryData load(CompoundTag tag) {
        MemoryData data = new MemoryData();
        CompoundTag talks = tag.getCompound("private");
        for (String key : talks.getAllKeys()) {
            data.privateTalks.put(key, read(talks.getList(key, Tag.TAG_COMPOUND)));
        }
        CompoundTag chats = tag.getCompound("colony");
        for (String key : chats.getAllKeys()) {
            data.colonyChats.put(Integer.parseInt(key), read(chats.getList(key, Tag.TAG_COMPOUND)));
        }
        return data;
    }

    private static Transcript read(ListTag list) {
        Transcript transcript = new Transcript();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag line = list.getCompound(i);
            transcript.add(new MemoryLine(line.getLong("time"), line.getString("speaker"), line.getString("text")),
                    Integer.MAX_VALUE, Long.MAX_VALUE);
        }
        return transcript;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag talks = new CompoundTag();
        privateTalks.forEach((key, transcript) -> {
            if (!transcript.isEmpty()) {
                talks.put(key, write(transcript));
            }
        });
        CompoundTag chats = new CompoundTag();
        colonyChats.forEach((key, transcript) -> {
            if (!transcript.isEmpty()) {
                chats.put(String.valueOf(key), write(transcript));
            }
        });
        tag.put("private", talks);
        tag.put("colony", chats);
        return tag;
    }

    private static ListTag write(Transcript transcript) {
        ListTag list = new ListTag();
        for (MemoryLine line : transcript.all()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("time", line.gameTime());
            entry.putString("speaker", line.speaker());
            entry.putString("text", line.text());
            list.add(entry);
        }
        return list;
    }
}

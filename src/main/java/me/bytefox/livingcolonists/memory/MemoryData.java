package me.bytefox.livingcolonists.memory;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Conversations and what colonists remember of them, saved with the world in
 * {@code data/livingcolonists_memory.dat}: each colonist's private talks per player, each colony's
 * chat, and each colonist's facts. Server thread only.
 */
public final class MemoryData extends SavedData {
    public static final String NAME = "livingcolonists_memory";

    /** Key: {@code colonyId:citizenId:playerUuid}. */
    final Map<String, Transcript> privateTalks = new HashMap<>();
    /** Key: colony id. */
    final Map<Integer, Transcript> colonyChats = new HashMap<>();
    /** Key: {@code colonyId:citizenId}. */
    final Map<String, FactBook> facts = new HashMap<>();

    public static SavedData.Factory<MemoryData> factory() {
        return new SavedData.Factory<>(MemoryData::new, (tag, provider) -> load(tag), null);
    }

    private static MemoryData load(CompoundTag tag) {
        MemoryData data = new MemoryData();
        CompoundTag talks = tag.getCompound("private");
        CompoundTag talksDigested = tag.getCompound("privateDigested");
        for (String key : talks.getAllKeys()) {
            data.privateTalks.put(key, read(talks.getList(key, Tag.TAG_COMPOUND), talksDigested, key));
        }
        CompoundTag chats = tag.getCompound("colony");
        CompoundTag chatsDigested = tag.getCompound("colonyDigested");
        for (String key : chats.getAllKeys()) {
            data.colonyChats.put(Integer.parseInt(key), read(chats.getList(key, Tag.TAG_COMPOUND), chatsDigested, key));
        }
        CompoundTag books = tag.getCompound("facts");
        for (String key : books.getAllKeys()) {
            FactBook book = new FactBook();
            ListTag list = books.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag fact = list.getCompound(i);
                book.add(new Fact(fact.getString("text"), fact.getInt("importance"), fact.getLong("created"),
                        fact.getLong("expires"), fact.getString("source"), fact.getBoolean("told")));
            }
            data.facts.put(key, book);
        }
        return data;
    }

    private static Transcript read(ListTag list, CompoundTag digested, String key) {
        Transcript transcript = new Transcript();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag line = list.getCompound(i);
            transcript.add(new MemoryLine(line.getLong("time"), line.getString("speaker"), line.getString("text")),
                    Integer.MAX_VALUE, Long.MAX_VALUE);
        }
        // Worlds saved before facts existed have no mark: their old lines count as already noted.
        transcript.markDigested(digested.contains(key) ? digested.getLong(key) : transcript.lastTime());
        return transcript;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag talks = new CompoundTag();
        CompoundTag talksDigested = new CompoundTag();
        privateTalks.forEach((key, transcript) -> {
            if (!transcript.isEmpty()) {
                talks.put(key, write(transcript));
                talksDigested.putLong(key, transcript.digested());
            }
        });
        CompoundTag chats = new CompoundTag();
        CompoundTag chatsDigested = new CompoundTag();
        colonyChats.forEach((key, transcript) -> {
            if (!transcript.isEmpty()) {
                chats.put(String.valueOf(key), write(transcript));
                chatsDigested.putLong(String.valueOf(key), transcript.digested());
            }
        });
        CompoundTag books = new CompoundTag();
        facts.forEach((key, book) -> {
            if (!book.isEmpty()) {
                ListTag list = new ListTag();
                for (Fact fact : book.all()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("text", fact.text());
                    entry.putInt("importance", fact.importance());
                    entry.putLong("created", fact.created());
                    entry.putLong("expires", fact.expires());
                    entry.putString("source", fact.source());
                    entry.putBoolean("told", fact.told());
                    list.add(entry);
                }
                books.put(key, list);
            }
        });
        tag.put("private", talks);
        tag.put("privateDigested", talksDigested);
        tag.put("colony", chats);
        tag.put("colonyDigested", chatsDigested);
        tag.put("facts", books);
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

package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Promises players made to colonists and how players treat them, saved with the world in
 * {@code data/livingcolonists_bonds.dat}. Server thread only.
 */
public final class BondsData extends SavedData {
    public static final String NAME = "livingcolonists_bonds";

    /** Key: {@code colonyId:citizenId}; oldest first. */
    final Map<String, List<Promise>> promises = new HashMap<>();
    /** Key: {@code colonyId:citizenId:playerUuid}. */
    final Map<String, Relation> relations = new HashMap<>();
    /** The name colonists last knew each player by, for what the colony says about them. */
    final Map<UUID, String> playerNames = new HashMap<>();
    long nextId = 1;

    public static SavedData.Factory<BondsData> factory() {
        return new SavedData.Factory<>(BondsData::new, (tag, provider) -> load(tag), null);
    }

    private static BondsData load(CompoundTag tag) {
        BondsData data = new BondsData();
        data.nextId = Math.max(1, tag.getLong("nextId"));
        CompoundTag promises = tag.getCompound("promises");
        for (String key : promises.getAllKeys()) {
            List<Promise> list = new ArrayList<>();
            ListTag entries = promises.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag p = entries.getCompound(i);
                list.add(new Promise(p.getLong("id"), p.getUUID("maker"), p.getString("makerName"), p.getString("text"),
                        Promise.About.of(p.getString("about")), p.getLong("made"), p.getLong("due"),
                        status(p.getString("status")), p.getLong("resolved"), p.getBoolean("needSeen")));
            }
            data.promises.put(key, list);
        }
        CompoundTag relations = tag.getCompound("relations");
        for (String key : relations.getAllKeys()) {
            CompoundTag r = relations.getCompound(key);
            List<Relation.Note> notes = new ArrayList<>();
            ListTag entries = r.getList("notes", Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag n = entries.getCompound(i);
                notes.add(new Relation.Note(n.getLong("time"), n.getString("text"), n.getBoolean("kind")));
            }
            data.relations.put(key, new Relation(r.getInt("rapport"), notes));
        }
        CompoundTag names = tag.getCompound("players");
        for (String key : names.getAllKeys()) {
            data.playerNames.put(UUID.fromString(key), names.getString(key));
        }
        return data;
    }

    private static Promise.Status status(String name) {
        try {
            return Promise.Status.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Promise.Status.CANCELLED;
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("nextId", nextId);
        CompoundTag promiseTag = new CompoundTag();
        promises.forEach((key, list) -> {
            if (list.isEmpty()) {
                return;
            }
            ListTag entries = new ListTag();
            for (Promise promise : list) {
                CompoundTag p = new CompoundTag();
                p.putLong("id", promise.id());
                p.putUUID("maker", promise.maker());
                p.putString("makerName", promise.makerName());
                p.putString("text", promise.text());
                p.putString("about", promise.about().name());
                p.putLong("made", promise.made());
                p.putLong("due", promise.due());
                p.putString("status", promise.status().name());
                p.putLong("resolved", promise.resolved());
                p.putBoolean("needSeen", promise.needSeen());
                entries.add(p);
            }
            promiseTag.put(key, entries);
        });
        CompoundTag relationTag = new CompoundTag();
        relations.forEach((key, relation) -> {
            CompoundTag r = new CompoundTag();
            r.putInt("rapport", relation.rapport());
            ListTag notes = new ListTag();
            for (Relation.Note note : relation.notes()) {
                CompoundTag n = new CompoundTag();
                n.putLong("time", note.time());
                n.putString("text", note.text());
                n.putBoolean("kind", note.kind());
                notes.add(n);
            }
            r.put("notes", notes);
            relationTag.put(key, r);
        });
        CompoundTag names = new CompoundTag();
        playerNames.forEach((uuid, name) -> names.putString(uuid.toString(), name));
        tag.put("promises", promiseTag);
        tag.put("relations", relationTag);
        tag.put("players", names);
        return tag;
    }
}

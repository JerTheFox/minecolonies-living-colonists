package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import me.bytefox.livingcolonists.data.DataVersions;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/** Letters from colonists waiting in players' notebooks (#67), in {@code data/livingcolonists_letters.dat}. */
public final class LettersData extends SavedData {
    public static final String NAME = "livingcolonists_letters";
    /** Format of the saved file; raise it with a migration step in {@link DataVersions#read}. */
    static final int VERSION = 1;

    /** By player, oldest first. */
    final Map<UUID, List<Letter>> letters = new HashMap<>();
    /** The calendar day each colonist last wrote to each player, by {@code colonyId:citizenId:player}. */
    final Map<String, Integer> lastLetter = new HashMap<>();
    long nextId = 1;

    public static SavedData.Factory<LettersData> factory() {
        return new SavedData.Factory<>(LettersData::new, (tag, provider) -> load(DataVersions.read(NAME, tag, VERSION), provider), null);
    }

    private static LettersData load(CompoundTag tag, HolderLookup.Provider registries) {
        LettersData data = new LettersData();
        data.nextId = Math.max(1, tag.getLong("nextId"));
        CompoundTag players = tag.getCompound("letters");
        for (String player : players.getAllKeys()) {
            List<Letter> list = new ArrayList<>();
            ListTag entries = players.getList(player, Tag.TAG_COMPOUND);
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag l = entries.getCompound(i);
                list.add(new Letter(l.getLong("id"), l.getString("from"), l.getString("colony"), l.getString("kind"),
                        l.getInt("variant"), ItemStack.parseOptional(registries, l.getCompound("gift")), l.getInt("day"),
                        l.getBoolean("taken")));
            }
            data.letters.put(UUID.fromString(player), list);
        }
        CompoundTag last = tag.getCompound("lastLetter");
        last.getAllKeys().forEach(key -> data.lastLetter.put(key, last.getInt(key)));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        DataVersions.stamp(tag, VERSION);
        tag.putLong("nextId", nextId);
        CompoundTag players = new CompoundTag();
        letters.forEach((player, list) -> {
            ListTag entries = new ListTag();
            for (Letter letter : list) {
                CompoundTag l = new CompoundTag();
                l.putLong("id", letter.id());
                l.putString("from", letter.from());
                l.putString("colony", letter.colony());
                l.putString("kind", letter.kind());
                l.putInt("variant", letter.variant());
                l.put("gift", letter.gift().saveOptional(registries));
                l.putInt("day", letter.day());
                l.putBoolean("taken", letter.taken());
                entries.add(l);
            }
            players.put(player.toString(), entries);
        });
        tag.put("letters", players);
        CompoundTag last = new CompoundTag();
        lastLetter.forEach(last::putInt);
        tag.put("lastLetter", last);
        return tag;
    }
}

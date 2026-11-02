package me.bytefox.livingcolonists.friendship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.bytefox.livingcolonists.data.DataVersions;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** The colony calendars (#67), saved with the world in {@code data/livingcolonists_calendar.dat}. Server thread only. */
public final class CalendarData extends SavedData {
    public static final String NAME = "livingcolonists_calendar";
    /** Format of the saved file; raise it with a migration step in {@link DataVersions#read}. */
    static final int VERSION = 1;
    /** The departed remembered on the Night of Remembrance, at most this many per colony. */
    static final int DEPARTED_KEPT = 30;

    /** By colony id. */
    final Map<Integer, Presence> presence = new HashMap<>();
    /** Day of the year of each colonist's birthday, by {@code colonyId:citizenId}. */
    final Map<String, Integer> births = new HashMap<>();
    /** Names of colonists who died, oldest first, by colony id. */
    final Map<Integer, List<String>> departed = new HashMap<>();
    /** Born and died this calendar year, by colony id: {born, died}. */
    final Map<Integer, int[]> thisYear = new HashMap<>();

    public static SavedData.Factory<CalendarData> factory() {
        return new SavedData.Factory<>(CalendarData::new, (tag, provider) -> load(DataVersions.read(NAME, tag, VERSION)), null);
    }

    private static CalendarData load(CompoundTag tag) {
        CalendarData data = new CalendarData();
        CompoundTag colonies = tag.getCompound("colonies");
        for (String key : colonies.getAllKeys()) {
            CompoundTag c = colonies.getCompound(key);
            int id = Integer.parseInt(key);
            data.presence.put(id, new Presence(c.getInt("day"), c.getLong("counted"), c.getLong("nearDay"), c.getLong("nearTicks")));
            data.thisYear.put(id, new int[] {c.getInt("born"), c.getInt("died")});
            ListTag names = c.getList("departed", Tag.TAG_STRING);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                list.add(names.getString(i));
            }
            data.departed.put(id, list);
        }
        CompoundTag births = tag.getCompound("births");
        births.getAllKeys().forEach(key -> data.births.put(key, births.getInt(key)));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        DataVersions.stamp(tag, VERSION);
        CompoundTag colonies = new CompoundTag();
        presence.forEach((id, p) -> {
            CompoundTag c = new CompoundTag();
            c.putInt("day", p.day());
            c.putLong("counted", p.countedGameDay());
            c.putLong("nearDay", p.nearGameDay());
            c.putLong("nearTicks", p.nearTicks());
            int[] year = thisYear.getOrDefault(id, new int[2]);
            c.putInt("born", year[0]);
            c.putInt("died", year[1]);
            ListTag names = new ListTag();
            departed.getOrDefault(id, List.of()).forEach(name -> names.add(StringTag.valueOf(name)));
            c.put("departed", names);
            colonies.put(Integer.toString(id), c);
        });
        tag.put("colonies", colonies);
        CompoundTag birthTag = new CompoundTag();
        births.forEach(birthTag::putInt);
        tag.put("births", birthTag);
        return tag;
    }
}

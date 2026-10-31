package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import me.bytefox.livingcolonists.data.DataVersions;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What colonists already brought up to players unprompted, saved with the world: how often each
 * unsolved problem was raised (and when), which things a player told them they already asked
 * about, and each colonist's personal goal. Keys start with {@code colonyId:citizenId}.
 */
public final class InitiativeData extends SavedData {
    public static final String NAME = "livingcolonists_initiative";
    /** Format of the saved file; raise it with a migration step in {@link DataVersions#read}. */
    static final int VERSION = 1;

    record Raised(int times, long last) {
    }

    /**
     * Something a colonist hopes for, which code can tell came true.
     *
     * @param kind   {@code home} (their home a level up), {@code work} (their workplace a level up) or
     *               {@code place:<building>} (the colony getting a building, like {@code place:hospital})
     * @param level  the level hoped for; 0 for a place
     * @param doneAt game time it came true; 0 while it hasn't
     * @param told   whether the colonist already told a player it came true
     */
    record Goal(String kind, int level, long since, long doneAt, boolean told) {
        Goal cameTrue(long now) {
            return new Goal(kind, level, since, now, false);
        }

        Goal toldNow() {
            return new Goal(kind, level, since, doneAt, true);
        }
    }

    /** {@code colonyId:citizenId:playerUuid:issueKey}. */
    final Map<String, Raised> complaints = new HashMap<>();
    /** {@code colonyId:citizenId:playerUuid:topicKey}, with the game time it came up. */
    final Map<String, Long> done = new HashMap<>();
    /** {@code colonyId:citizenId}. */
    final Map<String, Goal> goals = new HashMap<>();
    /** {@code colonyId:playerUuid}: game time the player was last seen in the colony. */
    final Map<String, Long> seen = new HashMap<>();
    /** Colonies where the mod is turned off by an operator (#55). */
    final java.util.Set<Integer> off = new java.util.HashSet<>();
    /** What happened in each colony lately, as told later, oldest first (#34). */
    final Map<Integer, Deque<Told>> history = new HashMap<>();
    /** Big deeds colonists heard each player did, by player UUID, oldest first (#39). */
    final Map<String, Deque<Told>> deeds = new HashMap<>();

    /** Something to tell later and the game time it happened. */
    record Told(String what, long gameTime) {
    }

    public static SavedData.Factory<InitiativeData> factory() {
        return new SavedData.Factory<>(InitiativeData::new, (tag, provider) -> {
            DataVersions.read(NAME, tag, VERSION);
            InitiativeData data = new InitiativeData();
            CompoundTag complaints = tag.getCompound("complaints");
            for (String key : complaints.getAllKeys()) {
                CompoundTag c = complaints.getCompound(key);
                data.complaints.put(key, new Raised(c.getInt("times"), c.getLong("last")));
            }
            CompoundTag done = tag.getCompound("done");
            done.getAllKeys().forEach(key -> data.done.put(key, done.getLong(key)));
            CompoundTag goals = tag.getCompound("goals");
            for (String key : goals.getAllKeys()) {
                CompoundTag g = goals.getCompound(key);
                data.goals.put(key, new Goal(g.getString("kind"), g.getInt("level"), g.getLong("since"),
                        g.getLong("doneAt"), g.getBoolean("told")));
            }
            CompoundTag seen = tag.getCompound("seen");
            seen.getAllKeys().forEach(key -> data.seen.put(key, seen.getLong(key)));
            for (int id : tag.getIntArray("off")) {
                data.off.add(id);
            }
            CompoundTag history = tag.getCompound("history");
            history.getAllKeys().forEach(key -> data.history.put(Integer.parseInt(key), readTold(history.getList(key, Tag.TAG_COMPOUND))));
            CompoundTag deeds = tag.getCompound("deeds");
            deeds.getAllKeys().forEach(key -> data.deeds.put(key, readTold(deeds.getList(key, Tag.TAG_COMPOUND))));
            return data;
        }, null);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        DataVersions.stamp(tag, VERSION);
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
        CompoundTag goals = new CompoundTag();
        this.goals.forEach((key, goal) -> {
            CompoundTag g = new CompoundTag();
            g.putString("kind", goal.kind());
            g.putInt("level", goal.level());
            g.putLong("since", goal.since());
            g.putLong("doneAt", goal.doneAt());
            g.putBoolean("told", goal.told());
            goals.put(key, g);
        });
        tag.put("goals", goals);
        CompoundTag seen = new CompoundTag();
        this.seen.forEach(seen::putLong);
        tag.put("seen", seen);
        tag.putIntArray("off", off.stream().mapToInt(Integer::intValue).toArray());
        CompoundTag history = new CompoundTag();
        this.history.forEach((colony, list) -> history.put(String.valueOf(colony), writeTold(list)));
        tag.put("history", history);
        CompoundTag deeds = new CompoundTag();
        this.deeds.forEach((player, list) -> deeds.put(player, writeTold(list)));
        tag.put("deeds", deeds);
        return tag;
    }

    private static Deque<Told> readTold(ListTag list) {
        Deque<Told> out = new ArrayDeque<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            out.addLast(new Told(t.getString("what"), t.getLong("at")));
        }
        return out;
    }

    private static ListTag writeTold(Deque<Told> told) {
        ListTag list = new ListTag();
        for (Told t : told) {
            CompoundTag entry = new CompoundTag();
            entry.putString("what", t.what());
            entry.putLong("at", t.gameTime());
            list.add(entry);
        }
        return list;
    }

    /** Drops gone colonists' records, by their {@code colonyId:citizenId} key. */
    void forget(Predicate<String> citizenKey) {
        boolean changed = complaints.keySet().removeIf(key -> citizenKey.test(citizenOf(key)))
                | done.keySet().removeIf(key -> citizenKey.test(citizenOf(key)))
                | goals.keySet().removeIf(citizenKey);
        if (changed) {
            setDirty();
        }
    }

    private static String citizenOf(String key) {
        int second = key.indexOf(':', key.indexOf(':') + 1);
        return second < 0 ? key : key.substring(0, second);
    }
}

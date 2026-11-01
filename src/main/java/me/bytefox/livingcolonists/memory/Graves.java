package me.bytefox.livingcolonists.memory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;

/**
 * Colonists who died lately, kept a few game days in case MineColonies' undertaker brings them back: their character
 * and memories wait instead of being dropped at once. A colonist brought back comes with the same name and a new id.
 * Saved with the memory data. Server thread only.
 */
public final class Graves {
    /** How long a dead colonist's memories wait: five game days. */
    public static final long KEEP_TICKS = 5 * 24000L;

    private record Grave(String name, long diedAt) {
    }

    /** By the dead colonist's key, {@code colony:citizen}. */
    private final Map<String, Grave> graves = new HashMap<>();

    public void died(String key, String name, long now) {
        graves.put(key, new Grave(name, now));
    }

    /** Whether the memories of the colonist with this key still wait for them. */
    public boolean keeps(String key, long now) {
        Grave grave = graves.get(key);
        return grave != null && now - grave.diedAt() < KEEP_TICKS;
    }

    /** Drops the graves nobody came back from in time; returns their keys. */
    public List<String> expire(long now) {
        List<String> gone = new ArrayList<>();
        graves.entrySet().removeIf(entry -> {
            boolean old = now - entry.getValue().diedAt() >= KEEP_TICKS;
            if (old) {
                gone.add(entry.getKey());
            }
            return old;
        });
        return gone;
    }

    /** The key of the dead colonist this living one is, brought back: the same colony and name, another id. */
    public Optional<String> cameBack(int colonyId, int id, String name) {
        String prefix = colonyId + ":";
        return graves.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix) && !entry.getKey().equals(prefix + id)
                        && entry.getValue().name().equals(name))
                .map(Map.Entry::getKey).findFirst();
    }

    public void remove(String key) {
        graves.remove(key);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        graves.forEach((key, grave) -> {
            CompoundTag one = new CompoundTag();
            one.putString("name", grave.name());
            one.putLong("diedAt", grave.diedAt());
            tag.put(key, one);
        });
        return tag;
    }

    public static Graves load(CompoundTag tag) {
        Graves graves = new Graves();
        for (String key : tag.getAllKeys()) {
            CompoundTag one = tag.getCompound(key);
            graves.graves.put(key, new Grave(one.getString("name"), one.getLong("diedAt")));
        }
        return graves;
    }
}

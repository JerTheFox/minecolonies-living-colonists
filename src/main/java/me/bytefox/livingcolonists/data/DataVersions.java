package me.bytefox.livingcolonists.data;

import me.bytefox.livingcolonists.ModLog;
import net.minecraft.nbt.CompoundTag;

/**
 * Format versions of the mod's saved files (#40): each file says which version wrote it, and an older
 * one is brought up to date step by step when read. A file without a version is version 1, the format
 * from before versions existed.
 */
public final class DataVersions {
    public static final String KEY = "formatVersion";

    /** One step of a migration: changes a tag of version {@code n} into version {@code n + 1}, in place. */
    @FunctionalInterface
    public interface Step {
        void apply(CompoundTag tag);
    }

    private DataVersions() {
    }

    /**
     * The tag brought up to {@code current}: {@code steps[0]} turns version 1 into 2, and so on. A file
     * from a newer mod is read as it is, with a warning, since nothing can turn it back.
     */
    public static CompoundTag read(String name, CompoundTag tag, int current, Step... steps) {
        int version = tag.contains(KEY) ? tag.getInt(KEY) : 1;
        if (version > current) {
            ModLog.warn("{} was saved by a newer version of the mod (format {}, this one reads {}); reading it as it is",
                    name, version, current);
            return tag;
        }
        for (int v = version; v < current; v++) {
            steps[v - 1].apply(tag);
        }
        tag.putInt(KEY, current);
        return tag;
    }

    /** Marks a tag being saved with its format version. */
    public static CompoundTag stamp(CompoundTag tag, int current) {
        tag.putInt(KEY, current);
        return tag;
    }
}

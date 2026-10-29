package me.bytefox.livingcolonists.debug;

import java.util.HashMap;
import java.util.Map;

import me.bytefox.livingcolonists.llm.UsageStats;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** Total token usage per model, saved with the world in {@code data/livingcolonists_usage.dat}. */
public final class UsageData extends SavedData {
    public static final String NAME = "livingcolonists_usage";

    private final UsageStats stats;

    private UsageData(UsageStats stats) {
        this.stats = stats;
    }

    public static SavedData.Factory<UsageData> factory(UsageStats stats) {
        return new SavedData.Factory<>(() -> new UsageData(stats), (tag, provider) -> load(stats, tag), null);
    }

    private static UsageData load(UsageStats stats, CompoundTag tag) {
        Map<String, UsageStats.Counts> totals = new HashMap<>();
        for (String model : tag.getAllKeys()) {
            CompoundTag counts = tag.getCompound(model);
            totals.put(model, new UsageStats.Counts(counts.getLong("requests"), counts.getLong("failures"),
                    counts.getLong("promptTokens"), counts.getLong("completionTokens"), counts.getLong("cachedTokens"),
                    counts.getLong("latencyMillis")));
        }
        stats.restore(totals);
        return new UsageData(stats);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        stats.total().forEach((model, counts) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("requests", counts.requests());
            entry.putLong("failures", counts.failures());
            entry.putLong("promptTokens", counts.promptTokens());
            entry.putLong("completionTokens", counts.completionTokens());
            entry.putLong("cachedTokens", counts.cachedTokens());
            entry.putLong("latencyMillis", counts.latencyMillis());
            tag.put(model, entry);
        });
        return tag;
    }
}

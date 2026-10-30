package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.talk.Translations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Every item of the game with its names: the server's English, and the Russian and the player's
 * own language where the item's mod ships them. Built once per locale: the common ones in the
 * background at server start, others on first use.
 */
public final class ItemIndex {
    private final Map<String, List<ItemMatch.Candidate<Item>>> byLocale = new ConcurrentHashMap<>();

    /**
     * Builds the index for a locale in the background, so the first look-up doesn't stall the server
     * reading the language files of hundreds of mods. The item registry is frozen by then, and the
     * language files are only read.
     */
    public void warmUp(String locale) {
        CompletableFuture.runAsync(() -> candidates(locale)).exceptionally(e -> {
            LivingColonists.LOGGER.warn("Could not prepare the item names for {}: {}", locale, e.toString());
            return null;
        });
    }

    public ItemMatch.Result<Item> find(String words, String locale) {
        return ItemMatch.find(words, candidates(locale));
    }

    private List<ItemMatch.Candidate<Item>> candidates(String locale) {
        return byLocale.computeIfAbsent(locale, ItemIndex::build);
    }

    private static List<ItemMatch.Candidate<Item>> build(String locale) {
        Set<String> languages = new LinkedHashSet<>(List.of("ru_ru", locale));
        languages.remove("en_us");
        List<ItemMatch.Candidate<Item>> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            List<String> names = new ArrayList<>();
            names.add(item.getDescription().getString());
            for (String language : languages) {
                String name = Translations.of(id.getNamespace(), language).get(item.getDescriptionId());
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            items.add(new ItemMatch.Candidate<>(item, id.toString(), names));
        }
        return items;
    }
}

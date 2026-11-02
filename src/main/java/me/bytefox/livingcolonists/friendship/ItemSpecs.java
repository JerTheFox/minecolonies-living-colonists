package me.bytefox.livingcolonists.friendship;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** Items written in content files as {@code "minecraft:bread 3"}, or {@code "potion:minecraft:healing 1"} for a potion. */
final class ItemSpecs {
    private ItemSpecs() {
    }

    /** The item, or empty if the file names one that doesn't exist (a mod not installed). */
    static ItemStack of(String spec) {
        String[] parts = spec.strip().split("\\s+");
        int count = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1;
        if (parts[0].startsWith("potion:")) {
            ResourceLocation id = ResourceLocation.tryParse(parts[0].substring("potion:".length()));
            return id == null ? ItemStack.EMPTY : BuiltInRegistries.POTION.getHolder(id).map(potion -> {
                ItemStack stack = new ItemStack(Items.POTION, count);
                stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
                return stack;
            }).orElse(ItemStack.EMPTY);
        }
        ResourceLocation id = ResourceLocation.tryParse(parts[0]);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id), count);
    }
}

package me.bytefox.livingcolonists.friendship;

import net.minecraft.world.item.ItemStack;

/**
 * A letter from a colonist in the Colony Notebook (#67), with a small gift to take.
 *
 * @param from    the colonist's name; "" for a gift without a name (the Gift Exchange)
 * @param kind    friend, close, best, exchange or keepsake: which texts it is written with
 * @param variant which of that kind's texts, from 1
 * @param day     the colony calendar day it came
 */
public record Letter(long id, String from, String colony, String kind, int variant, ItemStack gift, int day, boolean taken) {
    public Letter withGift(ItemStack left) {
        return new Letter(id, from, colony, kind, variant, left, day, left.isEmpty());
    }
}

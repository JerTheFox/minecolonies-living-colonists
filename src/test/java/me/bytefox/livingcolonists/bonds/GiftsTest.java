package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GiftsTest {
    @Test
    void onlyWhatCameIntoTheInventoryIsAGift() {
        Map<String, Integer> before = Map.of("minecraft:bread", 2, "minecraft:dirt", 10);
        Map<String, Integer> after = Map.of("minecraft:bread", 18, "minecraft:dirt", 4, "minecraft:iron_pickaxe", 1);
        assertEquals(Map.of("minecraft:bread", 16, "minecraft:iron_pickaxe", 1), Gifts.added(before, after));
        assertTrue(Gifts.added(after, after).isEmpty());
    }

    @Test
    void aGiftOfThePromisedThingKeepsThePromise() {
        assertTrue(Gifts.fulfils("Max promised to bring Randolph an emerald as a gift", List.of("Emerald")));
        assertTrue(Gifts.fulfils("Max promised Idris planks by tomorrow", List.of("Spruce Planks")));
        assertFalse(Gifts.fulfils("Max promised to build Idris a house", List.of("Bread")));
        assertFalse(Gifts.fulfils("Max promised a pick", List.of("Oak Log")), "short words don't count");
    }

    @Test
    void aBiggerGiftWarmsMoreButATrifleStillCounts() {
        assertTrue(Gifts.warmth(1) > 0);
        assertTrue(Gifts.warmth(5) > Gifts.warmth(2));
        assertTrue(Gifts.warmth(5) <= Bonds.KEPT);
    }
}

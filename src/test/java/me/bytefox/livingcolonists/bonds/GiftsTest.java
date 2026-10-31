package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aBiggerGiftWarmsMoreButATrifleStillCounts() {
        assertTrue(Gifts.warmth(1) > 0);
        assertTrue(Gifts.warmth(5) > Gifts.warmth(2));
        assertTrue(Gifts.warmth(5) <= Bonds.KEPT);
    }
}

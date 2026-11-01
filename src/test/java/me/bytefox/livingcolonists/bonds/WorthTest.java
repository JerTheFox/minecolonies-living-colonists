package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorthTest {
    private static final Worth.Traits DIRT = new Worth.Traits(0, Worth.Kind.PLAIN, 0, false, 64);
    private static final Worth.Traits IRON = new Worth.Traits(0, Worth.Kind.INGOT, 0, false, 64);
    private static final Worth.Traits DIAMOND = new Worth.Traits(0, Worth.Kind.GEM, 0, false, 64);
    private static final Worth.Traits DIAMOND_BLOCK = new Worth.Traits(0, Worth.Kind.STORAGE_BLOCK, 0, false, 64);
    private static final Worth.Traits IRON_PICKAXE = new Worth.Traits(0, Worth.Kind.PLAIN, 250, false, 1);
    private static final Worth.Traits DIAMOND_PICKAXE = new Worth.Traits(0, Worth.Kind.PLAIN, 1561, false, 1);
    private static final Worth.Traits ENCHANTED_DIAMOND_PICKAXE = new Worth.Traits(0, Worth.Kind.PLAIN, 1561, true, 1);
    private static final Worth.Traits NETHER_STAR = new Worth.Traits(1, Worth.Kind.PLAIN, 0, false, 64);

    @Test
    void aStackOfDirtIsATrifle() {
        assertEquals(1, Worth.of(DIRT, 64));
        assertEquals(1, Worth.of(DIRT, 512));
    }

    @Test
    void valuableThingsGrowWithTheAmount() {
        assertEquals(1, Worth.of(IRON, 1));
        assertEquals(3, Worth.of(IRON, 64));
        assertTrue(Worth.of(DIAMOND, 64) >= 4);
        assertEquals(5, Worth.of(DIAMOND_BLOCK, 64));
        assertTrue(Worth.of(DIAMOND, 1) < Worth.of(DIAMOND, 64));
    }

    @Test
    void betterToolsAreWorthMore() {
        assertTrue(Worth.of(IRON_PICKAXE, 1) < Worth.of(DIAMOND_PICKAXE, 1));
        assertTrue(Worth.of(DIAMOND_PICKAXE, 1) < Worth.of(ENCHANTED_DIAMOND_PICKAXE, 1));
    }

    @Test
    void rareItemsCountAsRare() {
        assertTrue(Worth.of(NETHER_STAR, 1) > Worth.of(DIRT, 1));
    }

    @Test
    void worthStaysBetweenOneAndFive() {
        assertEquals(1, Worth.of(DIRT, 0));
        assertEquals(5, Worth.of(new Worth.Traits(3, Worth.Kind.STORAGE_BLOCK, 0, true, 64), 10_000));
    }
}

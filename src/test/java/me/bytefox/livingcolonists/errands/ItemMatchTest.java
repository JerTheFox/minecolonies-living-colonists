package me.bytefox.livingcolonists.errands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

class ItemMatchTest {
    private static final List<ItemMatch.Candidate<String>> ITEMS = List.of(
            item("minecraft:oak_planks", "Oak Planks", "Дубовые доски"),
            item("minecraft:spruce_planks", "Spruce Planks", "Еловые доски"),
            item("minecraft:iron_ingot", "Iron Ingot", "Железный слиток"),
            item("minecraft:iron_ore", "Iron Ore", "Железная руда"),
            item("minecraft:iron_block", "Block of Iron", "Железный блок"),
            item("minecraft:bread", "Bread", "Хлеб"),
            item("othermod:bread", "Bread", "Хлеб"),
            item("minecolonies:eggplant", "Eggplant", "Баклажан"),
            item("minecraft:cobblestone", "Cobblestone", "Булыжник"),
            item("minecraft:gray_wool", "Gray Wool", "Серая шерсть"));

    @Test
    void findsByExactName() {
        assertEquals("minecraft:oak_planks", found("oak planks"));
        assertEquals("minecraft:iron_ingot", found("Iron Ingot"));
        assertEquals("minecraft:oak_planks", found("дубовые доски"));
    }

    @Test
    void findsByPluralAndCase() {
        assertEquals("minecraft:iron_ingot", found("iron ingots"));
        assertEquals("minecraft:cobblestone", found("булыжника"));
        assertEquals("minecolonies:eggplant", found("eggplants"));
    }

    @Test
    void acceptsBritishSpelling() {
        assertEquals("minecraft:gray_wool", found("grey wool"));
    }

    @Test
    void findsByRegistryId() {
        assertEquals("minecraft:iron_block", found("iron block"));
    }

    @Test
    void prefersVanillaWhenModsShareAName() {
        assertEquals("minecraft:bread", found("bread"));
    }

    @Test
    void tellsWhenSeveralFitEqually() {
        var result = ItemMatch.find("planks", ITEMS);
        var ambiguous = assertInstanceOf(ItemMatch.Result.Ambiguous.class, result);
        assertEquals(2, ambiguous.options().size());
        assertInstanceOf(ItemMatch.Result.Ambiguous.class, ItemMatch.find("iron", ITEMS));
    }

    @Test
    void unknownWordsFindNothing() {
        assertInstanceOf(ItemMatch.Result.None.class, ItemMatch.find("diamond sword", ITEMS));
        assertInstanceOf(ItemMatch.Result.None.class, ItemMatch.find("", ITEMS));
    }

    private static String found(String query) {
        var result = ItemMatch.find(query, ITEMS);
        return ((ItemMatch.Result.Found<String>) assertInstanceOf(ItemMatch.Result.Found.class, result)).value();
    }

    private static ItemMatch.Candidate<String> item(String id, String... names) {
        return new ItemMatch.Candidate<>(id, id, List.of(names));
    }
}

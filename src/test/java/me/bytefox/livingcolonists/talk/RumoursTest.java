package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RumoursTest {
    @org.junit.jupiter.api.BeforeAll
    static void bundled() {
        Rumours.use(me.bytefox.livingcolonists.prompt.PromptLibrary.bundled("content/rumours.json"));
    }

    @Test
    void bigDeedsAreKnownSmallOnesAreNot() {
        assertEquals("killed the Ender Dragon", Rumours.deed("minecraft:end/kill_dragon", Map.of(), "Free the End"));
        assertNull(Rumours.deed("minecraft:husbandry/tame_an_animal", Map.of(), "Best Friends Forever"));
        assertNull(Rumours.deed("minecraft:story/enter_the_nether", Map.of(), "We Need to Go Deeper"));
    }

    @Test
    void theConfigAddsDeedsWithOrWithoutWords() {
        Map<String, String> extra = Rumours.extra(List.of("create:andesite_alloy=learned to make andesite alloy",
                " mymod:boss ", "", "=nothing"));
        assertEquals("learned to make andesite alloy", Rumours.deed("create:andesite_alloy", extra, "Alloy"));
        assertEquals("earned \"Boss Slayer\"", Rumours.deed("mymod:boss", extra, "Boss Slayer"));
        assertEquals(2, extra.size());
    }

    @Test
    void colonyMilestonesAreColonyNews() {
        assertTrue(Rumours.colonyNews("minecolonies:minecolonies/colony_population_25").contains("25"));
        assertNull(Rumours.colonyNews("minecraft:end/kill_dragon"));
        assertNull(Rumours.deed("minecolonies:minecolonies/colony_population_25", Map.of(), "Hamlet"));
    }
}

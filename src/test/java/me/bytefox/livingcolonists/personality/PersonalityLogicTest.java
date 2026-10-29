package me.bytefox.livingcolonists.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import me.bytefox.livingcolonists.prompt.Templates;
import org.junit.jupiter.api.Test;

class PersonalityLogicTest {
    private static final Archetype BRUTE = new Archetype("brute", "Brute", "You are strong.", 10, 0.5, 0.35,
            Map.of("Strength", 1.0, "Intelligence", -1.0));
    private static final Archetype THINKER = new Archetype("thinker", "Thinker", "You think.", 10, 0.5, 0.35,
            Map.of("Intelligence", 1.0, "Strength", -1.0));
    private static final List<Archetype> BOTH = List.of(BRUTE, THINKER);

    private static final Map<String, Integer> STRONG = Map.of("Strength", 20, "Intelligence", 2, "Focus", 8);
    private static final Map<String, Integer> CLEVER = Map.of("Strength", 2, "Intelligence", 20, "Focus", 8);

    @Test
    void templatesFillKnownPlaceholdersAndLeaveTheRest() {
        assertEquals("Hi John, {unknown}", Templates.render("Hi {name}, {unknown}", Map.of("name", "John")));
        assertEquals("a $1 b", Templates.render("a {x} b", Map.of("x", "$1")));
    }

    @Test
    void templatesDropBlankLinesLeftByEmptyPlaceholders() {
        assertEquals("one\ntwo", Templates.render("one\n{character}\ntwo", Map.of("character", "")));
    }

    @Test
    void parsesAnArchetypeFile() {
        Archetype archetype = Archetypes.parse("grumbler", """
                {"name": "Grumbler", "prompt": "You grumble.", "weight": 7, "talkativeness": 0.6,
                 "skills": {"Strength": 0.6, "Intelligence": -0.4}}""");

        assertEquals("grumbler", archetype.id());
        assertEquals("Grumbler", archetype.name());
        assertEquals(7, archetype.weight());
        assertEquals(0.6, archetype.skills().get("Strength"));
    }

    @Test
    void rejectsAnArchetypeWithoutAPrompt() {
        assertThrows(IllegalArgumentException.class, () -> Archetypes.parse("broken", "{\"name\": \"Broken\"}"));
        assertThrows(IllegalArgumentException.class, () -> Archetypes.parse("broken", "not json"));
    }

    private static Map<String, Integer> pickMany(Map<String, Integer> skills, List<String> parents, double inherit) {
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            counts.merge(ArchetypePicker.pick(BOTH, skills, parents, inherit, random).id(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void skillsMostlyDecideButChanceStillPlaysAPart() {
        Map<String, Integer> strong = pickMany(STRONG, List.of(), 0);
        assertTrue(strong.get("brute") > 1400, strong.toString());
        assertTrue(strong.getOrDefault("thinker", 0) > 150, strong.toString());

        Map<String, Integer> clever = pickMany(CLEVER, List.of(), 0);
        assertTrue(clever.get("thinker") > 1400, clever.toString());
    }

    @Test
    void childrenSometimesTakeAfterAParent() {
        Map<String, Integer> child = pickMany(STRONG, List.of("thinker"), 0.3);
        int thinkers = child.getOrDefault("thinker", 0);
        Map<String, Integer> noParents = pickMany(STRONG, List.of(), 0);
        assertTrue(thinkers > noParents.getOrDefault("thinker", 0) + 400, child + " vs " + noParents);
    }

    @Test
    void unknownParentArchetypesAreIgnored() {
        Map<String, Integer> child = pickMany(STRONG, List.of("deleted-archetype"), 1.0);
        assertEquals(2000, child.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void anEmptyListFallsBackToAnOrdinaryColonist() {
        assertEquals(Archetype.ORDINARY, ArchetypePicker.pick(List.of(), STRONG, List.of(), 0, new Random(1)));
    }
}

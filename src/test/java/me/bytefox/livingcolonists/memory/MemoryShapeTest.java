package me.bytefox.livingcolonists.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MemoryShapeTest {
    private static final long DAY = 24000;
    private static final UUID MAX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void aPlayersNameInAnyCaseBecomesAMarkThatReadsAsTheCurrentName() {
        Map<UUID, List<String>> names = Map.of(MAX, List.of("Максим", "JerTheFox"));
        String marked = PlayerMarks.markNames("Максим обещал Эфраиму дом, а Максиму никто не верит.", names);
        assertFalse(marked.contains("Максим "), marked);
        assertEquals("Максим обещал Эфраиму дом, а Максиму никто не верит.",
                PlayerMarks.resolve(marked, id -> id.equals(MAX) ? "Максим" : null));
        // Renamed later: the old form gives way to the new name.
        assertEquals("Max обещал Эфраиму дом, а Max никто не верит.",
                PlayerMarks.resolve(marked, id -> id.equals(MAX) ? "Max" : null));
        assertEquals("JerTheFox promised a house.",
                PlayerMarks.resolve(PlayerMarks.markNames("JerTheFox promised a house.", names), id -> "JerTheFox"));
    }

    @Test
    void anUnknownMarkFallsBackToItsWords() {
        assertEquals("Anna said hi.", PlayerMarks.resolve(PlayerMarks.mark(ANNA, "Anna", "Anna") + " said hi.", id -> null));
        assertEquals("someone said hi.", PlayerMarks.resolve(PlayerMarks.mark(ANNA, "", "") + " said hi.", id -> null));
        assertEquals("Anna said hi.", PlayerMarks.resolve(PlayerMarks.mark(ANNA, "", "") + " said hi.", id -> "Anna"));
    }

    @Test
    void wordsThatOnlyBeginLikeANameStayAsTheyAre() {
        Map<UUID, List<String>> names = Map.of(ANNA, List.of("Ann"));
        assertEquals("Annual report.", PlayerMarks.markNames("Annual report.", names));
    }

    @Test
    void aFactToldByAPlayerIsFoundByTheirIdOrAnOldName() {
        Fact byId = new Fact("Max likes fish.", 2, 0, DAY, Fact.privateTalk(MAX), false);
        Fact byName = new Fact("Max likes fish.", 2, 0, DAY, Fact.privateTalk("JerTheFox"), false);
        assertTrue(byId.toldBy(MAX, List.of("Максим")));
        assertTrue(byName.toldBy(MAX, List.of("Максим", "JerTheFox")));
        assertFalse(byName.toldBy(ANNA, List.of("Anna")));
    }

    @Test
    void theDigestSaysWhomAFactIsAbout() {
        List<Digests.Entry> entries = Digests.parse("""
                3 | Ephraim | Ephraim's knee aches in the mornings.
                4 | colony | A builder's hut was placed.
                2 | Max told Anna the mine is flooded.
                """, 10);
        assertEquals(new Digests.Entry(3, "Ephraim", "Ephraim's knee aches in the mornings."), entries.get(0));
        assertEquals("colony", entries.get(1).about());
        assertEquals(new Digests.Entry(2, "Max told Anna the mine is flooded."), entries.get(2));
    }

    @Test
    void aFactLikeOneAlreadyKnownAboutTheSamePersonReplacesIt() {
        FactBook book = new FactBook();
        assertTrue(book.add(new Fact("Ephraim's knee aches in the mornings.", 2, 0, DAY, Fact.COLONY_CHAT, false, "c:4")));
        assertFalse(book.add(new Fact("Ephraim's knee still aches, mornings mostly.", 2, 100, 2 * DAY, Fact.COLONY_CHAT,
                false, "c:4")));
        assertEquals(1, book.all().size());
        assertEquals("Ephraim's knee still aches, mornings mostly.", book.all().get(0).text());
        assertEquals(2, book.all().get(0).importance());
        // About someone else, or on another subject, it is a fact of its own.
        assertTrue(book.add(new Fact("Darcy's knee aches in the mornings.", 2, 0, DAY, Fact.COLONY_CHAT, false, "c:5")));
        assertTrue(book.add(new Fact("Ephraim wants to build a mill by the river.", 3, 0, DAY, Fact.COLONY_CHAT, false,
                "c:4")));
        assertEquals(3, book.all().size());
    }

    @Test
    void theFactsShownAreTheOnesTheTalkTouches() {
        List<Fact> facts = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            facts.add(new Fact("Old trivia number " + i + ".", 2, i, 30 * DAY, Fact.COLONY_CHAT, false, ""));
        }
        facts.add(new Fact("Max promised Fiona a house by the lake.", 2, 25, 30 * DAY, Fact.privateTalk(MAX), false,
                "p:" + MAX));
        facts.add(new Fact("The mill wheel broke during the storm.", 2, 26, 30 * DAY, Fact.COLONY_CHAT, false, "colony"));
        facts.add(new Fact("A raid destroyed the old granary.", 5, 27, 30 * DAY, Fact.COLONY_CHAT, false, "colony"));
        List<Fact> shown = Digests.relevant(facts, Set.of("p:" + MAX), "when will the mill be repaired?", 30, 6);
        assertEquals(6, shown.size());
        List<String> texts = shown.stream().map(Fact::text).toList();
        assertTrue(texts.contains("Max promised Fiona a house by the lake."), texts.toString());
        assertTrue(texts.contains("The mill wheel broke during the storm."), texts.toString());
        assertTrue(texts.contains("A raid destroyed the old granary."), texts.toString());
        // Still oldest first.
        assertEquals(shown.stream().sorted(java.util.Comparator.comparingLong(Fact::created)).toList(), shown);
    }
}

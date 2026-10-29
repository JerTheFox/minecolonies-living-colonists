package me.bytefox.livingcolonists.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

class FactsLogicTest {
    private static final long DAY = 24000;

    @Test
    void theDigestIsReadLineByLine() {
        List<Digests.Entry> entries = Digests.parse("""
                3 | Max promised to bring bread tomorrow.
                - 5 | Anna is expecting a child.
                nonsense line
                9 | Importance above five counts as five.
                0 | Below one counts as one.
                """, 10);
        assertEquals(4, entries.size());
        assertEquals(new Digests.Entry(3, "Max promised to bring bread tomorrow."), entries.get(0));
        assertEquals(5, entries.get(1).importance());
        assertEquals(5, entries.get(2).importance());
        assertEquals(1, entries.get(3).importance());
    }

    @Test
    void nothingToRememberGivesNoFacts() {
        assertTrue(Digests.parse("[nothing]", 5).isEmpty());
        assertTrue(Digests.parse("", 5).isEmpty());
    }

    @Test
    void theDigestIsCappedAtTheLimit() {
        assertEquals(2, Digests.parse("1 | a\n2 | b\n3 | c", 2).size());
    }

    @Test
    void importantFactsLiveLonger() {
        FactLimits limits = FactLimits.of(Level.DEFAULT);
        assertEquals(15, limits.facts());
        assertTrue(limits.lifetime(5) > limits.lifetime(3));
        assertTrue(limits.lifetime(3) > limits.lifetime(1));
        assertEquals(7 * DAY, limits.lifetime(3));
        assertEquals(0, FactLimits.of(Level.OFF).facts());
    }

    @Test
    void theSameFactIsNotWrittenTwice() {
        FactBook book = new FactBook();
        assertTrue(book.add(new Fact("Max promised bread.", 3, 0, DAY, Fact.COLONY_CHAT, false)));
        assertFalse(book.add(new Fact("max promised bread", 4, 10, DAY, Fact.COLONY_CHAT, false)));
        assertEquals(1, book.all().size());
        assertEquals(4, book.all().get(0).importance());
    }

    @Test
    void cleanupDropsExpiredFactsThenTheLeastImportant() {
        FactBook book = new FactBook();
        book.add(new Fact("old", 5, 0, 10, Fact.COLONY_CHAT, false));
        book.add(new Fact("trivia", 1, 5, 1000, Fact.COLONY_CHAT, false));
        book.add(new Fact("news", 3, 6, 1000, Fact.COLONY_CHAT, false));
        book.add(new Fact("big news", 5, 7, 1000, Fact.COLONY_CHAT, false));
        book.cleanup(20, 2);
        assertEquals(List.of("news", "big news"), book.all().stream().map(Fact::text).toList());
    }

    @Test
    void memoriesReadOldestFirstWithWhenAndWhere() {
        FactBook book = new FactBook();
        book.add(new Fact("Max promised bread.", 3, 0, 10 * DAY, Fact.privateTalk("Max"), false));
        book.add(new Fact("The baker is ill.", 2, 2 * DAY + 5, 10 * DAY, Fact.COLONY_CHAT, false));
        String text = Digests.memories(book.all(), 2 * DAY + 100);
        assertEquals("""
                Things you remember from earlier talks. They are background: bring one up only when the talk touches \
                it, and never recite them:
                - Max promised bread. (Max told you privately, 2 days ago)
                - The baker is ill. (said in the colony chat, today)""", text);
        assertEquals("", Digests.memories(List.of(), 0));
    }

    @Test
    void onlyTheMostImportantFactsGoIntoThePromptInTheirOrder() {
        List<Fact> facts = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            facts.add(new Fact("fact " + i, i % 5 == 0 ? 5 : 2, i, DAY, Fact.COLONY_CHAT, false));
        }
        List<Fact> shown = Digests.mostImportant(facts, 8);
        assertEquals(8, shown.size());
        assertEquals(List.of("fact 0", "fact 5", "fact 10", "fact 15", "fact 20", "fact 25", "fact 28", "fact 29"),
                shown.stream().map(Fact::text).toList());
        assertEquals(3, Digests.mostImportant(facts.subList(0, 3), 8).size());
    }

    @Test
    void gossipComesFromSomeoneElsesPrivateTalkAndIsToldOnce() {
        FactBook book = new FactBook();
        book.add(new Fact("Max is building a tower.", 3, 0, DAY, Fact.privateTalk("Max"), false));
        book.add(new Fact("Anna likes fish.", 2, 0, DAY, Fact.privateTalk("Anna"), false));
        book.add(new Fact("Trivia from Anna.", 1, 0, DAY, Fact.privateTalk("Anna"), false));
        book.add(new Fact("Everyone knows this.", 4, 0, DAY, Fact.COLONY_CHAT, false));
        Optional<Fact> rumor = Digests.pickRumor(book.all(), "Max", new Random(1));
        assertEquals("Anna likes fish.", rumor.orElseThrow().text());
        assertTrue(book.markTold(rumor.get()));
        assertTrue(Digests.pickRumor(book.all(), "Max", new Random(1)).isEmpty());
    }

    @Test
    void secretsAreNotPassedOnAsGossip() {
        List<Fact> facts = List.of(new Fact("Max plans a tavern (secret).", 4, 0, DAY, Fact.privateTalk("Max"), false),
                new Fact("Max plans a tower (Secret)", 4, 0, DAY, Fact.privateTalk("Max"), false));
        assertTrue(Digests.pickRumor(facts, "Anna", new Random(1)).isEmpty());
        assertEquals("""
                Things you remember from earlier talks. They are background: bring one up only when the talk touches \
                it, and never recite them:
                - Max plans a tavern. (Max told you in secret: keep it to yourself, whoever asks, unless Max brings it \
                up, today)""", Digests.memories(facts.subList(0, 1), 0));
    }

    @Test
    void factTextIsCleanedAndCapped() {
        assertEquals("Max said hi.", Digests.cleanFact("  §cMax\n said   hi. "));
        assertEquals(Digests.MAX_FACT_LENGTH, Digests.cleanFact("a".repeat(500)).length());
    }

    @Test
    void theNoteMarkMovesOnlyForward() {
        Transcript transcript = new Transcript();
        transcript.add(new MemoryLine(10, "Max", "one"), 10, DAY);
        transcript.add(new MemoryLine(20, "Anna", "two"), 10, DAY);
        assertEquals(2, transcript.undigested().size());
        transcript.markDigested(10);
        assertEquals(List.of("two"), transcript.undigested().stream().map(MemoryLine::text).toList());
        assertEquals(List.of("one"), transcript.digestedTail(4).stream().map(MemoryLine::text).toList());
        transcript.markDigested(5);
        assertEquals(1, transcript.undigested().size());
    }
}

package me.bytefox.livingcolonists.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.talk.LinePacing;
import org.junit.jupiter.api.Test;

class ConversationLogicTest {
    private static final long DAY = 24000;

    @Test
    void aTranscriptKeepsTheLatestLinesWithinItsLimit() {
        Transcript transcript = new Transcript();
        for (int i = 0; i < 12; i++) {
            transcript.add(new MemoryLine(i, "Max", "line " + i), 10, DAY);
        }
        List<MemoryLine> lines = transcript.lines(12, DAY);
        assertEquals(10, lines.size());
        assertEquals("line 2", lines.get(0).text());
        assertEquals("line 11", lines.get(9).text());
    }

    @Test
    void oldLinesFadeOut() {
        Transcript transcript = new Transcript();
        transcript.add(new MemoryLine(0, "Max", "old"), 10, DAY);
        transcript.add(new MemoryLine(DAY, "Max", "new"), 10, DAY);
        assertEquals(List.of("new"), transcript.lines(DAY + 10, DAY).stream().map(MemoryLine::text).toList());
    }

    @Test
    void memoryLevelsSetHowMuchIsKept() {
        assertEquals(0, MemoryLimits.of(Level.OFF).lines());
        assertTrue(MemoryLimits.of(Level.LOW).lines() < MemoryLimits.of(Level.DEFAULT).lines());
        assertTrue(MemoryLimits.of(Level.DEFAULT).lines() < MemoryLimits.of(Level.HIGH).lines());
        assertTrue(MemoryLimits.of(Level.HIGH).maxAgeTicks() > MemoryLimits.of(Level.DEFAULT).maxAgeTicks());
    }

    @Test
    void threadsGoOnLongerAtHigherLevelsAndAlwaysEnd() {
        assertEquals(0, ThreadPolicy.maxTurns(Level.OFF));
        assertTrue(ThreadPolicy.maxTurns(Level.HIGH) > ThreadPolicy.maxTurns(Level.DEFAULT));
        assertTrue(ThreadPolicy.continueChance(Level.HIGH, 1) > ThreadPolicy.continueChance(Level.LOW, 1));
        assertTrue(ThreadPolicy.continueChance(Level.DEFAULT, 5) < ThreadPolicy.continueChance(Level.DEFAULT, 1));
        assertEquals(0, ThreadPolicy.continueChance(Level.DEFAULT, ThreadPolicy.maxTurns(Level.DEFAULT)));
    }

    @Test
    void averageThreadLengthGrowsWithTheLevel() {
        Random random = new Random(11);
        double low = averageLength(Level.LOW, random);
        double high = averageLength(Level.HIGH, random);
        assertTrue(low < 2.2 && high > 4.5, low + " vs " + high);
    }

    private static double averageLength(Level level, Random random) {
        int total = 0;
        for (int i = 0; i < 2000; i++) {
            int turns = 1;
            while (random.nextDouble() < ThreadPolicy.continueChance(level, turns)) {
                turns++;
            }
            total += turns;
        }
        return total / 2000.0;
    }

    @Test
    void conversationsRunShortOrLongWithoutAFixedLength() {
        Random random = new Random(12);
        int[] lengths = new int[30];
        for (int i = 0; i < 3000; i++) {
            double energy = ThreadPolicy.startEnergy(Level.DEFAULT, random);
            int turns = 0;
            do {
                turns++;
                energy = ThreadPolicy.afterLine(energy, new ThreadPolicy.LineTraits(random.nextDouble() < 0.3,
                        random.nextDouble() < 0.2, random.nextDouble() < 0.2, random.nextDouble() < 0.2), random);
            } while (ThreadPolicy.goesOn(energy, turns, Level.DEFAULT));
            lengths[Math.min(turns, 29)]++;
        }
        assertTrue(lengths[1] > 300, "dies at once now and then: " + lengths[1]);
        int longOnes = 0;
        for (int i = 6; i < 30; i++) {
            longOnes += lengths[i];
        }
        assertTrue(longOnes > 150 && longOnes < 1200, "long talks: " + longOnes);
        assertEquals(0, lengths[ThreadPolicy.hardCap(Level.DEFAULT) + 1]);
    }

    @Test
    void questionsAndArgumentsKeepATalkGoing() {
        Random random = new Random(1);
        double plain = ThreadPolicy.afterLine(3, new ThreadPolicy.LineTraits(false, false, false, false), random);
        double lively = ThreadPolicy.afterLine(3, new ThreadPolicy.LineTraits(true, true, true, false), new Random(1));
        double flat = ThreadPolicy.afterLine(3, new ThreadPolicy.LineTraits(false, false, false, true), new Random(1));
        assertTrue(lively > plain && plain > flat, flat + " < " + plain + " < " + lively);
    }

    @Test
    void neighboursAndCoworkersJoinMoreButNobodyIsShutOut() {
        assertTrue(ThreadPolicy.joinWeight(1, 5, false, 0) > ThreadPolicy.joinWeight(1, 100, false, 0));
        assertTrue(ThreadPolicy.joinWeight(1, 30, true, 0) > ThreadPolicy.joinWeight(1, 30, false, 0));
        assertTrue(ThreadPolicy.joinWeight(1, 5, false, 3) < ThreadPolicy.joinWeight(1, 5, false, 0));
        assertEquals(ThreadPolicy.MIN_WEIGHT, ThreadPolicy.joinWeight(0.01, -1, false, 9));
    }

    @Test
    void theChannelLeansAgainstAStreakAndTowardPrivateProblems() {
        assertEquals(0.5, ThreadPolicy.colonyChance(false, 0));
        assertTrue(ThreadPolicy.colonyChance(true, 0) < 0.5);
        assertTrue(ThreadPolicy.colonyChance(false, 3) < ThreadPolicy.colonyChance(false, 1));
        assertTrue(ThreadPolicy.colonyChance(false, -3) > 0.5);
        assertTrue(ThreadPolicy.colonyChance(false, -3) < 0.8);
    }

    @Test
    void othersRarelyJoinWhileAPlayerTalks() {
        Random random = new Random(5);
        double chatter = averageLength(Level.DEFAULT, ThreadPolicy.Kind.CHATTER, random);
        double talk = averageLength(Level.DEFAULT, ThreadPolicy.Kind.TALK, random);
        double business = averageLength(Level.DEFAULT, ThreadPolicy.Kind.BUSINESS, random);
        assertTrue(business < talk && talk < chatter, business + " < " + talk + " < " + chatter);
        assertTrue(talk < 1.5 && business < 1.25, talk + ", " + business);
        assertEquals(0, ThreadPolicy.continueChance(Level.OFF, 1, ThreadPolicy.Kind.TALK));
    }

    private static double averageLength(Level level, ThreadPolicy.Kind kind, Random random) {
        int total = 0;
        for (int i = 0; i < 4000; i++) {
            int turns = 1;
            while (random.nextDouble() < ThreadPolicy.continueChance(level, turns, kind)) {
                turns++;
            }
            total += turns;
        }
        return total / 4000.0;
    }

    @Test
    void speakersArePickedByWeightAndNeverTwiceInARow() {
        Random random = new Random(2);
        int chatty = 0;
        for (int i = 0; i < 1000; i++) {
            String next = ThreadPolicy.pickSpeaker(List.of(
                    new ThreadPolicy.Candidate<>("chatty", 9),
                    new ThreadPolicy.Candidate<>("quiet", 1),
                    new ThreadPolicy.Candidate<>("last", 100)), "last", random).orElseThrow();
            assertFalse(next.equals("last"));
            if (next.equals("chatty")) {
                chatty++;
            }
        }
        assertTrue(chatty > 800, String.valueOf(chatty));
    }

    @Test
    void typingTakesLongerForLongerLines() {
        assertTrue(LinePacing.typingTicks(300) > LinePacing.typingTicks(20));
        assertTrue(LinePacing.typingTicks(5000) <= LinePacing.MAX_TYPING_TICKS);
        assertTrue(LinePacing.typingTicks(0) >= LinePacing.MIN_TYPING_TICKS);
    }
}

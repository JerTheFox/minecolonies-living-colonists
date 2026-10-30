package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

class InitiativePolicyTest {
    private static final InitiativePolicy.Topic HOME =
            new InitiativePolicy.Topic(InitiativePolicy.Kind.COMPLAINT, "need:home", "you have no home", 0);
    private static final InitiativePolicy.Topic CHAT =
            new InitiativePolicy.Topic(InitiativePolicy.Kind.SMALL_TALK, "topic:a dream", "a dream you had", 0);

    @Test
    void theBudgetRefillsAsTheWindowMoves() {
        InitiativePolicy.Budget budget = new InitiativePolicy.Budget();
        long window = InitiativePolicy.window(Level.DEFAULT);
        assertTrue(budget.allows(0, Level.DEFAULT));
        budget.spend(0);
        budget.spend(10);
        assertFalse(budget.allows(20, Level.DEFAULT));
        assertTrue(budget.allows(window + 11, Level.DEFAULT));
        assertFalse(new InitiativePolicy.Budget().allows(0, Level.OFF));
    }

    @Test
    void pausesAreSpreadWideSoTheyDontTick() {
        Random random = new Random(2);
        long min = Long.MAX_VALUE;
        long max = 0;
        long total = 0;
        for (int i = 0; i < 2000; i++) {
            long gap = InitiativePolicy.gap(Level.DEFAULT, random);
            min = Math.min(min, gap);
            max = Math.max(max, gap);
            total += gap;
        }
        long mean = InitiativePolicy.meanGap(Level.DEFAULT);
        assertTrue(min < mean / 2 && max > mean * 2, min + " .. " + max);
        assertTrue(min >= 2 * InitiativePolicy.MINUTE);
        assertTrue(total / 2000 > mean * 0.9 && total / 2000 < mean * 1.5, "mean " + total / 2000);
        assertTrue(InitiativePolicy.gap(Level.HIGH, new Random(1)) < InitiativePolicy.gap(Level.LOW, new Random(1)));
    }

    @Test
    void higherLevelsTalkMore() {
        assertTrue(InitiativePolicy.budget(Level.HIGH) > InitiativePolicy.budget(Level.DEFAULT));
        assertTrue(InitiativePolicy.budget(Level.DEFAULT) > InitiativePolicy.budget(Level.LOW));
        assertTrue(InitiativePolicy.perColonist(Level.HIGH) < InitiativePolicy.perColonist(Level.DEFAULT));
    }

    @Test
    void realProblemsComeUpMostAndDislikeKeepsToThem() {
        Random random = new Random(1);
        int complaints = 0;
        int coldComplaints = 0;
        for (int i = 0; i < 2000; i++) {
            complaints += InitiativePolicy.pick(List.of(HOME, CHAT), 0, random).orElseThrow() == HOME ? 1 : 0;
            coldComplaints += InitiativePolicy.pick(List.of(HOME, CHAT), -60, random).orElseThrow() == HOME ? 1 : 0;
        }
        assertTrue(complaints > 1350 && complaints < 1650, "complaints: " + complaints);
        assertTrue(coldComplaints > complaints, coldComplaints + " vs " + complaints);
        assertTrue(InitiativePolicy.pick(List.of(), 0, random).isEmpty());
    }

    @Test
    void everybodyGetsTurnsNotJustTheTalkative() {
        Random random = new Random(6);
        double[] talk = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8, 0.9};
        long[] last = new long[talk.length];
        java.util.Arrays.fill(last, -1);
        int[] picks = new int[talk.length];
        long now = 0;
        for (int round = 0; round < 400; round++) {
            now += 12 * InitiativePolicy.MINUTE;
            double total = 0;
            double[] weights = new double[talk.length];
            for (int i = 0; i < talk.length; i++) {
                weights[i] = InitiativePolicy.speakerWeight(talk[i], last[i] < 0 ? -1 : now - last[i], 0);
                total += weights[i];
            }
            double roll = random.nextDouble() * total;
            int chosen = talk.length - 1;
            for (int i = 0; i < talk.length; i++) {
                roll -= weights[i];
                if (roll < 0) {
                    chosen = i;
                    break;
                }
            }
            picks[chosen]++;
            last[chosen] = now;
        }
        int min = java.util.Arrays.stream(picks).min().orElseThrow();
        int max = java.util.Arrays.stream(picks).max().orElseThrow();
        assertTrue(min >= 25, "the quietest got " + min + " of 400");
        assertTrue(max <= 3 * min, java.util.Arrays.toString(picks));
        assertTrue(picks[7] > picks[0], "the talkative still speak a bit more: " + java.util.Arrays.toString(picks));
    }

    @Test
    void aColonistWhoComplainedLatelyChatsInstead() {
        Random random = new Random(8);
        int complaints = 0;
        for (int i = 0; i < 2000; i++) {
            complaints += InitiativePolicy.pick(List.of(HOME, CHAT), 0, true, random).orElseThrow() == HOME ? 1 : 0;
        }
        assertTrue(complaints < 1000, "complaints: " + complaints);
    }

    @Test
    void complaintsGrowSharperThenTheColonistGivesUp() {
        assertTrue(InitiativePolicy.complaint("no home", 0).contains("helpfully"));
        assertTrue(InitiativePolicy.complaint("no home", 1).contains("again"));
        assertTrue(InitiativePolicy.complaint("no home", 2).contains("fed up"));
        assertTrue(InitiativePolicy.complaint("no home", 3).contains("won't bother"));
        assertEquals(InitiativePolicy.complaint("no home", 3), InitiativePolicy.complaint("no home", 9));
    }
}

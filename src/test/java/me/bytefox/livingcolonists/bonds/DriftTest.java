package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class DriftTest {
    @Test
    void sharedLifeMovesAPairTheWayTheirChemistryGoes() {
        assertTrue(Drift.daily(true, false, true, 0.5, 0) > 0, "they hit it off");
        assertTrue(Drift.daily(true, true, true, 0.5, 0) > Drift.daily(true, false, true, 0.5, 0), "more together, faster");
        assertTrue(Drift.daily(true, false, true, -0.9, 0) < 0, "some pairs just don't get on, even easy-going ones");
        assertEquals(0, Drift.daily(true, false, true, -0.2, 0), "most of the rest stay as they are");
        assertTrue(Drift.daily(false, true, false, 0.0, 0) < 0, "two contrary characters under one roof grate on each other");
        assertEquals(0, Drift.daily(false, false, true, 0.9, 0), "nothing in common, nothing changes");
    }

    @Test
    void driftAloneGoesNoFurtherThanTheCap() {
        assertEquals(0, Drift.daily(true, true, true, 0.9, Drift.CAP));
        assertEquals(0, Drift.daily(true, true, false, -0.9, -Drift.CAP));
        assertTrue(Drift.daily(true, true, true, -0.9, Drift.CAP) < 0, "a friendship can still wear thin");
    }

    @Test
    void chemistryIsTheSameForAPairEveryTimeAndSpreadOut() {
        assertEquals(Drift.chemistry("1:2:3"), Drift.chemistry("1:2:3"));
        long up = java.util.stream.IntStream.range(0, 400).filter(i -> Drift.chemistry("1:" + i + ":" + (i + 1)) > 0).count();
        assertTrue(up > 150 && up < 250, "about half the pairs: " + up);
        assertTrue(java.util.stream.IntStream.range(0, 400).mapToDouble(i -> Drift.chemistry("7:" + i + ":9"))
                .allMatch(c -> c >= -1 && c <= 1));
    }

    @Test
    void crossingAThresholdIsNewsOnceEachWay() {
        assertEquals(Optional.of(Drift.Turn.FRIENDS), Drift.crossed(Drift.FRIENDS - 1, Drift.FRIENDS));
        assertEquals(Optional.empty(), Drift.crossed(Drift.FRIENDS, Drift.FRIENDS + 2));
        assertEquals(Optional.of(Drift.Turn.AT_ODDS), Drift.crossed(-Drift.FRIENDS + 1, -Drift.FRIENDS));
        assertEquals(Optional.empty(), Drift.crossed(10, 12));
    }
}

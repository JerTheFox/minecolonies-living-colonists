package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class DriftTest {
    @Test
    void workingTogetherBringsCloserLivingTogetherDependsOnCharacters() {
        assertTrue(Drift.daily(true, false, true) > 0);
        assertTrue(Drift.daily(false, true, true) > 0);
        assertTrue(Drift.daily(false, true, false) < 0, "two contrary characters under one roof grate on each other");
        assertEquals(0, Drift.daily(false, false, true));
    }

    @Test
    void crossingAThresholdIsNewsOnceEachWay() {
        assertEquals(Optional.of(Drift.Turn.FRIENDS), Drift.crossed(Drift.FRIENDS - 1, Drift.FRIENDS));
        assertEquals(Optional.empty(), Drift.crossed(Drift.FRIENDS, Drift.FRIENDS + 2));
        assertEquals(Optional.of(Drift.Turn.AT_ODDS), Drift.crossed(-Drift.FRIENDS + 1, -Drift.FRIENDS));
        assertEquals(Optional.empty(), Drift.crossed(10, 12));
    }
}

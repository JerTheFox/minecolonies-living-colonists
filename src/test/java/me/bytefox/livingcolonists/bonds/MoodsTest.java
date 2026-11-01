package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** How much a colonist's happiness moves from how players treat them and who they live and work with (#61). */
class MoodsTest {
    @Test
    void kindnessLiftsRudenessDragsAndABrokenPromiseDragsMore() {
        assertTrue(Moods.treatment(1.0, Bonds.KIND) > 1.0);
        assertTrue(Moods.treatment(1.0, Bonds.RUDE) < 1.0);
        assertTrue(Moods.treatment(1.0, Bonds.brokenDelta(3)) < Moods.treatment(1.0, Bonds.RUDE), "a broken promise hurts more");
        assertEquals(1.0, Moods.treatment(1.0, 0));
    }

    @Test
    void manyDaysOfKindnessOrRudenessStayWithinBounds() {
        double mood = 1.0;
        for (int i = 0; i < 50; i++) {
            mood = Moods.treatment(mood, Bonds.KIND);
        }
        assertEquals(Moods.MAX, mood, 1e-9);
        for (int i = 0; i < 50; i++) {
            mood = Moods.treatment(mood, Bonds.RUDE);
        }
        assertEquals(Moods.MIN, mood, 1e-9);
    }

    @Test
    void friendsNearbyLiftQuarrelsDragNobodyLeavesItAlone() {
        assertEquals(Optional.empty(), Moods.neighbours(0, 0));
        assertTrue(Moods.neighbours(1, 0).orElseThrow() > 1.0);
        assertTrue(Moods.neighbours(0, 1).orElseThrow() < 1.0);
        assertTrue(Moods.neighbours(3, 0).orElseThrow() <= Moods.MAX);
        assertTrue(Moods.neighbours(0, 5).orElseThrow() >= Moods.MIN);
    }
}

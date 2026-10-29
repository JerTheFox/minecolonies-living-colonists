package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class LinePacingTest {
    @Test
    void aFastReplyWaitsForTheTypingPause() {
        assertEquals(160, LinePacing.slot(120, 160, 0));
    }

    @Test
    void aSlowReplyGoesOutAsSoonAsItIsReady() {
        assertEquals(300, LinePacing.slot(300, 160, 0));
    }

    @Test
    void aLineWaitsForThePauseAfterThePreviousColonyLine() {
        assertEquals(350, LinePacing.slot(300, 160, 350));
    }

    @Test
    void pausesAreTwoToFourSeconds() {
        Random random = new Random(7);
        for (int i = 0; i < 100; i++) {
            long gap = LinePacing.gap(random);
            assertTrue(gap >= LinePacing.MIN_GAP_TICKS && gap <= LinePacing.MAX_GAP_TICKS, String.valueOf(gap));
        }
    }
}

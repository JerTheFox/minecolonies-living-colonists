package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

class AirtimeTest {
    @Test
    void smallTalkGivesWayBeforeNews() {
        Airtime airtime = new Airtime();
        long now = 0;
        int chatter = 0;
        while (airtime.allows(Airtime.Kind.CHATTER, "c:1", now, Level.DEFAULT)) {
            airtime.spend("c:1", now);
            chatter++;
        }
        assertTrue(chatter > 0 && chatter < Airtime.CAPACITY, "chatter " + chatter);
        assertTrue(airtime.allows(Airtime.Kind.NEWS, "c:1", now, Level.DEFAULT));
        assertTrue(airtime.allows(Airtime.Kind.NOTE, "c:1", now, Level.DEFAULT));
    }

    @Test
    void urgentNewsAlwaysGoesThrough() {
        Airtime airtime = new Airtime();
        for (int i = 0; i < 20; i++) {
            assertTrue(airtime.allows(Airtime.Kind.URGENT, "c:1", 0, Level.LOW));
            airtime.spend("c:1", 0);
        }
        assertFalse(airtime.allows(Airtime.Kind.NOTE, "c:1", 0, Level.LOW));
    }

    @Test
    void theBudgetComesBackWithTime() {
        Airtime airtime = new Airtime();
        while (airtime.allows(Airtime.Kind.CHATTER, "p:x", 0, Level.DEFAULT)) {
            airtime.spend("p:x", 0);
        }
        assertFalse(airtime.allows(Airtime.Kind.CHATTER, "p:x", 0, Level.DEFAULT));
        assertTrue(airtime.allows(Airtime.Kind.CHATTER, "p:x", 3 * Airtime.refillTicks(Level.DEFAULT), Level.DEFAULT));
        assertTrue(Airtime.refillTicks(Level.HIGH) < Airtime.refillTicks(Level.DEFAULT));
    }
}

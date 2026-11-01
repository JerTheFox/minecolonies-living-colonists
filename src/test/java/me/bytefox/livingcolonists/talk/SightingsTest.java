package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SightingsTest {
    private static Sightings.Group group(String key, int count, boolean attacking) {
        return new Sightings.Group(key, count + " Zombie by the " + key, count, attacking, 1, 0, 0);
    }

    @Test
    void aLoneMobIsNotNewsUnlessItAttacks() {
        Sightings sightings = new Sightings();
        assertTrue(sightings.worthTelling(1, List.of(group("farm", 1, false)), 0).isEmpty());
        assertEquals(1, sightings.worthTelling(1, List.of(group("farm", 1, true)), 0).size());
        assertEquals(1, new Sightings().worthTelling(1, List.of(group("farm", 2, false)), 0).size());
    }

    @Test
    void onePlaceStaysQuietForAWhileAndTheColonyHearsOneAtATime() {
        Sightings sightings = new Sightings();
        List<Sightings.Group> told = sightings.worthTelling(1, List.of(group("farm", 2, false), group("mill", 5, false)), 0);
        assertEquals(List.of("mill"), told.stream().map(Sightings.Group::key).toList(), "the biggest first, one at a time");
        assertTrue(sightings.worthTelling(1, List.of(group("farm", 2, false)), Sightings.COLONY_GAP_TICKS - 1).isEmpty());
        assertEquals(1, sightings.worthTelling(1, List.of(group("farm", 2, false)), Sightings.COLONY_GAP_TICKS).size());
        assertTrue(sightings.worthTelling(1, List.of(group("mill", 5, false)), Sightings.PLACE_QUIET_TICKS - 1).isEmpty());
        assertEquals(1, sightings.worthTelling(1, List.of(group("mill", 5, false)), Sightings.PLACE_QUIET_TICKS).size());
        assertEquals(1, sightings.worthTelling(2, List.of(group("mill", 5, false)), 10).size(), "another colony");
    }

    @Test
    void theLastRaidersAreToldOnceAfterAMinute() {
        Sightings sightings = new Sightings();
        assertFalse(sightings.stragglers(1, true, 8, 0));
        assertFalse(sightings.stragglers(1, true, 2, 100));
        assertFalse(sightings.stragglers(1, true, 2, 100 + Sightings.STRAGGLERS_AFTER_TICKS - 1));
        assertTrue(sightings.stragglers(1, true, 2, 100 + Sightings.STRAGGLERS_AFTER_TICKS));
        assertFalse(sightings.stragglers(1, true, 1, 100 + 2 * Sightings.STRAGGLERS_AFTER_TICKS), "once a raid");
        assertFalse(sightings.stragglers(1, false, 0, 10_000));
        assertFalse(sightings.stragglers(1, true, 3, 20_000));
        assertTrue(sightings.stragglers(1, true, 3, 20_000 + Sightings.STRAGGLERS_AFTER_TICKS), "the next raid again");
    }
}

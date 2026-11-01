package me.bytefox.livingcolonists.errands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CompassTest {
    @Test
    void namesTheWayAndTheDistance() {
        assertEquals("about 20 blocks north", Compass.way(0, -20));
        assertEquals("about 20 blocks south", Compass.way(0, 20));
        assertEquals("about 30 blocks east", Compass.way(30, 0));
        assertEquals("about 30 blocks west", Compass.way(-30, 0));
        assertEquals("about 14 blocks north-east", Compass.way(10, -10));
        assertEquals("about 14 blocks south-west", Compass.way(-10, 10));
        assertEquals("right here", Compass.way(2, 3));
    }
}

package me.bytefox.livingcolonists.mood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Lasting troubles that make a colony glum (#65): only when they touch a good part of it. */
class ConditionsTest {
    private static List<String> occasions(int adults, int hungry, int homeless, int unhappy) {
        return Conditions.troubles(adults, hungry, homeless, unhappy).stream().map(Conditions.Trouble::occasion).toList();
    }

    @Test
    void aThirdOfTheColonyHungryHomelessOrUnhappyMakesItGlum() {
        assertEquals(List.of("hunger"), occasions(9, 3, 0, 0));
        assertEquals(List.of("homeless"), occasions(9, 0, 4, 0));
        assertEquals(List.of("unhappy"), occasions(9, 0, 0, 5));
        assertEquals(List.of("hunger", "homeless", "unhappy"), occasions(9, 9, 9, 9));
    }

    @Test
    void oneOrTwoInABigColonyOrNobodyAtAllIsNoTrouble() {
        assertTrue(occasions(12, 2, 2, 2).isEmpty());
        assertTrue(occasions(0, 0, 0, 0).isEmpty());
        assertTrue(occasions(3, 1, 0, 0).isEmpty(), "a single hungry colonist in a small colony");
    }

    @Test
    void theCauseSaysHowMany() {
        assertTrue(Conditions.troubles(9, 3, 0, 0).get(0).cause().contains("3 of 9"));
    }
}

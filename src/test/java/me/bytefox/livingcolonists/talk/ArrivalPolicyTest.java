package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

class ArrivalPolicyTest {
    private static ArrivalPolicy.Visit visit(boolean session, long away) {
        return new ArrivalPolicy.Visit(session, away, List.of(), false, null, null, false, List.of());
    }

    @Test
    void steppingOverTheBorderAndBackIsNoArrival() {
        assertFalse(ArrivalPolicy.qualifies(visit(false, 600)));
        assertTrue(ArrivalPolicy.qualifies(visit(true, 600)));
        assertTrue(ArrivalPolicy.qualifies(visit(false, ArrivalPolicy.AWAY)));
    }

    @Test
    void aTripOrADeathMakesAShortAbsenceCount() {
        var trip = new ArrivalPolicy.Visit(false, ArrivalPolicy.MIN_AWAY, List.of(), false, "the Nether", null, false,
                List.of());
        var died = new ArrivalPolicy.Visit(false, ArrivalPolicy.MIN_AWAY, List.of(), true, null, null, false, List.of());
        assertTrue(ArrivalPolicy.qualifies(trip));
        assertTrue(ArrivalPolicy.qualifies(died));
        assertFalse(ArrivalPolicy.qualifies(new ArrivalPolicy.Visit(false, 100, List.of(), true, null, null, false,
                List.of())));
    }

    @Test
    void withoutAReasonAGreetingIsRare() {
        assertFalse(ArrivalPolicy.hasReason(visit(true, 600)));
        assertTrue(ArrivalPolicy.chance(Level.DEFAULT, false) < 0.1);
        assertTrue(ArrivalPolicy.chance(Level.DEFAULT, true) >= 0.4);
        assertEquals(0, ArrivalPolicy.chance(Level.OFF, true));
    }

    @Test
    void newsComesFirstWithOneMoreThing() {
        var visit = new ArrivalPolicy.Visit(true, 3 * ArrivalPolicy.AWAY, List.of("The Tavern was finished."), true,
                "the Nether", null, false, List.of());
        String purpose = ArrivalPolicy.purpose(visit, "Alex");
        assertTrue(purpose.contains("The Tavern was finished."), purpose);
        assertTrue(purpose.contains("died"), purpose);
        assertFalse(purpose.contains("Nether"), purpose);
        assertFalse(purpose.contains("3 days"), purpose);
    }

    @Test
    void aPlainGreetingSaysThereIsNothingMore() {
        String purpose = ArrivalPolicy.purpose(visit(true, 600), "Alex");
        assertTrue(purpose.contains("Nothing more to it"), purpose);
        assertTrue(ArrivalPolicy.purpose(visit(false, 2 * ArrivalPolicy.AWAY), "Alex").contains("2 days"));
    }

    @Test
    void eventsAreToldInThePast() {
        assertEquals("The Tavern was finished.", ArrivalPolicy.past("The Tavern was just finished."));
        assertEquals("Ann has arrived in the colony as a new colonist.",
                ArrivalPolicy.past("Ann has just arrived in the colony as a new colonist."));
        assertEquals("Raiders attacked the colony.", ArrivalPolicy.past("Raiders are attacking the colony right now!"));
        assertNull(ArrivalPolicy.past("The raid on the colony is over."));
    }

    @Test
    void otherWorldsHaveNames() {
        assertEquals("the Nether", ArrivalPolicy.world("minecraft:the_nether"));
        assertEquals("the End", ArrivalPolicy.world("minecraft:the_end"));
        assertEquals("a far-off place", ArrivalPolicy.world("twilightforest:twilight_forest"));
        assertNull(ArrivalPolicy.world("minecraft:overworld"));
    }

    @Test
    void aPlayerLooksAsTheyAre() {
        assertEquals("", ArrivalPolicy.look("Alex", null, null, 1));
        String text = ArrivalPolicy.look("Alex", "Diamond Sword", "Iron Chestplate", 0.2);
        assertTrue(text.contains("holds Diamond Sword"), text);
        assertTrue(text.contains("wears Iron Chestplate"), text);
        assertTrue(text.contains("badly hurt"), text);
        assertTrue(ArrivalPolicy.look("Alex", null, null, 0.4).contains("looks hurt"));
    }

    @Test
    void aDeedIsHearsay() {
        var visit = new ArrivalPolicy.Visit(false, ArrivalPolicy.MIN_AWAY, List.of(), false, null, null, false,
                List.of("found diamonds", "killed the Ender Dragon"));
        assertTrue(ArrivalPolicy.qualifies(visit));
        String purpose = ArrivalPolicy.purpose(visit, "Alex");
        assertTrue(purpose.contains("Word has it they killed the Ender Dragon"), purpose);
    }
}

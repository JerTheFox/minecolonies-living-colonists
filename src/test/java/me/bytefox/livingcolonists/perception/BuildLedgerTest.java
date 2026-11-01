package me.bytefox.livingcolonists.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BuildLedgerTest {
    @Test
    void usedIsCountedFromTheMostSeenNeeded() {
        BuildLedger ledger = new BuildLedger();
        ledger.lines("tavern", List.of(new BuildLedger.Material("Spruce Stairs", 106, 0, 0),
                new BuildLedger.Material("Spruce Log", 30, 30, 0)), 5);
        List<String> later = ledger.lines("tavern", List.of(new BuildLedger.Material("Spruce Stairs", 40, 0, 20),
                new BuildLedger.Material("Spruce Log", 10, 10, 0)), 5);
        assertEquals(List.of("Spruce Stairs: 66 of 106 used, 0 at hand, 20 on the way, 20 still to bring",
                "Spruce Log: 20 of 30 used, 10 at hand, nothing more to bring"), later);
        assertEquals(63, ledger.percentUsed("tavern", List.of(new BuildLedger.Material("Spruce Stairs", 40, 0, 20),
                new BuildLedger.Material("Spruce Log", 10, 10, 0))));
    }

    @Test
    void aFreshOrderShowsWhatItNeeds() {
        assertEquals(List.of("Cobblestone: 16 needed, 4 at hand, 12 still to bring"),
                new BuildLedger().lines("house", List.of(new BuildLedger.Material("Cobblestone", 16, 4, 0)), 5));
    }
}

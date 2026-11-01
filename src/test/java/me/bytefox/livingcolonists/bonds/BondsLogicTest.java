package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import me.bytefox.livingcolonists.memory.Digests;
import org.junit.jupiter.api.Test;

class BondsLogicTest {
    private static final long DAY = 24000;
    private static final UUID MAX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Promise promise(long id, UUID maker, String name, String text, long made, long due) {
        return new Promise(id, maker, name, text, 0, Promise.About.GENERAL, made, due, Promise.Status.OPEN, 0, false, false, "");
    }

    @Test
    void theDigestCarriesPromisesResolutionsAndConduct() {
        Digests.Digest digest = Digests.parseAll("""
                3 | Max said the oven is cold.
                promise | 1 | supply | Max promised to bring 10 oak planks.
                promise | 0 | housing | Max promised to build Anna a house.
                promise | x | weird | broken line
                kept | 2
                broken | 1
                cancelled | 9
                kind | Max brought flowers.
                rude | Max called Anna lazy.
                """, 5);
        assertEquals(1, digest.facts().size());
        assertEquals(List.of(new Digests.NewPromise(1, "supply", 0, "Max promised to bring 10 oak planks."),
                new Digests.NewPromise(0, "housing", 0, "Max promised to build Anna a house.")), digest.promises());
        assertEquals(List.of(new Digests.Resolution("kept", 2), new Digests.Resolution("broken", 1),
                new Digests.Resolution("cancelled", 9)), digest.resolutions());
        assertEquals(List.of(new Digests.Conduct(true, "Max brought flowers."),
                new Digests.Conduct(false, "Max called Anna lazy.")), digest.conduct());
    }

    @Test
    void theColonyDigestCarriesHowColonistsGetAlong() {
        Digests.Digest digest = Digests.parseAll("""
                friendly | Anna Becker | Oleg Petrov | Oleg backed Anna up about the fields.
                tension | Idris Kowalski | Anna Becker | Idris mocked Anna's harvest.
                tension | Idris Kowalski | broken line
                """, 5);
        assertEquals(List.of(new Digests.ColonistBond(true, "Anna Becker", "Oleg Petrov", "Oleg backed Anna up about the fields."),
                new Digests.ColonistBond(false, "Idris Kowalski", "Anna Becker", "Idris mocked Anna's harvest.")),
                digest.colonists());
    }

    @Test
    void theColonyDigestCarriesPromisesToNamedColonists() {
        Digests.Digest digest = Digests.parseAll("""
                promise | Alex | Idris Y. Crawley | 0 | supply | Alex promised Idris a couple of diamonds.
                promise | 1 | supply | A private-talk style line still counts as a promise without names.
                kept | 1
                """, 5);
        assertEquals(List.of(new Digests.ColonyPromise("Alex", "Idris Y. Crawley", 0, "supply", 0,
                "Alex promised Idris a couple of diamonds.")), digest.colonyPromises());
        assertEquals(1, digest.promises().size());
        assertEquals(List.of(new Digests.Resolution("kept", 1)), digest.resolutions());
    }

    @Test
    void colonistsArgueMoreWithThoseTheyDislike() {
        assertEquals(0.35, Bonds.contrarinessWith(0.35, 0), 1e-9);
        assertTrue(Bonds.contrarinessWith(0.35, -30) > 0.35);
        assertTrue(Bonds.contrarinessWith(0.35, 30) < 0.35);
        assertTrue(Bonds.contrarinessWith(0.8, -100) <= 0.9);
        assertTrue(Bonds.contrarinessWith(0.1, 100) >= 0);
        assertEquals(1.0, Bonds.replyWeight(0), 1e-9);
        assertTrue(Bonds.replyWeight(-30) > 1 && Bonds.replyWeight(30) > 1);
    }

    @Test
    void howAColonistGetsAlongWithOthersReadsAsAShortLine() {
        Relation friends = Relation.EMPTY.with(Bonds.KIND, "Oleg backed Anna up.", 0).with(Bonds.KIND, "x", 0)
                .with(Bonds.KIND, "Oleg shared his lunch.", 0).with(Bonds.KIND, "y", 0);
        assertEquals("Oleg Petrov: you are good friends (lately: y)", Bonds.colonistLine("Oleg Petrov", friends));
        assertEquals("", Bonds.colonistLine("Oleg Petrov", Relation.EMPTY));
    }

    @Test
    void aRequestThatWasDeliveredKeepsThePromiseAndOneCancelledDropsIt() {
        assertEquals(Optional.empty(), Bonds.requestOutcome("ASSIGNED", "CREATED"));
        assertEquals(Optional.of(Promise.Status.KEPT), Bonds.requestOutcome("COMPLETED", "IN_PROGRESS"));
        assertEquals(Optional.of(Promise.Status.KEPT), Bonds.requestOutcome("RECEIVED", "COMPLETED"));
        assertEquals(Optional.of(Promise.Status.CANCELLED), Bonds.requestOutcome("CANCELLED", "ASSIGNED"));
        assertEquals(Optional.of(Promise.Status.CANCELLED), Bonds.requestOutcome("FAILED", "IN_PROGRESS"));
        // Gone from the request manager: delivered, unless the last state seen was a cancellation.
        assertEquals(Optional.of(Promise.Status.KEPT), Bonds.requestOutcome(null, "IN_PROGRESS"));
        assertEquals(Optional.of(Promise.Status.CANCELLED), Bonds.requestOutcome(null, "OVERRULED"));
    }

    @Test
    void cancellingAPromisedRequestIsOnlyABreachOnceItIsOverdue() {
        Promise due = promise(1, MAX, "Max", "a pickaxe", 0, DAY);
        assertEquals(Promise.Status.CANCELLED, Bonds.cancelledByMaker(due, DAY));
        assertEquals(Promise.Status.BROKEN, Bonds.cancelledByMaker(due, DAY + 1));
    }

    @Test
    void aTrifleKeptIsWorthNothingAndSomethingPreciousALot() {
        assertEquals(0, Bonds.keptDelta(1));
        assertTrue(Bonds.keptDelta(5) > Bonds.keptDelta(3) && Bonds.keptDelta(3) > Bonds.keptDelta(2));
        assertTrue(Bonds.brokenDelta(1) < 0 && Bonds.brokenDelta(5) < Bonds.brokenDelta(1));
        assertEquals(Bonds.keptDelta(2), Bonds.keptDelta(0), "an unknown worth counts as ordinary");
    }

    @Test
    void keptPromisesWarmAColonistAtMostOnceADay() {
        assertTrue(Bonds.mayCredit(Long.MIN_VALUE, 0));
        assertTrue(!Bonds.mayCredit(1000, 1000 + DAY - 1));
        assertTrue(Bonds.mayCredit(1000, 1000 + DAY));
    }

    @Test
    void theDigestReadsHowMuchAPromiseIsWorth() {
        Digests.Digest digest = Digests.parseAll("""
                promise | 1 | supply | 5 | Max promised Anna a stack of diamonds.
                promise | 0 | general | Max promised Anna a stick.
                promise | Max | Anna Becker | 1 | supply | 1 | Max promised Anna some dirt.
                """, 5);
        assertEquals(5, digest.promises().get(0).worth());
        assertEquals("Max promised Anna a stack of diamonds.", digest.promises().get(0).text());
        assertEquals(0, digest.promises().get(1).worth());
        assertEquals(1, digest.colonyPromises().get(0).worth());
        assertEquals("Max promised Anna some dirt.", digest.colonyPromises().get(0).text());
    }

    @Test
    void aColonistTurnsANewPromiseDownWhileAnEarlierOneIsOpen() {
        String line = Bonds.owesLine("Max", "Max promised Idris diamonds.", "Idris");
        assertTrue(line.startsWith("Max still hasn't kept an earlier promise to Idris"), line);
        assertTrue(line.contains("keep that one first"), line);
        assertTrue(Bonds.owesLine("Max", "x", null).contains("promise to you"));
    }

    @Test
    void topicsAreReadLeniently() {
        assertEquals(Promise.About.HOUSING, Promise.About.of("Housing"));
        assertEquals(Promise.About.GENERAL, Promise.About.of("something else"));
    }

    @Test
    void rapportMovesAndStaysInBounds() {
        Relation relation = Relation.EMPTY;
        relation = relation.with(Bonds.KIND, "brought flowers", 1);
        assertEquals(7, relation.rapport());
        relation = relation.with(Bonds.RUDE, "shouted", 2).with(Bonds.BROKEN, "broke a promise", 3);
        assertEquals(-12, relation.rapport());
        for (int i = 0; i < 30; i++) {
            relation = relation.with(Bonds.RUDE, "again", 4);
        }
        assertEquals(-100, relation.rapport());
        assertEquals(Relation.MAX_NOTES, relation.notes().size());
    }

    @Test
    void promisesPastTheirDeadlineAreBrokenAndOnesWithoutAreForgotten() {
        Promise due = promise(1, MAX, "Max", "bread", 0, 2 * DAY);
        assertEquals(Optional.empty(), Bonds.expiry(due, 2 * DAY + Bonds.GRACE_TICKS));
        assertEquals(Optional.of(Promise.Status.BROKEN), Bonds.expiry(due, 2 * DAY + Bonds.GRACE_TICKS + 1));
        Promise open = promise(2, MAX, "Max", "a house", 0, 0);
        assertEquals(Optional.empty(), Bonds.expiry(open, Bonds.NO_DEADLINE_TICKS));
        assertEquals(Optional.of(Promise.Status.CANCELLED), Bonds.expiry(open, Bonds.NO_DEADLINE_TICKS + 1));
    }

    @Test
    void buildingNamesAreFoundInPromises() {
        assertTrue(Bonds.mentionsBuilding("Max promised to build a guard tower by the river.", "Guard Tower"));
        assertTrue(Bonds.mentionsBuilding("Max will finish the builder's hut.", "Builder's Hut"));
        assertTrue(Bonds.mentionsBuilding("Max promised a tavern.", "Tavern"));
        assertFalse(Bonds.mentionsBuilding("Max promised a tower.", "Guard Tower"));
        assertFalse(Bonds.mentionsBuilding("Max promised bread.", "Bakery"));
        assertFalse(Bonds.mentionsBuilding("Max promised bread.", ""));
    }

    @Test
    void theBlockShowsOpenPromisesOverdueOnesAndTheRecord() {
        List<Promise> promises = List.of(
                promise(1, MAX, "Max", "Max promised to bring bread.", 0, DAY),
                promise(2, ANNA, "Anna", "Anna promised a new hoe.", 0, 0),
                new Promise(3, MAX, "Max", "Max promised a house.", 0, Promise.About.HOUSING, 0, 0, Promise.Status.KEPT,
                        2 * DAY, false, false, ""),
                new Promise(4, MAX, "Max", "Max promised fish.", 0, Promise.About.FOOD, 0, 0, Promise.Status.BROKEN,
                        0, false, false, ""));
        String block = Bonds.promisesBlock(promises, 2 * DAY + 10);
        assertEquals("""
                Promises players made to you:
                - Max promised to bring bread. (Max, 2 days ago, was due yesterday: overdue)
                - Anna promised a new hoe. (Anna, 2 days ago, no deadline)
                Recently kept: Max promised a house. You are grateful to Max.
                Keeping their word to you: Max kept 1 and broke 1.
                Remind, thank or blame only the one who made a promise, and don't bring up someone's promise with \
                anybody else unless they ask. While a promise is open and not overdue, don't complain about that \
                problem: you trust them and wait.""", block);
        assertEquals("", Bonds.promisesBlock(List.of(), 0));
    }

    @Test
    void openPromisesAreNumberedForTheDigest() {
        List<Promise> open = List.of(promise(7, MAX, "Max", "Max promised bread.", 0, DAY),
                promise(9, MAX, "Max", "Max promised a hoe.", 0, 0));
        assertEquals("1. Max promised bread. (due in 1 day)\n2. Max promised a hoe. (no deadline)",
                Bonds.numbered(open, 0));
        assertEquals("(none)", Bonds.numbered(List.of(), 0));
    }

    @Test
    void howAPlayerTreatsTheColonistShowsInTone() {
        Relation warm = Relation.EMPTY.with(Bonds.KIND, "Max brought flowers.", 0).with(Bonds.KEPT,
                "kept a promise to you", 0).with(Bonds.KIND, "Max helped.", 0).with(Bonds.KIND, "Max thanked you.", 0);
        String text = Bonds.relationBlock("Max", warm, 0);
        assertTrue(text.startsWith("How Max treats you: consistently kind and respectful"), text);
        assertTrue(text.contains("Max brought flowers. (today)"), text);
        assertEquals("", Bonds.relationBlock("Max", Relation.EMPTY, 0));
    }

    @Test
    void aRequestedAmountRangeBecomesTheFullAmount() {
        assertEquals("34 Cobblestone", Bonds.requestedThing("1-34 Cobblestone"));
        assertEquals("34 Cobblestone", Bonds.requestedThing("34 Cobblestone"));
        assertEquals("Food", Bonds.requestedThing("Food"));
    }

    @Test
    void theColonyTalksAboutPlayersWhoAreRudeToSeveral() {
        assertEquals("Around the colony, Max is known as rude: several colonists grumble about it.",
                Bonds.reputation("Max", List.of(-30, -20, 5), 0, 0));
        assertEquals("Around the colony, Max is known as kind and respectful. Max has kept 3 and broken 0 promises to colonists.",
                Bonds.reputation("Max", List.of(30, 20), 3, 0));
        assertEquals("", Bonds.reputation("Max", List.of(40), 0, 0));
    }
}

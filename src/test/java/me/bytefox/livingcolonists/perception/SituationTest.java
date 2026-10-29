package me.bytefox.livingcolonists.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import me.bytefox.livingcolonists.behaviour.Level;
import org.junit.jupiter.api.Test;

class SituationTest {
    private static ColonistFacts.Builder builder() {
        return ColonistFacts.builder()
                .job("builder").workplaceLevel(2)
                .availableWork(List.of("chopping wood (the Forester's Hut)"))
                .missingWork(List.of("fishing"))
                .homeLevel(1).saturation(15).food(ColonistFacts.Food.UNKNOWN)
                .health(1).happiness(6.5)
                .weather("rain").colony(12, 2, 1, false);
    }

    @Test
    void offSaysNothing() {
        assertEquals("", Situation.render(builder().build(), Level.OFF));
    }

    @Test
    void lowKnowsTheJobAndTheWorkRulesOnly() {
        String text = Situation.render(builder().homeLevel(0).build(), Level.LOW);

        assertTrue(text.contains("builder"), text);
        assertTrue(text.contains("Forester's Hut"), text);
        assertTrue(text.contains("fishing"), text);
        assertFalse(text.contains("home"), text);
    }

    @Test
    void workplaceLevelAndWorkNotesAreKnownFromLow() {
        String text = Situation.render(builder().workplaceMaxLevel(5)
                .workNotes(List.of("You are making for others: 3 Iron Ingots.")).build(), Level.LOW);
        assertTrue(text.contains("level 2 of 5"), text);
        assertTrue(text.contains("could still be upgraded"), text);
        assertTrue(text.contains("3 Iron Ingots"), text);
    }

    @Test
    void theListIsBackgroundNotATopicToRecite() {
        assertTrue(Situation.render(builder().build(), Level.LOW).contains("to answer questions with"));
    }

    @Test
    void unemployedColonistsKnowTheyHaveNoJob() {
        assertTrue(Situation.render(builder().job(null).workplaceLevel(0).build(), Level.LOW).contains("no job"));
    }

    @Test
    void aLevelOneHouseIsStillAHome() {
        String text = Situation.render(builder().build(), Level.DEFAULT);

        assertTrue(text.contains("level 1 house"), text);
        assertFalse(text.contains("no home"), text);
        assertTrue(Situation.render(builder().homeLevel(0).build(), Level.DEFAULT).contains("no home"));
    }

    @Test
    void hungerWithFoodAtHandIsNoReasonToBeg() {
        String pockets = Situation.render(builder().saturation(3).food(ColonistFacts.Food.POCKETS).build(), Level.DEFAULT);
        assertTrue(pockets.contains("hungry"), pockets);
        assertTrue(pockets.contains("don't ask players for food"), pockets);

        String nothing = Situation.render(builder().saturation(3).food(ColonistFacts.Food.NONE).build(), Level.DEFAULT);
        assertTrue(nothing.contains("no food"), nothing);
    }

    @Test
    void requestsUnderwayAreNotAProblemButStuckOnesAre() {
        String text = Situation.render(builder().requests(2, List.of("an iron pickaxe")).build(), Level.LOW);

        assertTrue(text.contains("2 deliveries"), text);
        assertTrue(text.contains("iron pickaxe"), text);
    }

    @Test
    void buildersKnowWhatTheyBuildAndWhatIsMissing() {
        String text = Situation.render(builder().construction("the Tavern, level 1", List.of("32 Oak Planks", "16 Stone"), 1)
                .build(), Level.LOW);
        assertTrue(text.contains("the Tavern"), text);
        assertTrue(text.contains("32 Oak Planks, 16 Stone"), text);
        assertTrue(text.contains("already being delivered"), text);
        assertTrue(Situation.render(builder().build(), Level.LOW).contains("no construction order"));
    }

    @Test
    void thingsNobodyCanProvideAreThePlayersJob() {
        String text = Situation.render(builder().playerMustBring(List.of("64 Cobblestone")).build(), Level.LOW);
        assertTrue(text.contains("64 Cobblestone"), text);
        assertTrue(text.contains("a player has to bring it"), text);
        assertFalse(text.contains("courier or crafter is already handling"), text);
    }

    @Test
    void sicknessAndCareAreMentioned() {
        String text = Situation.render(builder().sickness("the flu", true).build(), Level.DEFAULT);
        assertTrue(text.contains("the flu"), text);
        assertTrue(text.contains("hospital"), text);
    }

    @Test
    void unhappinessNamesItsCauses() {
        String text = Situation.render(builder().happiness(3).unhappyAbout(List.of("food", "security")).build(), Level.DEFAULT);
        assertTrue(text.contains("unhappy"), text);
        assertTrue(text.contains("food, security"), text);
    }

    @Test
    void familyAndCoworkersAreListedFromDefault() {
        ColonistFacts facts = builder()
                .family(List.of(new ColonistFacts.Relative("wife", "Ann Lee", "farmer", List.of("hungry")),
                        new ColonistFacts.Relative("sister", "Mia Lee", "baker", List.of())))
                .coworkers(List.of(new ColonistFacts.Relative("coworker", "Tom Hart", "builder", List.of())))
                .build();

        String normal = Situation.render(facts, Level.DEFAULT);
        assertTrue(normal.contains("Your wife Ann Lee, farmer (hungry)"), normal);
        assertTrue(normal.contains("Tom Hart"), normal);
        assertFalse(normal.contains("Mia Lee"), normal);

        assertTrue(Situation.render(facts, Level.HIGH).contains("Mia Lee"));
        assertFalse(Situation.render(facts, Level.LOW).contains("Ann Lee"));
    }

    @Test
    void theColonyAndARaidAreKnownFromDefault() {
        String text = Situation.render(builder().colony(12, 2, 1, true).build(), Level.DEFAULT);
        assertTrue(text.contains("12 colonists"), text);
        assertTrue(text.contains("raid"), text);
    }
}

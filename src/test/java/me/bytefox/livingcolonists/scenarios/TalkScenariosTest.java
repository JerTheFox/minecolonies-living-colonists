package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.permissions.IPermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the talk code does today, end to end: the net under the refactoring of #44. */
class TalkScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private boolean saw(String text) {
        return stage.chat.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void aPrivateMessageGetsAPrivateAnswer() throws Exception {
        stage.model.saysWhenAsked("как спалось", "Плохо, крыша течёт.");
        stage.playerWritesTo("Darcy Abbott", "как спалось?");
        assertTrue(stage.runUntil(() -> saw("Плохо, крыша течёт."), WAIT), "no answer: " + stage.chat);
        assertTrue(stage.replies().stream().allMatch(r -> r.toString().contains("Darcy Abbott")), "someone else was asked");
    }

    @Test
    void aLookUpGoesBackToTheModelAndTheAnswerComesFromIt() throws Exception {
        stage.model.callsWhenOffered("food_status", new JsonObject());
        stage.model.saysWhenAsked("food_items_in_warehouses", "На складе пусто.");
        stage.playerWritesTo("Darcy Abbott", "сколько у нас еды?");
        assertTrue(stage.runUntil(() -> saw("На складе пусто."), WAIT), "the look-up's result never reached the answer: " + stage.chat);
    }

    @Test
    void theSameLookUpLaterLooksAgain() throws Exception {
        // 0.4.0: a build_status from before an order was answered from a ten-minute cache, so a colonist denied a
        // building the builder was already digging out.
        stage.model.callsWhenOffered("food_status", new JsonObject());
        stage.model.saysWhenAsked("food_items_in_warehouses", "Еды хватает.");
        java.util.Map<String, Long> before = me.bytefox.livingcolonists.debug.Counters.snapshot();
        stage.playerWritesTo("Darcy Abbott", "сколько у нас еды?");
        assertTrue(stage.runUntil(() -> saw("Еды хватает."), WAIT), "no first answer: " + stage.chat);
        stage.chat.clear();
        stage.model.callsWhenOffered("food_status", new JsonObject());
        stage.model.saysWhenAsked("food_items_in_warehouses", "Еды хватает.");
        stage.playerWritesTo("Darcy Abbott", "а теперь сколько еды?");
        assertTrue(stage.runUntil(() -> saw("Еды хватает."), WAIT), "no second answer: " + stage.chat);
        java.util.Map<String, Long> after = me.bytefox.livingcolonists.debug.Counters.snapshot();
        assertEquals(2L, after.getOrDefault("lookups", 0L) - before.getOrDefault("lookups", 0L), "the colony wasn't looked at twice");
    }

    @Test
    void aWalkingOrderRunsAtOnceAndNamesWhoCantGo() throws Exception {
        // Walks need no yes: "stop" ends them and they run out by themselves (WalkErrands).
        JsonObject words = new JsonObject();
        words.addProperty("player_words", "все по домам");
        stage.model.callsWhenOffered("everyone_home", words);
        stage.playerWritesTo("Darcy Abbott", "все по домам");
        assertTrue(stage.runUntil(() -> stage.model.requests.toString().contains("No home to go to: Darcy Abbott, Hunter Casey"),
                WAIT), "the order's result never went back to the model");
        assertFalse(saw("livingcolonists.order.confirm"), "a walk asked for a yes");
    }

    @Test
    void theColonistNamedAnswersFirst() throws Exception {
        stage.playerWritesToColony("Хантер, как дела?");
        assertTrue(stage.runUntil(() -> !stage.replies().isEmpty(), WAIT), "nobody answered");
        assertTrue(stage.replies().get(0).toString().contains("You are Hunter Casey"), "not Hunter first");
    }

    @Test
    void aStrangerIsNotAnswered() throws Exception {
        stage.rank(IPermissions.NEUTRAL_RANK_ID);
        stage.playerWritesToColony("эй, есть кто?");
        assertTrue(stage.runUntil(() -> saw("livingcolonists.colony.not_member"), WAIT), "no refusal: " + stage.chat);
        stage.runUntil(() -> false, Duration.ofMillis(300));
        assertTrue(stage.replies().isEmpty(), "a stranger got an answer");
    }

    @Test
    void aColonyTurnedOffStaysSilent() throws Exception {
        stage.talk.setColonyOff(stage.colony, true);
        stage.playerWritesToColony("эй, есть кто?");
        assertTrue(stage.runUntil(() -> saw("livingcolonists.talk.off"), WAIT), "no note that the colony is off: " + stage.chat);
        stage.runUntil(() -> false, Duration.ofMillis(300));
        assertTrue(stage.replies().isEmpty(), "a colony turned off answered");
    }
}

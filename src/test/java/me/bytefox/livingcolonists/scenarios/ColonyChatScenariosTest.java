package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The colony chat end to end: a player's message, the model's answer and what comes of it. */
class ColonyChatScenariosTest {
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    @Test
    void aColonistAskedByNameAnswersInsteadOfChangingTheSubject() throws Exception {
        // 0.4.0: asked about a zombie by name, a colonist was told to change the subject and said "Not a tanner, for sure."
        stage.model.saysWhenAsked("Дарси, как дела", "Нормально. Хантер, а ты сегодня ел?");
        stage.playerWritesToColony("Дарси, как дела?");
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("You are Hunter Casey")),
                Duration.ofSeconds(20)), "Hunter never got the turn; the player saw: " + stage.chat);
        String hunter = stage.replies().stream().map(Object::toString).filter(r -> r.contains("You are Hunter Casey")).findFirst().orElseThrow();
        assertTrue(hunter.contains("asked you something by name"), "not told to answer the question");
        assertTrue(!hunter.contains("Change the subject"), "told to change the subject");
    }

    @Test
    void aColonistAnswersThePlayerInTheColonyChat() throws Exception {
        stage.model.saysWhenAsked("сколько у нас еды", "Еды на пару дней.");
        stage.playerWritesToColony("сколько у нас еды?");
        assertTrue(stage.runUntil(() -> stage.chat.stream().anyMatch(line -> line.contains("Еды на пару дней.")),
                Duration.ofSeconds(20)), "no answer; the player saw: " + stage.chat);
    }

    @Test
    void anOrderToEveryoneOffersTheFunctionsToTheOneAnswering() throws Exception {
        JsonObject words = new JsonObject();
        words.addProperty("player_words", "все по домам");
        stage.model.callsWhenOffered("everyone_home", words);
        stage.playerWritesToColony("все по домам");
        assertTrue(stage.runUntil(() -> stage.model.requests.stream().anyMatch(r -> FakeModel.offers(r, "everyone_home")),
                Duration.ofSeconds(20)), "the answer was asked for without the orders; requests: " + stage.model.requests.size());
    }

    @Test
    void anOrderRightAfterColonyNewsStillGetsTheOrders() throws Exception {
        // 0.3.3: the colonist answering was still on the news and had no orders, so "everyone go home" did nothing.
        stage.talk.threads().startEvent(stage.server(), stage.colony, "Someone has just set out the place for a new Guard Tower.",
                java.util.List.of(), false, 0);
        stage.playerWritesToColony("все по домам");
        assertTrue(stage.runUntil(() -> stage.model.requests.stream()
                        .anyMatch(r -> FakeModel.isReply(r) && r.toString().contains("все по домам") && FakeModel.offers(r, "everyone_home")),
                Duration.ofSeconds(20)), "the reply to the order was asked for without the orders");
    }
}

package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Profile;
import me.bytefox.livingcolonists.bonds.Promise;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a server's settings and the colony's changes do to the talk (#7, "still to see in game"): the hourly and daily
 * limits, the minimal profile, a promise still owed, a colonist who died.
 */
class ServerRulesScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
    }

    @AfterEach
    void tearDown() {
        stage.close();
        // Settings outlive a stage within one run of the tests: put back the ones changed here.
        Config.MESSAGES_PER_PLAYER_PER_HOUR.set(Config.MESSAGES_PER_PLAYER_PER_HOUR.getDefault());
        Config.TOKENS_PER_DAY.set(Config.TOKENS_PER_DAY.getDefault());
        Config.PROFILE.set(Config.PROFILE.getDefault());
    }

    /** Darcy's answers the player saw. */
    private long answers() {
        return stage.chat.stream().filter(line -> line.startsWith("<Darcy Abbott> → <Alex>")).count();
    }

    @Test
    void theHourlyLimitStopsThePlayerWithAMessage() throws Exception {
        Config.MESSAGES_PER_PLAYER_PER_HOUR.set(2);
        stage.playerWritesTo("Darcy Abbott", "hello");
        assertTrue(stage.runUntil(() -> answers() >= 1, WAIT), "no answer to the first message");
        stage.playerWritesTo("Darcy Abbott", "how are you?");
        assertTrue(stage.runUntil(() -> answers() >= 2, WAIT), "no answer to the second message: " + stage.chat);
        stage.playerWritesTo("Darcy Abbott", "and the weather?");
        stage.runTicks(200);
        assertEquals(2, stage.replies().size(), "the third message this hour still reached the model");
        assertTrue(stage.chat.stream().anyMatch(line -> line.contains("livingcolonists.limit.hourly")), "not told: " + stage.chat);
    }

    @Test
    void theDailyTokenLimitStopsTheTalkWithAMessage() throws Exception {
        Config.TOKENS_PER_DAY.set(20L);
        stage.playerWritesTo("Darcy Abbott", "hello");
        assertTrue(stage.runUntil(() -> stage.replies().size() >= 1, WAIT), "no answer before the limit");
        stage.runTicks(100);
        int before = stage.replies().size();
        stage.playerWritesTo("Darcy Abbott", "how are you?");
        stage.runTicks(200);
        assertEquals(before, stage.replies().size(), "talk went on past the day's tokens");
        assertTrue(stage.chat.stream().anyMatch(line -> line.contains("livingcolonists.limit.daily")), "not told: " + stage.chat);
    }

    @Test
    void theMinimalProfileAnswersButNeverSpeaksFirst() throws Exception {
        Config.PROFILE.set(Profile.MINIMAL);
        for (int i = 0; i < stage.citizens.size(); i++) {
            stage.body(stage.citizens.get(i), new BlockPos(3 * i, 64, 2));
        }
        stage.dice(0);
        stage.playerWritesTo("Darcy Abbott", "hello");
        assertTrue(stage.runUntil(() -> stage.replies().size() >= 1, WAIT), "a minimal colonist doesn't answer");
        int answered = stage.replies().size();
        stage.citizenEvent("citizen_born", "Ann Lowe");
        stage.runTicks(6000);
        assertEquals(answered, stage.replies().size(), "a minimal colonist spoke first");
    }

    @Test
    void whileAPromiseIsOwedColonistsTakeNoNewOne() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд", "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the first promise was not written down");
        stage.bonds.answer(stage.player, stage.bonds.promises(darcy).get(0).id(), true);
        stage.model.remembersWhen("и ещё принесу хлеба", "promise | 1 | food | 2 | Alex promised to bring Darcy Abbott bread");
        stage.playerWritesTo("Darcy Abbott", "и ещё принесу хлеба");
        assertTrue(stage.runUntil(() -> stage.model.requests.stream()
                .anyMatch(r -> !me.bytefox.livingcolonists.scenarios.FakeModel.isReply(r) && r.toString().contains("и ещё принесу хлеба")),
                WAIT), "the second talk was never written down");
        stage.runTicks(100);
        assertEquals(1, stage.bonds.promises(darcy).size(), "a new promise while one is owed");
        assertTrue(stage.bonds.promises(darcy).get(0).text().contains("an emerald"));
    }

    @Test
    void aColonistWhoDiedIsForgottenAndANewcomerWithTheSameIdStartsFresh() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд", "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald\n"
                + "4 | Alex | Alex loves fishing.");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty() && !stage.memory.facts(darcy).isEmpty(), WAIT),
                "nothing was remembered");
        stage.citizens.remove(darcy);
        // What the mod does every ten seconds for colonists no longer in their colony.
        stage.memory.forget(Citizens::isGone);
        stage.bonds.forget(Citizens::isGone);
        stage.talk.forgetInitiative(Citizens::isGone);
        ICitizenData newcomer = stage.newcomer(darcy.getId(), "Rowan Hale");
        assertTrue(stage.bonds.promises(newcomer).isEmpty(), "the dead colonist's promises carried over");
        assertTrue(stage.memory.facts(newcomer).isEmpty(), "the dead colonist's memories carried over");
        assertTrue(stage.bonds.promises(darcy).stream().noneMatch(Promise::isOpen));
    }
}

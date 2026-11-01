package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.bonds.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a talk leaves behind once it goes quiet: facts, promises and what keeps them. */
class MemoryScenariosTest {
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
    }

    @Test
    void aPromiseInAPrivateTalkIsWrittenDownWithTheDeadlineFromThePlayersWords() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд через минуту",
                "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд через минуту");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        Promise promise = stage.bonds.promises(darcy).get(0);
        assertTrue(promise.isOpen());
        // The words were said at game time 6000; the talk is written down a minute of quiet later (#11).
        assertTrue(Math.abs(promise.due() - (6000 + 1200)) <= 50,
                "the deadline isn't a minute after the words: made " + promise.made() + ", due " + promise.due());
    }

    @Test
    void aGiftOfWhatWasPromisedKeepsThePromise() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд", "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "gave Darcy Abbott an emerald", 3, List.of("Emerald"));
        assertEquals(Promise.Status.KEPT, stage.bonds.promises(darcy).get(0).status());
    }

    @Test
    void factsFromAPrivateTalkAreKept() throws Exception {
        stage.model.remembersWhen("у меня жена болеет", "3 | Alex | Alex's wife is ill.");
        stage.playerWritesTo("Darcy Abbott", "у меня жена болеет");
        assertTrue(stage.runUntil(() -> stage.memory.facts(darcy).stream().anyMatch(f -> f.text().contains("wife is ill")), WAIT),
                "the fact was not kept: " + stage.memory.facts(darcy));
    }

    @Test
    void aColonistWithEnoughMemoriesGetsALifeStoryThatRepliesSee() throws Exception {
        for (int i = 0; i < 6; i++) {
            stage.memory.rememberSeen(stage.colony, java.util.List.of(darcy.getId()), "",
                    "Darcy Abbott did thing number " + i + ".", 3);
        }
        stage.model.answersWhen("did thing number", "Darcy came here young and has worked hard ever since.");
        assertTrue(stage.runUntil(() -> stage.memory.memories(darcy, java.util.Set.of(), "").contains("worked hard ever since"),
                Duration.ofSeconds(30)), "no story in the memories: " + stage.memory.memories(darcy, java.util.Set.of(), ""));
    }

    @Test
    void withEmbeddingsFactsAreFoundByMeaningAndTheirVectorsAsked() throws Exception {
        me.bytefox.livingcolonists.Config.EMBEDDINGS_API_KEY.set("test-key");
        for (int i = 0; i < 12; i++) {
            stage.memory.rememberSeen(stage.colony, java.util.List.of(darcy.getId()), "",
                    "Darcy Abbott keeps note " + i + ".", 3);
        }
        java.util.concurrent.atomic.AtomicReference<String> block = new java.util.concurrent.atomic.AtomicReference<>();
        stage.memory.memoriesFor(darcy, java.util.Set.of(), "как твоя нога?", stage.server(), block::set);
        assertTrue(stage.runUntil(() -> block.get() != null, WAIT), "no memories came back");
        assertTrue(block.get().contains("keeps note"), "no facts in the block: " + block.get());
        assertTrue(stage.runUntil(() -> stage.model.embedded.stream().anyMatch(t -> t.contains("keeps note")), WAIT),
                "the facts' vectors were never asked for");
        assertTrue(stage.model.embedded.stream().anyMatch(t -> t.contains("нога")), "what was said was not embedded");
    }

    @Test
    void newsFromTheColonyChatIsRememberedByThoseWhoTookPart() throws Exception {
        stage.model.remembersWhen("праздник в пятницу", "4 | colony | Alex announced a feast on Friday.");
        stage.playerWritesToColony("у нас будет праздник в пятницу");
        assertTrue(stage.runUntil(() -> stage.citizens.stream().anyMatch(c -> stage.memory.facts(c).stream()
                .anyMatch(f -> f.text().contains("feast on Friday"))), Duration.ofSeconds(30)), "nobody remembered the news");
    }
}

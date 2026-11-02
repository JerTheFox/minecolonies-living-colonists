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
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "gave Darcy Abbott an emerald", 3, List.of("Emerald"),
                me.bytefox.livingcolonists.friendship.Taste.LIKE, java.util.Map.of(), 1);
        assertEquals(Promise.Status.KEPT, stage.bonds.promises(darcy).get(0).status());
    }

    @Test
    void aPromiseIsShownToThePlayerInTheirLanguage() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд",
                "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald | принести Дарси изумруд");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        Promise promise = stage.bonds.promises(darcy).get(0);
        assertEquals("принести Дарси изумруд", promise.display().getString());
        assertTrue(promise.text().contains("an emerald"), "the kept text is no longer English: " + promise.text());
    }

    @Test
    void aSettledPromiseIsNotWrittenDownAgain() throws Exception {
        stage.model.remembersWhen("принесу тебе изумруд", "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "gave Darcy Abbott an emerald", 3, List.of("Emerald"),
                me.bytefox.livingcolonists.friendship.Taste.LIKE, java.util.Map.of(), 1);
        stage.playerWritesTo("Darcy Abbott", "вот твой изумруд, как обещал");
        assertTrue(stage.runUntil(() -> stage.model.requests.toString().contains("already settled")
                && stage.model.requests.toString().contains("bring Darcy Abbott an emerald (kept)"), WAIT),
                "the kept promise wasn't listed for the next digest");
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

    private Promise promiseFromTalk(String words) throws Exception {
        stage.model.remembersWhen(words, "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", words);
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        return stage.bonds.promises(darcy).get(0);
    }

    @Test
    void thePlayerIsAskedWhetherTheyMeantItAndAYesMakesItBinding() throws Exception {
        Promise promise = promiseFromTalk("принесу тебе изумруд");
        assertEquals(Promise.Confirm.ASKED, promise.confirm());
        assertTrue(stage.chat.stream().anyMatch(line -> line.contains("livingcolonists.promise.ask")), "not asked: " + stage.chat);
        assertEquals(1, stage.bonds.answer(stage.player, promise.id(), true));
        assertEquals(Promise.Confirm.CONFIRMED, stage.bonds.promises(darcy).get(0).confirm());
        assertTrue(stage.bonds.owesInColony(stage.colony, stage.player.getUUID()).isPresent(), "a confirmed promise binds");
    }

    @Test
    void aNoDropsItWithoutAGrudge() throws Exception {
        Promise promise = promiseFromTalk("может, принесу тебе изумруд");
        stage.bonds.answer(stage.player, promise.id(), false);
        assertEquals(Promise.Status.CANCELLED, stage.bonds.promises(darcy).get(0).status());
        assertEquals(0, stage.bonds.playerRapport(darcy, stage.player.getUUID()));
    }

    @Test
    void unansweredItTurnsVagueAndLapsesQuietly() throws Exception {
        Promise promise = promiseFromTalk("принесу тебе изумруд через минуту");
        assertTrue(stage.runUntil(() -> stage.bonds.promises(darcy).get(0).confirm() == Promise.Confirm.VAGUE, WAIT),
                "it never turned vague");
        assertTrue(stage.bonds.owesInColony(stage.colony, stage.player.getUUID()).isEmpty(), "a vague promise doesn't bind");
        stage.skipTime(4 * 24000);
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).get(0).isOpen(), WAIT), "it never lapsed");
        assertTrue(stage.chat.stream().noneMatch(line -> line.contains("livingcolonists.promise.broken")), "told it was broken");
        assertEquals(Promise.Status.CANCELLED, stage.bonds.promises(darcy).get(0).status());
        assertEquals(0, stage.bonds.playerRapport(darcy, stage.player.getUUID()), "a grudge over a vague promise");
        assertTrue(promise.id() >= 0);
    }

    @Test
    void anOverduePromiseTheyMeantGetsAReminderAVagueOneDoesNot() throws Exception {
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.dice(0);
        Promise promise = promiseFromTalk("принесу тебе изумруд через минуту");
        stage.bonds.answer(stage.player, promise.id(), true);
        int before = stage.replies().size();
        stage.skipTime(2400);
        assertTrue(stage.runUntil(() -> stage.replies().stream().skip(before)
                        .anyMatch(r -> r.toString().contains("promised to bring Darcy Abbott an emerald")), WAIT),
                "no reminder of the overdue promise");
    }

    @Test
    void puttingWhatWasPromisedIntoTheColonistsBagKeepsThePromiseAndGetsThanks() throws Exception {
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.dice(0);
        Promise promise = promiseFromTalk("принесу тебе изумруд");
        stage.bonds.answer(stage.player, promise.id(), true);
        // The colonist's bag, as MineColonies' inventory window shows it.
        java.util.List<net.minecraft.world.item.ItemStack> bag = new java.util.ArrayList<>(java.util.Collections.nCopies(9,
                net.minecraft.world.item.ItemStack.EMPTY));
        var inventory = org.mockito.Mockito.mock(com.minecolonies.api.inventory.InventoryCitizen.class);
        org.mockito.Mockito.when(inventory.getSlots()).thenReturn(bag.size());
        org.mockito.Mockito.when(inventory.getStackInSlot(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> bag.get((int) invocation.getArgument(0)));
        org.mockito.Mockito.when(darcy.getInventory()).thenReturn(inventory);
        var window = org.mockito.Mockito.mock(com.minecolonies.api.inventory.container.ContainerCitizenInventory.class);
        org.mockito.Mockito.when(window.getCitizenData()).thenReturn(darcy);

        int before = stage.replies().size();
        stage.talk.gifts().opened(stage.player, window);
        bag.set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD));
        stage.talk.gifts().closed(stage.player, window);

        assertEquals(Promise.Status.KEPT, stage.bonds.promises(darcy).get(0).status(), "the gift didn't keep the promise");
        assertTrue(stage.bonds.playerRapport(darcy, stage.player.getUUID()) > 0, "no warmth for the gift");
        assertTrue(stage.runUntil(() -> stage.replies().stream().skip(before)
                .anyMatch(r -> r.toString().contains("to thank them")), WAIT), "no thanks");
    }
}

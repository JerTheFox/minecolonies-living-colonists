package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.friendship.Taste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A colony without a language model (#68): colonists answer and react with set lines, and nothing calls the API. */
class NoModelScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.body(stage.citizens.get(1), new net.minecraft.core.BlockPos(-3, 64, 0));
        Config.USE_MODEL.set(false);
    }

    @AfterEach
    void tearDown() {
        Config.USE_MODEL.set(true);
        stage.close();
    }

    private boolean said(String speaker) {
        return stage.chat.stream().anyMatch(line -> line.startsWith("<" + speaker + ">"));
    }

    @Test
    void aPrivateMessageGetsASetReplyAndThePlayerHearsWhyOnce() throws Exception {
        stage.playerWritesTo("Darcy Abbott", "как дела?");
        assertTrue(stage.runUntil(() -> stage.chat.stream().anyMatch(l -> l.startsWith("<Darcy Abbott> →")), WAIT),
                "no answer: " + stage.chat);
        stage.playerWritesTo("Darcy Abbott", "а сейчас?");
        stage.runTicks(200);
        assertEquals(1, stage.chat.stream().filter(l -> l.contains("livingcolonists.talk.no_model")).count(),
                "the player wasn't told once why: " + stage.chat);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }

    @Test
    void theColonyAnswersAMessageWithASetLine() throws Exception {
        stage.playerWritesToColony("всем привет");
        assertTrue(stage.runUntil(() -> said("Darcy Abbott") || said("Hunter Casey"), WAIT), "nobody answered: " + stage.chat);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }

    @Test
    void aGiftIsThankedForInASetLine() throws Exception {
        stage.dice(0);
        java.util.List<net.minecraft.world.item.ItemStack> bag = new java.util.ArrayList<>(java.util.Collections.nCopies(9,
                net.minecraft.world.item.ItemStack.EMPTY));
        var inventory = org.mockito.Mockito.mock(com.minecolonies.api.inventory.InventoryCitizen.class);
        org.mockito.Mockito.when(inventory.getSlots()).thenReturn(bag.size());
        org.mockito.Mockito.when(inventory.getStackInSlot(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> bag.get((int) invocation.getArgument(0)));
        org.mockito.Mockito.when(darcy.getInventory()).thenReturn(inventory);
        var window = org.mockito.Mockito.mock(com.minecolonies.api.inventory.container.ContainerCitizenInventory.class);
        org.mockito.Mockito.when(window.getCitizenData()).thenReturn(darcy);
        stage.talk.gifts().opened(stage.player, window);
        bag.set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CAKE));
        stage.talk.gifts().closed(stage.player, window);
        assertTrue(stage.runUntil(() -> stage.chat.stream().anyMatch(l -> l.startsWith("<Darcy Abbott> →")), WAIT),
                "no thanks: " + stage.chat);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }

    @Test
    void aHolidayIsBroughtUpWithASetLine() throws Exception {
        stage.talk.calendar().setDay(stage.colony, 12);
        assertTrue(stage.runUntil(() -> said("Darcy Abbott") || said("Hunter Casey"), WAIT), "nobody spoke of it: " + stage.chat);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }

    @Test
    void friendshipWorksWithoutAModel() {
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me cake", 3, List.of("Cake"), Taste.LOVE, Map.of(), 2);
        assertTrue(stage.bonds.playerRapport(darcy, stage.player.getUUID()) >= 20);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }
}

package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.bonds.Promise;
import me.bytefox.livingcolonists.items.ModItems;
import me.bytefox.livingcolonists.network.PromiseBookPages;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The promise notebook: who gets one, and what its pages say. */
class PromiseBookScenariosTest {
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

    private Promise promiseFromTalk(String words) throws Exception {
        stage.model.remembersWhen(words, "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald");
        stage.playerWritesTo("Darcy Abbott", words);
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty(), WAIT), "the promise was not written down");
        return stage.bonds.promises(darcy).get(0);
    }

    private static List<String> commands(List<Component> pages) {
        List<String> commands = new ArrayList<>();
        for (Component page : pages) {
            page.visit((Style style, String text) -> {
                if (style.getClickEvent() != null && style.getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND) {
                    commands.add(style.getClickEvent().getValue());
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
        return commands;
    }

    private static String text(List<Component> pages) {
        return String.join("\n", pages.stream().map(Component::getString).toList());
    }

    @Test
    void theFirstPromiseThePlayerStandsByBringsTheNotebookOnce() throws Exception {
        Promise promise = promiseFromTalk("принесу тебе изумруд");
        stage.bonds.answer(stage.player, promise.id(), true);
        verify(stage.player, times(1)).addItem(argThat(stack -> stack.is(ModItems.PROMISE_BOOK.get())));
        assertTrue(stage.chat.stream().anyMatch(line -> line.contains("livingcolonists.book.given")), "not told: " + stage.chat);
        stage.bonds.answer(stage.player, promise.id(), true);
        verify(stage.player, times(1)).addItem(argThat(stack -> stack.is(ModItems.PROMISE_BOOK.get())));
    }

    @Test
    void aPromiseNobodyStoodByBringsNoNotebook() throws Exception {
        Promise promise = promiseFromTalk("может, принесу тебе изумруд");
        stage.bonds.answer(stage.player, promise.id(), false);
        verify(stage.player, times(0)).addItem(argThat(stack -> stack.is(ModItems.PROMISE_BOOK.get())));
    }

    @Test
    void thePagesListOpenPromisesWithTheirButtons() throws Exception {
        Promise promise = promiseFromTalk("принесу тебе изумруд");
        List<Component> pages = stage.bonds.notebook(stage.player);
        assertTrue(text(pages).contains("Darcy Abbott"), text(pages));
        assertTrue(text(pages).contains("promised to bring Darcy Abbott an emerald"), text(pages));
        assertTrue(commands(pages).contains("/lc promise yes " + promise.id()), "a vague one can be confirmed: " + commands(pages));
        assertTrue(commands(pages).contains("/lc promise no " + promise.id()), "no way to give it up: " + commands(pages));
        stage.bonds.answer(stage.player, promise.id(), true);
        pages = stage.bonds.notebook(stage.player);
        assertTrue(!commands(pages).contains("/lc promise yes " + promise.id()), "a confirmed one is asked again");
        stage.bonds.answer(stage.player, promise.id(), false);
        assertTrue(!text(stage.bonds.notebook(stage.player)).contains("an emerald"), "a promise given up stays in the book");
    }

    @Test
    void anOpenNotebookIsRefreshedWhenAButtonIsUsed() throws Exception {
        Promise promise = promiseFromTalk("принесу тебе изумруд");
        stage.bonds.answer(stage.player, promise.id(), true);
        org.mockito.ArgumentCaptor<net.minecraft.network.protocol.Packet<?>> packets =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.protocol.Packet.class);
        org.mockito.Mockito.verify(stage.player.connection, org.mockito.Mockito.atLeastOnce())
                .send((net.minecraft.network.protocol.Packet<?>) packets.capture());
        List<PromiseBookPages> sent = packets.getAllValues().stream()
                .filter(packet -> packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof PromiseBookPages)
                .map(packet -> (PromiseBookPages) ((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) packet).payload())
                .toList();
        assertTrue(!sent.isEmpty(), "no pages sent");
        PromiseBookPages last = sent.get(sent.size() - 1);
        assertTrue(!last.open(), "a button should refresh the notebook, not open it");
        assertTrue(!commands(last.pages()).contains("/lc promise yes " + promise.id()), "the refreshed pages are stale");
    }

    @Test
    void aPromiseFromAMineColoniesDialogBringsTheNotebookToo() {
        stage.bonds.onDialogResponse(darcy, "com.minecolonies.coremod.ai.nohospital", "com.minecolonies.coremod.gui.chat.okay",
                stage.player, "Alex");
        assertTrue(!stage.bonds.promises(darcy).isEmpty(), "no promise from the dialog");
        verify(stage.player, times(1)).addItem(argThat(stack -> stack.is(ModItems.PROMISE_BOOK.get())));
        assertTrue(text(stage.bonds.notebook(stage.player)).contains("cured"), "the dialog's promise isn't in the book");
    }

    @Test
    void theNotebookListsTheSoonestFirstAndLeavesOutOtherPlayersPromises() {
        stage.bonds.onDialogResponse(darcy, "com.minecolonies.coremod.ai.nohospital", "com.minecolonies.coremod.gui.chat.okay",
                stage.player, "Alex");
        stage.bonds.onDialogResponse(stage.citizens.get(1), "com.minecolonies.coremod.entity.citizen.no.foodquality",
                "com.minecolonies.coremod.gui.chat.okay", stage.player, "Alex");
        List<Component> pages = stage.bonds.notebook(stage.player);
        assertEquals(3, pages.size(), text(pages));
        assertTrue(pages.get(1).getString().contains("cured"), "the one with a deadline comes first: " + text(pages));
        assertTrue(pages.get(2).getString().contains("better food"), text(pages));
        net.minecraft.server.level.ServerPlayer other = org.mockito.Mockito.mock(net.minecraft.server.level.ServerPlayer.class);
        org.mockito.Mockito.when(other.getUUID()).thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff"));
        assertEquals(1, stage.bonds.notebook(other).size(), "someone else's promises in the book");
    }

    @Test
    void withNoPromisesTheNotebookSaysSo() {
        List<Component> pages = stage.bonds.notebook(stage.player);
        assertEquals(1, pages.size());
        assertTrue(text(pages).contains("livingcolonists.book.empty"), text(pages));
    }
}

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

    /** What the server sent the player of a kind, in order. */
    private <T> List<T> sent(Class<T> type) {
        org.mockito.ArgumentCaptor<net.minecraft.network.protocol.Packet<?>> packets =
                org.mockito.ArgumentCaptor.forClass(net.minecraft.network.protocol.Packet.class);
        org.mockito.Mockito.verify(stage.player.connection, org.mockito.Mockito.atLeast(0)).send(packets.capture());
        return packets.getAllValues().stream()
                .filter(p -> p instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && type.isInstance(custom.payload()))
                .map(p -> type.cast(((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) p).payload())).toList();
    }

    @Test
    void turningToAColonistOpensTheTalkMenuNotTheChat() {
        var conversations = new me.bytefox.livingcolonists.talk.Conversations(stage.talk);
        conversations.lock(stage.player, darcy, true);
        var menus = sent(me.bytefox.livingcolonists.network.TalkMenuState.class);
        assertEquals(1, menus.size(), "no talk menu");
        assertTrue(menus.get(0).open());
        assertTrue(conversations.partner(stage.player).isEmpty(), "a free talk was started without a model");
    }

    @Test
    void writingToTheColonyIsntOfferedWithoutAModel() {
        var conversations = new me.bytefox.livingcolonists.talk.Conversations(stage.talk);
        conversations.chatToColony(stage.player);
        assertTrue(!conversations.wantsChat(stage.player), "the chat went to the colony");
    }

    @Test
    void aTalkADayAndACompliment() {
        stage.talk.menu().open(stage.player, darcy);
        int afterTalk = stage.bonds.playerRapport(darcy, stage.player.getUUID());
        assertTrue(afterTalk > 0, "the first talk of the day brought nothing");
        stage.talk.menu().choose(stage.player, darcy, "compliment");
        int afterCompliment = stage.bonds.playerRapport(darcy, stage.player.getUUID());
        assertTrue(afterCompliment > afterTalk, "the compliment brought nothing");
        stage.talk.menu().choose(stage.player, darcy, "compliment");
        stage.talk.menu().choose(stage.player, darcy, "how");
        assertEquals(afterCompliment, stage.bonds.playerRapport(darcy, stage.player.getUUID()), "friendship farmed in a day");
        stage.passDays(1);
        stage.talk.menu().choose(stage.player, darcy, "how");
        assertTrue(stage.bonds.playerRapport(darcy, stage.player.getUUID()) > afterCompliment, "a new day's talk brought nothing");
    }

    @Test
    void aColonistTellsOneThingTheyLikeADay() {
        stage.talk.menu().choose(stage.player, darcy, "likes");
        var known = stage.bonds.knownTastes(darcy, stage.player.getUUID());
        assertEquals(1, known.values().stream().mapToInt(List::size).sum(), "no taste told: " + known);
        stage.talk.menu().choose(stage.player, darcy, "likes");
        assertEquals(1, stage.bonds.knownTastes(darcy, stage.player.getUUID()).values().stream().mapToInt(List::size).sum(),
                "two tastes in a day");
        var last = sent(me.bytefox.livingcolonists.network.TalkMenuState.class);
        assertTrue(last.get(last.size() - 1).line().toString().contains("menu.likes.again"), last.toString());
    }

    @Test
    void theNotebookHasNoOrdersWithoutAModel() {
        var notebook = new me.bytefox.livingcolonists.notebook.Notebook(stage.bonds, stage.talk.errands(), stage.talk.moods(),
                stage.talk.calendar(), stage.talk.letters());
        assertTrue(notebook.sections(stage.player).stream().noneMatch(s -> s.key().equals("orders")));
    }

    @Test
    void friendshipWorksWithoutAModel() {
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me cake", 3, List.of("Cake"), Taste.LOVE, Map.of(), 2);
        assertTrue(stage.bonds.playerRapport(darcy, stage.player.getUUID()) >= 20);
        assertTrue(stage.model.requests.isEmpty(), "the model was asked: " + stage.model.requests);
    }
}

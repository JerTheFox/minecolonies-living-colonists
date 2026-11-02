package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.network.NotebookPages;
import me.bytefox.livingcolonists.notebook.Notebook;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** The Colony Notebook (#66): the mod's own window, in sections the server writes for each player. */
class NotebookScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;
    private Notebook notebook;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        notebook = new Notebook(stage.bonds, stage.talk.errands(), stage.talk.moods(), stage.talk.calendar(), stage.talk.letters());
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    /** Translation keys, their arguments and literal text in a section, flattened. */
    private static String text(Notebook.Section section) {
        StringBuilder out = new StringBuilder();
        section.pages().forEach(page -> collect(page, out));
        return out.toString();
    }

    private static void collect(Component component, StringBuilder out) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            out.append(translatable.getKey()).append(' ');
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component inner) {
                    collect(inner, out);
                } else {
                    out.append(arg).append(' ');
                }
            }
        } else {
            out.append(component.getString()).append(' ');
        }
        component.getSiblings().forEach(sibling -> collect(sibling, out));
    }

    private static List<String> commands(Notebook.Section section) {
        List<String> commands = new ArrayList<>();
        section.pages().forEach(page -> page.visit((Style style, String text) -> {
            if (style.getClickEvent() != null && style.getClickEvent().getAction() == ClickEvent.Action.RUN_COMMAND) {
                commands.add(style.getClickEvent().getValue());
            }
            return Optional.empty();
        }, Style.EMPTY));
        return commands;
    }

    private Notebook.Section section(String key) {
        return notebook.sections(stage.player).stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void sevenSectionsInOrder() {
        assertEquals(List.of("promises", "letters", "relations", "orders", "colony", "settings", "help"),
                notebook.sections(stage.player).stream().map(Notebook.Section::key).toList());
        notebook.sections(stage.player).forEach(s -> assertTrue(!s.pages().isEmpty(), s.key() + " has no pages"));
    }

    @Test
    void relationsShowHowAColonistFeelsAboutThePlayer() throws Exception {
        stage.model.remembersWhen("спасибо тебе, ты лучший пекарь", "kind | Alex thanked Darcy Abbott warmly.");
        stage.playerWritesTo("Darcy Abbott", "спасибо тебе, ты лучший пекарь");
        assertTrue(stage.runUntil(() -> stage.bonds.playerRapport(darcy, stage.player.getUUID()) > 0, WAIT), "not taken in");
        String relations = text(section("relations"));
        assertTrue(relations.contains("Darcy Abbott"), relations);
        assertTrue(relations.contains("livingcolonists.book.mood.glad"), "the happiness from it isn't shown: " + relations);
        assertTrue(!relations.contains("Hunter Casey"), "a colonist with no relation to the player listed: " + relations);
    }

    @Test
    void theColonyPageShowsTheCalendar() {
        String before = text(section("colony"));
        assertTrue(before.contains("livingcolonists.book.calendar.not_yet"), before);
        stage.talk.calendar().setDay(stage.colony, 9);
        String colony = text(section("colony"));
        assertTrue(colony.contains("livingcolonists.book.calendar.today"), colony);
        assertTrue(colony.contains("livingcolonists.calendar.holiday.flowers"), "the coming festival isn't shown: " + colony);
    }

    @Test
    void aNewPromiseOrFriendshipIsNewsForItsTab() {
        String promises = section("promises").news();
        String relations = section("relations").news();
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me a cake", 3, List.of("Cake"),
                me.bytefox.livingcolonists.friendship.Taste.LOVE, java.util.Map.of(), 2);
        assertTrue(!relations.equals(section("relations").news()), "a new friend isn't news");
        assertEquals(promises, section("promises").news(), "a gift changed the promises' news");
        assertEquals("", section("help").news());
    }

    @Test
    void relationsShowWhatAColonistThoughtOfTheGifts() {
        stage.bonds.gift(darcy, stage.player.getUUID(), "Alex", "Alex gave me a cake", 2, List.of("Cake"),
                me.bytefox.livingcolonists.friendship.Taste.LOVE, java.util.Map.of("minecraft:cake",
                        me.bytefox.livingcolonists.friendship.Taste.LOVE), 1);
        String relations = text(section("relations"));
        assertTrue(relations.contains("livingcolonists.book.tastes.love"), "the loved gift isn't shown: " + relations);
        // The icon's text sits in the translation's argument, which tests without the language files don't unfold.
        assertTrue(section("relations").pages().toString().contains("insertion=" + Notebook.ITEM + "minecraft:cake"),
                "the cake has no icon: " + section("relations").pages());
    }

    @Test
    void ordersShowWhatWaitsForAYesAndTheLastOneWithUndo() throws Exception {
        stage.building("baker", 1, new BlockPos(20, 64, 20));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("building", "bakery");
        arguments.addProperty("player_words", "улучши пекарню");
        stage.model.callsWhenOffered("upgrade_building", arguments);
        stage.playerWritesTo("Darcy Abbott", "улучши пекарню");
        assertTrue(stage.runUntil(() -> stage.chat.stream().anyMatch(l -> l.contains("livingcolonists.order.confirm")), WAIT),
                "no question: " + stage.chat);
        assertTrue(text(section("orders")).contains("livingcolonists.book.order.waiting"), text(section("orders")));
        stage.playerWritesTo("Darcy Abbott", "да");
        assertTrue(stage.runUntil(() -> !stage.workOrders.isEmpty(), WAIT), "the yes placed no order");
        assertTrue(text(section("orders")).contains("livingcolonists.book.order.last"), text(section("orders")));
        assertTrue(commands(section("orders")).contains("/lc undo"), "no undo button: " + commands(section("orders")));
    }

    @Test
    void theColonyShowsWhoIsMourning() {
        ICitizenData hunter = stage.citizens.get(1);
        Mockito.when(hunter.getCitizenHappinessHandler().getHappiness(Mockito.any(), Mockito.any())).thenReturn(8.0);
        Mockito.when(darcy.getCitizenHappinessHandler().getHappiness(Mockito.any(), Mockito.any())).thenReturn(8.0);
        Mockito.when(hunter.getCitizenMournHandler().isMourning()).thenReturn(true);
        Mockito.when(hunter.getCitizenMournHandler().getDeceasedCitizens()).thenReturn(java.util.Set.of("Ann Lowe"));
        String colony = text(section("colony"));
        assertTrue(colony.contains("livingcolonists.book.colony.mourning") && colony.contains("Hunter Casey")
                && colony.contains("Ann Lowe"), colony);
    }

    @Test
    void helpCoversEveryTopic() {
        String help = text(section("help"));
        for (String topic : List.of("talk", "orders", "gifts", "friendship", "calendar")) {
            assertTrue(help.contains("livingcolonists.book.help." + topic), topic + " is missing: " + help);
        }
        assertTrue(commands(section("help")).contains("/lc help"));
    }

    @Test
    void settingsHaveThePlayersOwnChoicesAndTheOwnersSwitch() {
        List<String> commands = commands(section("settings"));
        assertTrue(commands.contains(Notebook.CLIENT + "lines off") && commands.contains(Notebook.CLIENT + "settings"), commands.toString());
        assertTrue(commands.contains("/lc colony off") || commands.contains("/lc colony on"), "the owner's switch is missing: " + commands);
    }

    @Test
    void aRequestFromTheClientRefreshesTheOpenNotebook() {
        notebook.send(stage.player, false);
        ArgumentCaptor<net.minecraft.network.protocol.Packet<?>> packets =
                ArgumentCaptor.forClass(net.minecraft.network.protocol.Packet.class);
        Mockito.verify(stage.player.connection, Mockito.atLeastOnce()).send((net.minecraft.network.protocol.Packet<?>) packets.capture());
        NotebookPages sent = packets.getAllValues().stream()
                .filter(p -> p instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof NotebookPages)
                .map(p -> (NotebookPages) ((net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket) p).payload())
                .reduce((a, b) -> b).orElseThrow();
        assertTrue(!sent.open(), "a refresh opened the book");
        assertEquals(7, sent.sections().size());
    }
}

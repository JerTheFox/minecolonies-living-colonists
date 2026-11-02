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
        notebook = new Notebook(stage.bonds, stage.talk.errands(), stage.talk.moods());
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
    void fiveSectionsInOrder() {
        assertEquals(List.of("promises", "relations", "orders", "colony", "help"),
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
    void helpHasTheKeysAndButtons() {
        List<String> commands = commands(section("help"));
        assertTrue(commands.contains("/lc help"), commands.toString());
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
        assertEquals(5, sent.sections().size());
    }
}

package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.IPermissions;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Orders through the colony's systems: they wait for the player's yes, run, and can be taken back. */
class OrdersScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private IBuilding bakery;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        bakery = stage.building("baker", 1, new BlockPos(20, 64, 20));
        JsonObject arguments = new JsonObject();
        arguments.addProperty("building", "bakery");
        arguments.addProperty("player_words", "улучши пекарню");
        stage.model.callsWhenOffered("upgrade_building", arguments);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private boolean saw(String text) {
        return stage.chat.stream().anyMatch(line -> line.contains(text));
    }

    @Test
    void anUpgradeWaitsForTheYesThenPlacesTheOrderAndUndoTakesItBack() throws Exception {
        stage.playerWritesTo("Darcy Abbott", "улучши пекарню");
        assertTrue(stage.runUntil(() -> saw("livingcolonists.order.confirm"), WAIT), "no question: " + stage.chat);
        verify(bakery, never()).requestUpgrade(any(), any());
        stage.playerWritesTo("Darcy Abbott", "да");
        assertTrue(stage.runUntil(() -> !stage.workOrders.isEmpty(), WAIT), "the yes placed no order: " + stage.chat);
        stage.talk.errands().undo(stage.player);
        assertEquals(0, stage.workOrders.size(), "/lc undo left the order");
    }

    @Test
    void aNoLeavesTheColonyAsItWas() throws Exception {
        stage.playerWritesTo("Darcy Abbott", "улучши пекарню");
        assertTrue(stage.runUntil(() -> saw("livingcolonists.order.confirm"), WAIT), "no question");
        stage.playerWritesTo("Darcy Abbott", "нет");
        stage.runUntil(() -> false, Duration.ofSeconds(2));
        assertTrue(stage.workOrders.isEmpty(), "an order despite the no");
    }

    @Test
    void aFriendOfTheColonyIsTurnedDownGently() throws Exception {
        stage.rank(IPermissions.FRIEND_RANK_ID);
        stage.playerWritesTo("Darcy Abbott", "улучши пекарню");
        stage.runUntil(() -> stage.model.requests.size() >= 2, WAIT);
        stage.runUntil(() -> false, Duration.ofSeconds(2));
        assertTrue(stage.workOrders.isEmpty(), "a friend gave an order");
        assertTrue(!saw("livingcolonists.order.confirm"), "a friend was asked to confirm an order");
    }
}

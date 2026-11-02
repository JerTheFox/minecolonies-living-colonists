package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.inventory.container.ContainerCitizenInventory;
import me.bytefox.livingcolonists.friendship.Taste;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** Gifts by taste (#67): what a colonist loves warms them most, what they hate sets them back, two a week count. */
class GiftScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;
    private ICitizenData hunter;
    private final List<ItemStack> darcyBag = bag();
    private final List<ItemStack> hunterBag = bag();

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        hunter = stage.citizens.get(1);
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.body(hunter, new net.minecraft.core.BlockPos(-3, 64, 0));
        stage.dice(0);
        wear(darcy, darcyBag);
        wear(hunter, hunterBag);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    @Test
    void aLovedGiftWarmsMoreThanAnOrdinaryOneAndTheThanksSaySo() throws Exception {
        int before = stage.replies().size();
        give(darcy, darcyBag, Items.CAKE);
        give(hunter, hunterBag, Items.REDSTONE_TORCH);
        assertTrue(rapport(darcy) > rapport(hunter), "cake " + rapport(darcy) + " vs torch " + rapport(hunter));
        assertTrue(stage.runUntil(() -> stage.replies().stream().skip(before)
                .anyMatch(r -> r.toString().contains("one of your favourite things")), WAIT), "no delighted thanks");
    }

    @Test
    void aHatedGiftSetsThemBack() throws Exception {
        int before = stage.replies().size();
        give(darcy, darcyBag, Items.ROTTEN_FLESH);
        assertTrue(rapport(darcy) < 0, "rotten flesh warmed them: " + rapport(darcy));
        assertTrue(stage.runUntil(() -> stage.replies().stream().skip(before)
                .anyMatch(r -> r.toString().contains("can't stand")), WAIT), "no displeasure");
    }

    @Test
    void twoGiftsAWeekCount() {
        give(darcy, darcyBag, Items.BREAD);
        stage.passDays(1);
        give(darcy, darcyBag, Items.BREAD);
        int two = rapport(darcy);
        stage.passDays(1);
        give(darcy, darcyBag, Items.CAKE);
        assertEquals(two, rapport(darcy), "a third gift in a week counted");
        stage.passDays(6);
        give(darcy, darcyBag, Items.BREAD);
        assertTrue(rapport(darcy) > two, "a gift a week later didn't count");
    }

    @Test
    void whatTheyThoughtOfAGiftIsRemembered() {
        give(darcy, darcyBag, Items.CAKE);
        stage.passDays(1);
        give(darcy, darcyBag, Items.DIRT);
        Map<Taste, List<String>> known = stage.bonds.knownTastes(darcy, stage.player.getUUID());
        assertEquals(List.of("minecraft:cake"), known.get(Taste.LOVE));
        assertEquals(List.of("minecraft:dirt"), known.get(Taste.DISLIKE));
    }

    private int rapport(ICitizenData citizen) {
        return stage.bonds.playerRapport(citizen, stage.player.getUUID());
    }

    /** Puts the item into the colonist's bag through MineColonies' inventory window. */
    private void give(ICitizenData citizen, List<ItemStack> bag, Item item) {
        ContainerCitizenInventory window = Mockito.mock(ContainerCitizenInventory.class);
        Mockito.when(window.getCitizenData()).thenReturn(citizen);
        stage.talk.gifts().opened(stage.player, window);
        bag.set(bag.indexOf(ItemStack.EMPTY), new ItemStack(item));
        stage.talk.gifts().closed(stage.player, window);
    }

    private static List<ItemStack> bag() {
        return new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
    }

    private static void wear(ICitizenData citizen, List<ItemStack> bag) {
        InventoryCitizen inventory = Mockito.mock(InventoryCitizen.class);
        Mockito.when(inventory.getSlots()).thenReturn(bag.size());
        Mockito.when(inventory.getStackInSlot(ArgumentMatchers.anyInt())).thenAnswer(invocation -> bag.get((int) invocation.getArgument(0)));
        Mockito.when(citizen.getInventory()).thenReturn(inventory);
    }
}

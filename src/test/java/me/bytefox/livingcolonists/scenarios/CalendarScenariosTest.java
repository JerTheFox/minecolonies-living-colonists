package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.inventory.container.ContainerCitizenInventory;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.friendship.Calendar;
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.mood.Mood;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/** The colony calendar (#67): days that pass with players around, holidays and birthdays. */
class CalendarScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    /** The Flower Festival: spring, day 13. */
    private static final int FLOWERS = 12;
    private Stage stage;
    private Calendar calendar;
    private ICitizenData darcy;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        calendar = stage.talk.calendar();
        darcy = stage.citizens.get(0);
        stage.body(darcy, new net.minecraft.core.BlockPos(3, 64, 0));
        stage.body(stage.citizens.get(1), new net.minecraft.core.BlockPos(-3, 64, 0));
        stage.dice(0);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    @Test
    void aDayPassesAfterFiveMinutesWithAMemberInTheColony() throws Exception {
        assertTrue(calendar.date(stage.colony).isEmpty());
        stage.runTicks(5 * 60 * 20 + 400);
        assertEquals(0, calendar.date(stage.colony).orElseThrow().day());
        assertTrue(calendar.birthday(darcy).isPresent(), "no birthday given out");
    }

    @Test
    void noDayPassesWhileNobodyIsAround() throws Exception {
        stage.playerAway(true);
        stage.runTicks(5 * 60 * 20 + 400);
        assertTrue(calendar.date(stage.colony).isEmpty(), "a day passed with nobody in the colony");
    }

    @Test
    void aHolidayIsCelebratedAndTalkedAbout() throws Exception {
        calendar.setDay(stage.colony, FLOWERS);
        assertEquals("flowers", calendar.holiday(stage.colony).orElseThrow().id());
        assertEquals(Mood.FESTIVE, stage.talk.moods().leading(stage.colony.getID(), stage.server().getTickCount()).mood());
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("Flower Festival")), WAIT),
                "nobody talked of the festival");
        stage.passDays(1);
        assertTrue(calendar.holiday(stage.colony).isEmpty(), "the festival went on into the next game day");
    }

    @Test
    void noNightOfRemembranceWhileNobodyHasDied() {
        int remembrance = 2 * 24 + 18;
        calendar.setDay(stage.colony, remembrance);
        assertTrue(calendar.holiday(stage.colony).isEmpty(), "remembered nobody");
        ModEvents.BUS.post(new GameEvents.CitizenDied(stage.colony, "Ella Moss"));
        calendar.setDay(stage.colony, remembrance + 96);
        assertEquals("remembrance", calendar.holiday(stage.colony).orElseThrow().id());
    }

    @Test
    void aBirthdayGiftCountsTwiceAndOutsideTheWeeklyLimit() {
        calendar.setDay(stage.colony, 0);
        int birthday = calendar.birthday(darcy).orElseThrow();
        List<ItemStack> bag = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        InventoryCitizen inventory = Mockito.mock(InventoryCitizen.class);
        Mockito.when(inventory.getSlots()).thenReturn(bag.size());
        Mockito.when(inventory.getStackInSlot(ArgumentMatchers.anyInt())).thenAnswer(invocation -> bag.get((int) invocation.getArgument(0)));
        Mockito.when(darcy.getInventory()).thenReturn(inventory);
        give(bag, Items.BREAD);
        give(bag, Items.BREAD);
        int two = rapport();
        calendar.setDay(stage.colony, 96 + birthday);
        assertTrue(calendar.isBirthday(darcy));
        give(bag, Items.BREAD);
        int gain = rapport() - two;
        assertTrue(gain >= 2 * Taste.LIKE.warmth(1), "the birthday gift didn't count twice: " + gain);
    }

    @Test
    void theNextBirthdaysAndHolidaysAreComingUp() {
        calendar.setDay(stage.colony, FLOWERS - 3);
        assertTrue(calendar.upcoming(stage.colony, 7).stream().anyMatch(u -> u.holiday() != null && u.holiday().id().equals("flowers")
                && u.inDays() == 3), calendar.upcoming(stage.colony, 7).toString());
    }

    private int rapport() {
        return stage.bonds.playerRapport(darcy, stage.player.getUUID());
    }

    private void give(List<ItemStack> bag, net.minecraft.world.item.Item item) {
        ContainerCitizenInventory window = Mockito.mock(ContainerCitizenInventory.class);
        Mockito.when(window.getCitizenData()).thenReturn(darcy);
        stage.talk.gifts().opened(stage.player, window);
        bag.set(bag.indexOf(ItemStack.EMPTY), new ItemStack(item));
        stage.talk.gifts().closed(stage.player, window);
    }
}

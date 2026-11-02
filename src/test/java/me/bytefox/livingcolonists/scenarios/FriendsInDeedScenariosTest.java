package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.entity.citizen.citizenhandlers.CitizenSkillHandler;
import me.bytefox.livingcolonists.friendship.Letter;
import me.bytefox.livingcolonists.friendship.Taste;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Friends in deed (#67): letters with gifts, help in trouble by job, and friendship in the colonists' work. */
class FriendsInDeedScenariosTest {
    private Stage stage;
    private ICitizenData darcy;
    private ICitizenData hunter;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        hunter = stage.citizens.get(1);
        stage.dice(0);
        when(stage.player.getInventory()).thenReturn(new Inventory(stage.player));
        when(stage.player.getActiveEffects()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    /** {@code times} loved gifts' worth of friendship, 12 each: 2 make a friend, 7 a best friend. */
    private void befriend(ICitizenData citizen, int times) {
        stage.bonds.gift(citizen, stage.player.getUUID(), "Alex", "Alex gave me cake", 3, List.of("Cake"), Taste.LOVE, Map.of(), times);
    }

    private static void job(ICitizenData citizen, String path) {
        IJob<?> job = mock(IJob.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(job.getJobRegistryEntry().getKey()).thenReturn(ResourceLocation.fromNamespaceAndPath("minecolonies", path));
        when(citizen.getJob()).thenAnswer(invocation -> job);
    }

    private void hurt(float health) {
        when(stage.player.getHealth()).thenReturn(health);
        when(stage.player.getMaxHealth()).thenReturn(20f);
        when(stage.player.isDeadOrDying()).thenReturn(false);
    }

    @Test
    void aFriendWritesOnANewCalendarDayAndTheGiftCanBeTaken() {
        job(darcy, "baker");
        befriend(darcy, 2);
        stage.talk.calendar().setDay(stage.colony, 1);
        List<Letter> letters = stage.talk.letters().of(stage.player.getUUID());
        assertEquals(1, letters.size(), "no letter from a friend");
        Letter letter = letters.get(0);
        assertEquals("Darcy Abbott", letter.from());
        assertFalse(letter.gift().isEmpty(), "a letter without a gift");
        assertTrue(stage.talk.letters().take(stage.player, letter.id()), "the gift couldn't be taken");
        assertTrue(stage.player.getInventory().contains(letter.gift()), "the gift isn't in the inventory");
        assertTrue(stage.talk.letters().of(stage.player.getUUID()).get(0).taken());
    }

    @Test
    void aFriendWritesAtMostOnceAWeek() {
        befriend(darcy, 2);
        for (int day = 1; day <= 6; day++) {
            stage.talk.calendar().setDay(stage.colony, day);
        }
        assertEquals(1, stage.talk.letters().of(stage.player.getUUID()).size());
        stage.talk.calendar().setDay(stage.colony, 8);
        assertEquals(2, stage.talk.letters().of(stage.player.getUUID()).size());
    }

    @Test
    void someoneWhoLikesThePlayerSendsANamelessGiftOnTheGiftExchange() {
        stage.bonds.gift(hunter, stage.player.getUUID(), "Alex", "Alex gave me bread", 3, List.of("Bread"), Taste.LIKE, Map.of(), 1);
        stage.talk.calendar().setDay(stage.colony, 3 * 24 + 12);
        List<Letter> letters = stage.talk.letters().of(stage.player.getUUID());
        assertTrue(letters.stream().anyMatch(l -> l.kind().equals("exchange") && l.from().isEmpty()), letters.toString());
    }

    @Test
    void aBadlyHurtPlayerIsHealedByAFriendlyHealerNearby() {
        job(darcy, "healer");
        befriend(darcy, 2);
        stage.body(darcy, new BlockPos(3, 0, 0));
        hurt(4);
        stage.talk.help().hurt(stage.player, null);
        verify(stage.player).heal(8f);
    }

    @Test
    void aStrangerDoesntHelpAndAFriendHelpsOnceInAWhile() {
        job(darcy, "healer");
        stage.body(darcy, new BlockPos(3, 0, 0));
        hurt(4);
        stage.talk.help().hurt(stage.player, null);
        verify(stage.player, never()).heal(org.mockito.ArgumentMatchers.anyFloat());
        befriend(darcy, 2);
        hurt(12);
        stage.talk.help().hurt(stage.player, null);
        verify(stage.player, never()).heal(org.mockito.ArgumentMatchers.anyFloat());
    }

    @Test
    void aFriendlyCookHandsOverFood() {
        job(darcy, "cook");
        befriend(darcy, 2);
        stage.body(darcy, new BlockPos(3, 0, 0));
        hurt(4);
        stage.talk.help().hurt(stage.player, mock(Zombie.class));
        assertFalse(stage.player.getInventory().isEmpty(), "no food from the cook");
    }

    @Test
    void aBestFriendWalksFasterAndLearnsFaster() throws Exception {
        befriend(darcy, 7);
        var entity = stage.body(darcy, new BlockPos(3, 0, 0));
        AttributeInstance speed = mock(AttributeInstance.class);
        when(entity.getAttribute(Attributes.MOVEMENT_SPEED)).thenReturn(speed);
        IJob<?> job = mock(IJob.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        WorkerBuildingModule module = mock(WorkerBuildingModule.class);
        when(module.getPrimarySkill()).thenReturn(Skill.Strength);
        when(job.getWorkModule()).thenReturn(module);
        when(darcy.getJob()).thenAnswer(invocation -> job);
        CitizenSkillHandler.SkillData skill = mock(CitizenSkillHandler.SkillData.class);
        when(skill.getExperience()).thenReturn(10.0, 20.0, 25.0);
        when(darcy.getCitizenSkillHandler().getSkills()).thenReturn(Map.of(Skill.Strength, skill));

        stage.runTicks(201);
        ArgumentCaptor<AttributeModifier> modifier = ArgumentCaptor.forClass(AttributeModifier.class);
        verify(speed).addTransientModifier(modifier.capture());
        assertEquals(0.25, modifier.getValue().amount(), 1e-9, "not the full bonus for a best friend");
        stage.runTicks(201);
        verify(darcy.getCitizenSkillHandler()).addXpToSkill(eq(Skill.Strength), eq(5.0), any());
    }

    @Test
    void nobodyWhoKnowsThemNoBonus() throws Exception {
        var entity = stage.body(hunter, new BlockPos(3, 0, 0));
        AttributeInstance speed = mock(AttributeInstance.class);
        when(entity.getAttribute(Attributes.MOVEMENT_SPEED)).thenReturn(speed);
        stage.runTicks(201);
        verify(speed, never()).addTransientModifier(any());
        verify(hunter.getCitizenSkillHandler(), never()).addXpToSkill(any(), anyDouble(), any());
    }
}

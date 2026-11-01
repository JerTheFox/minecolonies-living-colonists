package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import me.bytefox.livingcolonists.bonds.Moods;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** How colonists feel about how players treat them and who they live beside, in MineColonies' happiness (#61). */
class HappinessScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey", "Fiona Lowe");
        darcy = stage.citizens.get(0);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    /** The modifiers of {@code id} the colonist was given so far. */
    private static List<IHappinessModifier> given(ICitizenData citizen, String id) {
        ArgumentCaptor<IHappinessModifier> added = ArgumentCaptor.forClass(IHappinessModifier.class);
        verify(citizen.getCitizenHappinessHandler(), atLeastOnce()).addModifier(added.capture());
        return added.getAllValues().stream().filter(modifier -> modifier.getId().equals(id)).toList();
    }

    private void talk(String words, String conduct) throws Exception {
        stage.model.remembersWhen(words, conduct);
        stage.playerWritesTo("Darcy Abbott", words);
        assertTrue(stage.runUntil(() -> stage.bonds.playerRapport(darcy, stage.player.getUUID()) != 0, WAIT), "not taken in");
    }

    @Test
    void kindTalkMakesAColonistHappierForAFewDays() throws Exception {
        talk("спасибо тебе, ты лучший пекарь", "kind | Alex thanked Darcy Abbott warmly.");
        List<IHappinessModifier> mood = given(darcy, Moods.PLAYERS);
        assertTrue(mood.get(mood.size() - 1).getFactor(darcy) > 1.0, "kindness didn't lift");
    }

    @Test
    void theMoodComesBackAfterMineColoniesDropsItOnLoad() throws Exception {
        talk("спасибо тебе, ты лучший пекарь", "kind | Alex thanked Darcy Abbott warmly.");
        int before = given(darcy, Moods.PLAYERS).size();
        // MineColonies keeps only its own modifiers when the world loads: ours is gone (the stub has none).
        stage.runTicks(400);
        List<IHappinessModifier> mood = given(darcy, Moods.PLAYERS);
        assertTrue(mood.size() > before, "not put back");
        assertTrue(mood.get(mood.size() - 1).getFactor(darcy) > 1.0, "put back without the lift");
    }

    @Test
    void theMoodRunsOutWithItsDays() throws Exception {
        talk("спасибо тебе, ты лучший пекарь", "kind | Alex thanked Darcy Abbott warmly.");
        stage.skipTime(4 * 24000);
        int before = given(darcy, Moods.PLAYERS).size();
        stage.runTicks(400);
        assertEquals(before, given(darcy, Moods.PLAYERS).size(), "put back after it ran out");
    }

    @Test
    void rudeTalkMakesThemUnhappier() throws Exception {
        talk("ты бездельник", "rude | Alex called Darcy Abbott a loafer.");
        List<IHappinessModifier> mood = given(darcy, Moods.PLAYERS);
        assertTrue(mood.get(mood.size() - 1).getFactor(darcy) < 1.0, "rudeness didn't drag");
    }

    @Test
    void aFriendAtHomeLiftsAQuarrelAtWorkDrags() {
        ICitizenData hunter = stage.citizens.get(1);
        ICitizenData fiona = stage.citizens.get(2);
        IBuilding home = mock(IBuilding.class);
        IBuilding work = mock(IBuilding.class);
        when(darcy.getHomeBuilding()).thenReturn(home);
        when(hunter.getHomeBuilding()).thenReturn(home);
        when(fiona.getWorkBuilding()).thenReturn(work);
        when(stage.citizens.get(1).getWorkBuilding()).thenReturn(work);
        stage.bonds.colonists().nudge(stage.colony, 1, 2, 50);
        stage.bonds.colonists().nudge(stage.colony, 2, 3, -50);
        stage.passDays(2);
        assertTrue(given(darcy, Moods.NEIGHBOURS).get(0).getFactor(darcy) > 1.0, "a friend at home didn't lift Darcy");
        List<IHappinessModifier> hunterMood = given(hunter, Moods.NEIGHBOURS);
        assertTrue(!hunterMood.isEmpty(), "Hunter, with a friend and a quarrel, got nothing");
        IHappinessModifier fionaMood = given(fiona, Moods.NEIGHBOURS).get(0);
        assertTrue(fionaMood.getFactor(fiona) < 1.0, "a quarrel at work didn't drag Fiona");
    }
}

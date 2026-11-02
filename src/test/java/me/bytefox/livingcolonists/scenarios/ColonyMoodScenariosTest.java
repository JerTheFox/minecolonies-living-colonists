package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import me.bytefox.livingcolonists.mood.Mood;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The colony's mood in what colonists say (#65): a death puts everyone in mourning, the family most. */
class ColonyMoodScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(25);
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey", "Fiona Lowe");
        for (int i = 0; i < stage.citizens.size(); i++) {
            stage.body(stage.citizens.get(i), new BlockPos(3 * i, 64, 2));
        }
        stage.dice(0);
        stage.runTicks(150);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    @Test
    void aDeathPutsTheColonyInMourningAndEveryLineShowsIt() throws Exception {
        stage.citizenEvent("citizen_died", "Ann Lowe");
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("The colony is in mourning")),
                WAIT), "the colony's lines don't know it is in mourning");
        assertTrue(stage.talk.moods().leading(stage.colony.getID(), stage.server().getTickCount()).mood() == Mood.GRIEF);
        int before = stage.replies().size();
        stage.playerWritesTo("Darcy Abbott", "как дела?");
        assertTrue(stage.runUntil(() -> stage.replies().stream().skip(before)
                .anyMatch(r -> r.toString().contains("The colony is in mourning")), WAIT), "a private talk forgets the mourning");
    }

    @Test
    void whileGriefIsStrongTheColonistsOwnTalkComesBackToIt() throws Exception {
        stage.citizenEvent("citizen_died", "Ann Lowe");
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("has died")), WAIT),
                "the death never came up");
        assertTrue(stage.runUntil(() -> stage.replies().stream()
                .anyMatch(r -> r.toString().contains("what the whole colony feels right now")), Duration.ofSeconds(40)),
                "the colonists' own talk never came back to the death");
    }

    @Test
    void aHungryColonyTurnsGlum() throws Exception {
        stage.citizens.forEach(c -> org.mockito.Mockito.when(c.getSaturation()).thenReturn(2.0));
        assertTrue(stage.runUntil(() -> stage.talk.moods().leading(stage.colony.getID(), stage.server().getTickCount()).mood()
                == Mood.GLOOM, Duration.ofSeconds(10)), "hunger went unnoticed");
    }

    @Test
    void aTalkColonistsStartThemselvesHasAShape() throws Exception {
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("on your own")
                && r.toString().contains("It's an ordinary chat")), Duration.ofSeconds(40)), "no shape given to the talk");
        assertTrue(me.bytefox.livingcolonists.debug.Counters.snapshot().getOrDefault("talk.shape.ordinary", 0L) > 0);
    }

    @Test
    void aQuarrelBetweenColonistsIsSomethingToTalkAbout() throws Exception {
        stage.bonds.colonists().nudge(stage.colony, 1, 2, -50);
        // Ordinary dice: a topic with something at stake sits beside the neutral ones and comes up now and then.
        me.bytefox.livingcolonists.Dice.use(new java.util.Random(11));
        assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("can't stand each other")
                || r.toString().contains("gets on your nerves")), Duration.ofSeconds(60)), "the quarrel never came up");
    }

    @Test
    void aCalmColonySaysNothingOfMoods() throws Exception {
        stage.playerWritesTo("Darcy Abbott", "как дела?");
        assertTrue(stage.runUntil(() -> !stage.replies().isEmpty(), WAIT), "no answer");
        assertTrue(stage.replies().stream().noneMatch(r -> r.toString().contains("The colony is in")), "a mood out of nowhere");
    }
}

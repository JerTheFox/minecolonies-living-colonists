package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import com.minecolonies.api.colony.ICitizenData;
import me.bytefox.livingcolonists.Afterlife;
import me.bytefox.livingcolonists.memory.Graves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A colonist who dies and is brought back by the undertaker keeps who they were. */
class AfterlifeScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private Stage stage;
    private ICitizenData darcy;
    private Afterlife afterlife;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        darcy = stage.citizens.get(0);
        afterlife = new Afterlife(stage.personalities, stage.memory, stage.bonds, stage.talk.calendar());
        stage.model.remembersWhen("принесу тебе изумруд", "promise | 1 | supply | 3 | Alex promised to bring Darcy Abbott an emerald\n"
                + "4 | Alex | Alex loves fishing.");
        stage.playerWritesTo("Darcy Abbott", "принесу тебе изумруд");
        assertTrue(stage.runUntil(() -> !stage.bonds.promises(darcy).isEmpty() && !stage.memory.facts(darcy).isEmpty(), WAIT),
                "nothing was remembered");
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private long now() {
        return stage.colony.getWorld().getGameTime();
    }

    /** Darcy dies; the mod's check for gone colonists runs. */
    private void dies() {
        afterlife.died(darcy, now());
        stage.citizens.remove(darcy);
        sweep();
    }

    private void sweep() {
        afterlife.expire(now());
        afterlife.check(stage.colony);
        stage.memory.forget(afterlife.forgettable(now()));
        stage.bonds.forget(afterlife.forgettable(now()));
        stage.personalities.forget(afterlife.forgettable(now()));
    }

    private static boolean remembers(Stage stage, ICitizenData citizen, String text) {
        return stage.memory.facts(citizen).stream().anyMatch(fact -> fact.text().contains(text));
    }

    @Test
    void broughtBackWithANewIdTheyKeepTheirMemoriesAndKnowTheyDied() {
        dies();
        assertTrue(!stage.bonds.promises(darcy).isEmpty(), "dropped at once, before the undertaker could come");
        ICitizenData back = stage.newcomer(12, "Darcy Abbott");
        sweep();
        assertTrue(remembers(stage, back, "loves fishing"), "memories lost: " + stage.memory.facts(back));
        assertTrue(remembers(stage, back, "brought me back to life"), "doesn't know they died");
        assertTrue(stage.bonds.promises(back).stream().anyMatch(p -> p.text().contains("an emerald")), "the promise to them is lost");
    }

    @Test
    void nobodyCameBackSoTheMemoriesGoAfterAFewDays() {
        dies();
        stage.skipTime(Graves.KEEP_TICKS);
        sweep();
        assertTrue(stage.bonds.promises(darcy).isEmpty(), "kept forever");
        assertTrue(stage.memory.facts(darcy).isEmpty(), "kept forever");
        ICitizenData stranger = stage.newcomer(12, "Darcy Abbott");
        sweep();
        assertTrue(!remembers(stage, stranger, "brought me back"), "a late namesake taken for the dead colonist");
    }

    @Test
    void aColonistInMourningKnowsWhyTheyDontWork() throws Exception {
        ICitizenData hunter = stage.citizens.get(1);
        org.mockito.Mockito.when(hunter.getCitizenMournHandler().isMourning()).thenReturn(true);
        org.mockito.Mockito.when(hunter.getCitizenMournHandler().getDeceasedCitizens()).thenReturn(java.util.Set.of("Ann Lowe"));
        int before = stage.replies().size();
        stage.playerWritesTo("Hunter Casey", "почему не работаешь?");
        assertTrue(stage.runUntil(() -> stage.replies().size() > before, WAIT), "no answer");
        assertTrue(stage.replies().get(stage.replies().size() - 1).toString().contains("in mourning for Ann Lowe"),
                "the colonist doesn't know they're mourning");
    }

    @Test
    void aNewcomerWithAnotherNameIsNotTheDeadColonist() {
        dies();
        ICitizenData newcomer = stage.newcomer(12, "Rowan Hale");
        sweep();
        assertTrue(stage.memory.facts(newcomer).isEmpty(), "a stranger got the dead colonist's memories");
        assertTrue(stage.bonds.promises(newcomer).isEmpty());
    }
}

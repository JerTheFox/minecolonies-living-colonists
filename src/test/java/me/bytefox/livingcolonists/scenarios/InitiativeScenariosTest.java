package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Colonists speaking up on their own: greetings on coming back, and rumours of what the player did out there (#39). */
class InitiativeScenariosTest {
    private static final Duration WAIT = Duration.ofSeconds(30);
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey");
        stage.body(stage.citizens.get(0), new BlockPos(3, 64, 0));
        stage.body(stage.citizens.get(1), new BlockPos(-3, 64, 0));
        stage.dice(0);
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private boolean writtenFirstAbout(String text) {
        return stage.replies().stream().anyMatch(r -> r.toString().contains(text));
    }

    /** The colony has seen the player once: the first visit only explains the keys. */
    private void firstVisit() throws InterruptedException {
        stage.runUntil(() -> stage.chat.stream().anyMatch(line -> line.contains("livingcolonists.help.first_visit")), WAIT);
        stage.runUntil(() -> false, Duration.ofSeconds(1));
    }

    @Test
    void comingBackFromABigDeedTheColonyHasHeardOfIt() throws Exception {
        firstVisit();
        stage.playerAway(true);
        stage.runUntil(() -> false, Duration.ofMillis(500));
        stage.earns("minecraft:end/kill_dragon", "Free the End");
        stage.skipTime(3000);
        stage.playerAway(false);
        assertTrue(stage.runUntil(() -> writtenFirstAbout("killed the Ender Dragon"), WAIT),
                "nobody brought up the dragon; replies: " + stage.replies().size());
    }

    @Test
    void comingBackAfterADayAColonistGreetsThePlayer() throws Exception {
        firstVisit();
        stage.playerAway(true);
        stage.runUntil(() -> false, Duration.ofMillis(500));
        stage.skipTime(24000 + 100);
        stage.playerAway(false);
        assertTrue(stage.runUntil(() -> writtenFirstAbout("who has just come back to the colony"), WAIT),
                "no greeting; replies: " + stage.replies().size());
    }
}

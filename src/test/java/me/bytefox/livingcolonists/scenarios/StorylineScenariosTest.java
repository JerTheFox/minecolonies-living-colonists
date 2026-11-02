package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import me.bytefox.livingcolonists.memory.Digests;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** A running story between two colonists (#65) is carried on in their own talk, from where it was. */
class StorylineScenariosTest {
    @Test
    void aRunningStoryIsCarriedOnInTheColonistsOwnTalk() throws Exception {
        try (Stage stage = new Stage("Darcy Abbott", "Hunter Casey")) {
            stage.body(stage.citizens.get(0), new BlockPos(0, 64, 2));
            stage.body(stage.citizens.get(1), new BlockPos(3, 64, 2));
            // Darcy and Hunter fell out over the woodpile, as colony chats wrote down.
            for (int i = 0; i < 4; i++) {
                stage.bonds.colonists().noteColonists(stage.colony, stage.citizens, List.of(
                        new Digests.ColonistBond(false, "Darcy Abbott", "Hunter Casey", "Darcy Abbott and Hunter Casey argued over the woodpile")));
            }
            stage.dice(0);
            assertTrue(stage.runUntil(() -> stage.replies().stream().anyMatch(r -> r.toString().contains("to carry on the story between you")
                    && r.toString().contains("argued over the woodpile")), Duration.ofSeconds(90)), "the story was never carried on");
        }
    }
}

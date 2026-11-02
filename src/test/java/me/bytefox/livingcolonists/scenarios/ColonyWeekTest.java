package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import me.bytefox.livingcolonists.Dice;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A week of colony life on fast-forward (#64): colonists talk while the player is around, and don't flood the chat.
 * About two minutes: run with {@code ./gradlew test -PwithSlow --tests '*ColonyWeekTest'}.
 */
@Tag("slow")
class ColonyWeekTest {
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey", "Fiona Lowe", "Idris Vale");
        for (int i = 0; i < stage.citizens.size(); i++) {
            stage.body(stage.citizens.get(i), new BlockPos(3 * i, 64, 2));
        }
        Dice.use(new Random(42));
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    private long colonistLines() {
        return stage.chat.stream().filter(line -> line.startsWith("<") && !line.startsWith("<Alex>")).count();
    }

    @Test
    void aWeekOfVisitsHasTalkButNoFlood() throws Exception {
        List<Long> perVisit = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            stage.playerAway(false);
            long before = colonistLines();
            stage.runTicks(6000);
            perVisit.add(colonistLines() - before);
            stage.playerAway(true);
            stage.runTicks(200);
            stage.skipTime(18000);
        }
        long total = perVisit.stream().mapToLong(Long::longValue).sum();
        System.out.println("Colonists' lines per 5-minute visit over a week: " + perVisit + ", total " + total);
        assertTrue(total > 0, "a whole week without a word");
        assertTrue(perVisit.stream().allMatch(lines -> lines <= 30), "a flood in one visit: " + perVisit);
    }
}

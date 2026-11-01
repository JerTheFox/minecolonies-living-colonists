package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.events.ModEvents;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * How colonists behave across the mod's modules (#64): every kind of colony event reaches their talk, without warnings
 * in the log; news is told once; a model stuck on one line doesn't make the chat go round in circles. The model is a
 * stand-in, so this checks the mechanics, not how well colonists put things.
 */
class ColonyBehaviourTest {
    private static final Duration WAIT = Duration.ofSeconds(25);

    private record Case(String name, Consumer<Stage> happens, String told) {
    }

    private static Stage stage() throws Exception {
        Stage stage = new Stage("Darcy Abbott", "Hunter Casey", "Fiona Lowe");
        for (int i = 0; i < stage.citizens.size(); i++) {
            stage.body(stage.citizens.get(i), new BlockPos(3 * i, 64, 2));
        }
        stage.dice(0);
        return stage;
    }

    private static boolean told(Stage stage, String text) {
        return stage.replies().stream().anyMatch(r -> r.toString().contains(text));
    }

    @TestFactory
    Stream<DynamicTest> everyKindOfColonyEventReachesTheColonistsTalk() {
        return Stream.of(
                new Case("a death", s -> s.citizenEvent("citizen_died", "Hunter Casey"), "Hunter Casey has died"),
                new Case("a birth", s -> s.citizenEvent("citizen_born", "Ann Lowe"), "was just born"),
                new Case("a child grown up", s -> s.citizenEvent("citizen_grown_up", "Fiona Lowe"), "has just grown up"),
                new Case("a newcomer", s -> s.citizenEvent("citizen_spawn", "Idris Vale"), "has just arrived in the colony"),
                new Case("a building finished", s -> s.buildingEvent("building_built", "baker"), "was just finished"),
                new Case("a new site", s -> s.building("baker", 0, new BlockPos(30, 64, 30)), "set out the place for a new"),
                new Case("a raid", s -> s.raid(true), "Raiders are attacking"),
                new Case("an illness", s -> ModEvents.BUS.post(new GameEvents.FellIll(s.citizens.get(0), "Influenza")),
                        "has fallen ill with Influenza"),
                new Case("a new job", s -> ModEvents.BUS.post(new GameEvents.JobChanged(s.citizens.get(1), null, "farmer")),
                        "has just become the colony's farmer")
        ).map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            try (Stage stage = stage()) {
                stage.runTicks(150);
                c.happens().accept(stage);
                assertTrue(stage.runUntil(() -> told(stage, c.told()), WAIT), c.name() + " never came up");
                assertTrue(stage.warnings.isEmpty(), "warnings: " + stage.warnings);
            }
        }));
    }

    @Test
    void newsIsToldOnce() throws Exception {
        try (Stage stage = stage()) {
            stage.runTicks(150);
            Map<String, Long> before = Counters.snapshot();
            stage.citizenEvent("citizen_died", "Hunter Casey");
            assertTrue(stage.runUntil(() -> told(stage, "Hunter Casey has died"), WAIT), "the death never came up");
            stage.runTicks(4000);
            long urgent = Counters.snapshot().getOrDefault("said.urgent", 0L) - before.getOrDefault("said.urgent", 0L);
            assertEquals(1, urgent, "the death was told more than once, or not as urgent news");
        }
    }

    @Test
    void aModelStuckOnOneLineDoesntMakeTheChatGoRound() throws Exception {
        try (Stage stage = stage()) {
            stage.model.replyLine = "Опять этот дождь, третий день подряд.";
            // Ordinary dice, so the talk runs its natural course instead of every roll coming up.
            me.bytefox.livingcolonists.Dice.use(new java.util.Random(7));
            stage.runTicks(150);
            stage.citizenEvent("citizen_born", "Ann Lowe");
            stage.runTicks(6000);
            List<String> lines = stage.chat.stream().filter(line -> line.startsWith("<") && !line.startsWith("<Alex>")).toList();
            Map<String, Long> same = lines.stream().collect(Collectors.groupingBy(line -> line.substring(line.indexOf('>') + 1).strip(),
                    Collectors.counting()));
            System.out.println("Stuck model, colonists' lines: " + lines.size() + ", repeats caught "
                    + Counters.snapshot().getOrDefault("repeats.caught", 0L));
            assertTrue(same.values().stream().allMatch(count -> count <= 2), "the same line over and over: " + same);
            assertTrue(stage.warnings.isEmpty(), "warnings: " + stage.warnings);
        }
    }
}

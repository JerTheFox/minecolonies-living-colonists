package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import me.bytefox.livingcolonists.prompt.PromptLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shape of a colony talk (#65), by weighted dice: an ordinary chat most of the time, not a reality show. */
class TalkShapesTest {
    @BeforeEach
    void table() {
        TalkShapes.use(PromptLibrary.bundled("content/talk.json"));
    }

    @Test
    void anOrdinaryChatIsTheCommonestAndRareShapesStayRare() {
        Random random = new Random(1);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            counts.merge(TalkShapes.pick(random).key(), 1, Integer::sum);
        }
        String commonest = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        assertEquals("ordinary", commonest, counts.toString());
        assertTrue(counts.get("ordinary") > 3000 && counts.get("ordinary") < 5000, counts.toString());
        assertTrue(counts.getOrDefault("confession", 0) < 150, "a confession every few talks: " + counts);
    }

    @Test
    void aboutOneTalkInFourGetsAnEnding() {
        Random random = new Random(2);
        int endings = 0;
        for (int i = 0; i < 10_000; i++) {
            if (TalkShapes.ending(random) != null) {
                endings++;
            }
        }
        assertTrue(endings > 2000 && endings < 3000, "endings: " + endings);
    }

    @Test
    void everyShapeTellsTheOpenerAndTheOthers() {
        TalkShapes.all().forEach(shape -> assertTrue(!shape.opening().isBlank() && !shape.others().isBlank(), shape.key()));
    }
}

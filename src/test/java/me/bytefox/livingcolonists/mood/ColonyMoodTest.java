package me.bytefox.livingcolonists.mood;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import me.bytefox.livingcolonists.prompt.PromptLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The colony's mood (#65): what events stir it, how it fades, which one leads. */
class ColonyMoodTest {
    private static final long MINUTE = 60 * 20;

    @BeforeEach
    void table() {
        MoodTable.use(PromptLibrary.bundled("content/moods.json"));
    }

    @Test
    void aCalmColonyByDefault() {
        ColonyMood mood = new ColonyMood();
        assertEquals(Mood.CALM, mood.leading(0).mood());
        assertEquals(Optional.empty(), mood.tone(0, -1));
    }

    @Test
    void aDeathBringsGriefThatFadesBackToCalm() {
        ColonyMood mood = new ColonyMood();
        mood.raise("death", "Randolph Casey has died", Set.of(), 0);
        assertEquals(Mood.GRIEF, mood.leading(MINUTE).mood());
        assertTrue(mood.tone(MINUTE, -1).orElseThrow().contains("Randolph Casey has died"));
        assertEquals(Mood.CALM, mood.leading(MoodTable.row("death").fadeTicks()).mood(), "grief never faded");
    }

    @Test
    void aWeakEventDoesntOverrideAStrongOne() {
        ColonyMood mood = new ColonyMood();
        mood.raise("raid", "raiders attack", Set.of(), 0);
        mood.raise("building_built", "the bakery was finished", Set.of(), MINUTE);
        assertEquals(Mood.PANIC, mood.leading(2 * MINUTE).mood());
    }

    @Test
    void theSameEventDoesntStirTheMoodAgainBeforeItsCooldown() {
        ColonyMood mood = new ColonyMood();
        mood.raise("enemies", "zombies by the farm", Set.of(), 0);
        double first = mood.leading(MINUTE).intensity();
        mood.raise("enemies", "zombies by the farm", Set.of(), MINUTE);
        assertEquals(first, mood.leading(MINUTE).intensity(), 1e-9, "raised again within the cooldown");
    }

    @Test
    void moodsMixWhenTheSecondIsStrongEnough() {
        ColonyMood mood = new ColonyMood();
        mood.raise("death", "Randolph Casey has died", Set.of(), 0);
        mood.raise("birth", "a baby was born", Set.of(), 0);
        String tone = mood.tone(MINUTE, -1).orElseThrow();
        assertTrue(tone.contains("Randolph") && tone.contains("baby"), tone);
    }

    @Test
    void closeOnesGrieveHarder() {
        ColonyMood mood = new ColonyMood();
        mood.raise("death", "Randolph Casey has died", Set.of(7), 0);
        String family = mood.tone(MINUTE, 7).orElseThrow();
        String others = mood.tone(MINUTE, 8).orElseThrow();
        assertTrue(!family.equals(others), "family and the rest grieve alike");
        assertTrue(family.contains(MoodTable.closeLine(Mood.GRIEF)), family);
    }

    @Test
    void anEventsInterestComesFromTheTable() {
        assertTrue(MoodTable.row("death").interest() > MoodTable.row("building_built").interest());
        assertEquals(0, MoodTable.row("no such occasion").interest());
    }

    @Test
    void theLeadingMoodSaysWhatStirredIt() {
        ColonyMood mood = new ColonyMood();
        mood.raise("death", "Randolph Casey has died", Set.of(), 0);
        ColonyMood.Leading leading = mood.leading(MINUTE);
        assertEquals("death", leading.occasion());
        assertEquals("Randolph Casey has died", leading.cause());
    }
}

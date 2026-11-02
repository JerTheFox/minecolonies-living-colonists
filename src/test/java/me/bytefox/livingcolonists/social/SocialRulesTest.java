package me.bytefox.livingcolonists.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import me.bytefox.livingcolonists.friendship.FriendshipLevels;
import me.bytefox.livingcolonists.mood.Mood;
import me.bytefox.livingcolonists.talk.SetLines;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The social layer without a model (#68): voices, words in passing, small talk and a menu that grows with friendship. */
class SocialRulesTest {
    @BeforeAll
    static void levels() throws IOException {
        try (InputStream in = SocialRulesTest.class.getResourceAsStream("/defaults/content/friendship.json")) {
            FriendshipLevels.use(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void charactersComeDownToFourVoices() {
        assertEquals(Voices.GRUMPY, Voices.of("grumbler"));
        assertEquals(Voices.CHEERFUL, Voices.of("optimist"));
        assertEquals("", Voices.of("a_server_archetype"));
    }

    @Test
    void aVoicedLineFirstElseAPlainOne() {
        String grumpy = SetLines.pick("bark.rain", Voices.GRUMPY, "en_us", new Random(1)).orElseThrow();
        assertTrue(grumpy.equals("Rain. Of course.") || grumpy.equals("Soaked again."), grumpy);
        assertTrue(SetLines.pick("bark.hungry", Voices.GRUMPY, "en_us", new Random(1)).isPresent(), "no plain line to fall back to");
    }

    @Test
    void aWordInPassingIsAboutWhatMattersMost() {
        assertEquals("bark.sick", Barks.occasion(Mood.FESTIVE, 50, true, false, false, false, 1000));
        assertEquals("bark.panic", Barks.occasion(Mood.PANIC, 50, false, false, false, true, 1000));
        assertEquals("bark.friend", Barks.occasion(Mood.CALM, 50, false, false, false, true, 1000));
        assertEquals("bark.rain", Barks.occasion(Mood.CALM, 0, false, false, false, true, 1000));
        assertEquals("bark.night", Barks.occasion(Mood.CALM, 0, false, false, false, false, 18000));
        assertEquals("bark.morning", Barks.occasion(Mood.CALM, 0, false, false, false, false, 24000 * 5 + 500));
    }

    @Test
    void smallTalkFollowsTheMoment() {
        assertEquals("grief", SmallTalk.topic(Mood.GRIEF, true, 0, true, 0));
        assertEquals("food", SmallTalk.topic(Mood.CALM, true, 0, true, 0));
        assertEquals("rain", SmallTalk.topic(Mood.CALM, true, 6000, false, 0.1));
        assertTrue(SetLines.pair("rain", "ru_ru", new Random(1)).orElseThrow()[1].length() > 0, "no answer to the line");
        assertTrue(SmallTalk.jab(-30));
    }

    @Test
    void theMenuGrowsWithFriendship() {
        assertTrue(!Acquaintance.options(0).contains("about"));
        assertTrue(Acquaintance.options(25).contains("about") && !Acquaintance.options(25).contains("news"));
        assertTrue(Acquaintance.options(45).contains("news"));
        assertEquals("bye", Acquaintance.options(45).get(Acquaintance.options(45).size() - 1));
        assertEquals(0, Acquaintance.hearts(0));
        assertEquals(Acquaintance.MAX_HEARTS, Acquaintance.hearts(100));
        assertTrue(Acquaintance.hearts(25) > Acquaintance.hearts(10));
    }

    @Test
    void aColonistsStoryIsTheirOwnAndTheSameEachTime() {
        int[] story = Acquaintance.story("1:5");
        assertEquals(story[0], Acquaintance.story("1:5")[0]);
        for (int part : story) {
            assertTrue(part >= 1 && part <= 12, "part " + part);
        }
    }
}

package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PromptsTest {
    @Test
    void jobNameComesFromTheRegistryPath() {
        assertEquals("baker", Prompts.jobName("baker"));
        assertEquals("stone smelter", Prompts.jobName("stone_smelter"));
        assertEquals("unemployed", Prompts.jobName(null));
    }

    @Test
    void timeOfDayFollowsMinecraftClock() {
        assertEquals("morning, about 6 o'clock", Prompts.timeOfDay(0));
        assertEquals("midday, about 12 o'clock", Prompts.timeOfDay(6000));
        assertEquals("afternoon, about 15 o'clock", Prompts.timeOfDay(9000));
        assertEquals("evening, about 18 o'clock", Prompts.timeOfDay(12000));
        assertEquals("night, about 0 o'clock", Prompts.timeOfDay(18000));
        assertEquals("dawn, about 5 o'clock", Prompts.timeOfDay(23500));
        assertEquals("morning, about 6 o'clock", Prompts.timeOfDay(24000 * 5 + 100));
    }

    @Test
    void worriesComeUpUnaskedInAboutAQuarterOfReplies() {
        java.util.Random random = new java.util.Random(3);
        int allowed = 0;
        for (int i = 0; i < 4000; i++) {
            if (Prompts.initiative(random).contains("you may add")) {
                allowed++;
            }
        }
        assertTrue(allowed > 850 && allowed < 1150, String.valueOf(allowed));
    }

    @Test
    void recognisesSilence() {
        assertTrue(Prompts.isSilence("[silence]"));
        assertTrue(Prompts.isSilence(" [Silence] "));
        assertTrue(Prompts.isSilence("*молчание*"));
        assertFalse(Prompts.isSilence("Silence is golden, they say."));
    }

    @Test
    void contraryArchetypesDisagreeMoreOften() {
        java.util.Random random = new java.util.Random(4);
        int grumbler = 0;
        int optimist = 0;
        for (int i = 0; i < 4000; i++) {
            grumbler += Prompts.stance(0.6, random).contains("don't agree") ? 1 : 0;
            optimist += Prompts.stance(0.15, random).contains("don't agree") ? 1 : 0;
        }
        assertTrue(grumbler > 2200 && optimist < 800, grumbler + " vs " + optimist);
    }

    @Test
    void aLongerThreadMovesOnToAnotherTopicMoreOften() {
        java.util.Random random = new java.util.Random(7);
        int early = 0;
        int late = 0;
        for (int i = 0; i < 4000; i++) {
            early += Prompts.stance(0.35, 2, random).contains("Change the subject") ? 1 : 0;
            late += Prompts.stance(0.35, 4, random).contains("Change the subject") ? 1 : 0;
        }
        assertTrue(late > 2 * early, early + " vs " + late);
    }

    @Test
    void theFirstAnswerToAnOpenerStaysOnItsTopic() {
        java.util.Random random = new java.util.Random(11);
        for (int i = 0; i < 2000; i++) {
            assertFalse(Prompts.stance(0.35, 1, random).contains("Change the subject"));
        }
    }

    @Test
    void aColonistKeepsOneMoodForTheWholeDay() {
        assertEquals(Prompts.mood(5, 12, 6.0), Prompts.mood(5, 12, 6.0));
        java.util.Set<String> moods = new java.util.HashSet<>();
        for (int day = 0; day < 60; day++) {
            moods.add(Prompts.mood(5, day, 6.0));
        }
        assertTrue(moods.size() >= 4, moods.toString());
    }

    @Test
    void unhappyColonistsAreInABadMoodMoreOften() {
        int unhappy = 0;
        int happy = 0;
        for (int citizen = 0; citizen < 2000; citizen++) {
            unhappy += Prompts.mood(citizen, 3, 2.0).contains("irritable") || Prompts.mood(citizen, 3, 2.0).contains("gloomy") ? 1 : 0;
            happy += Prompts.mood(citizen, 3, 9.5).contains("irritable") || Prompts.mood(citizen, 3, 9.5).contains("gloomy") ? 1 : 0;
        }
        assertTrue(unhappy > 2 * happy, unhappy + " vs " + happy);
    }

    @Test
    void cleansBackgrounds() {
        assertEquals("Grumbles a lot. Works hard.", Prompts.cleanBio("  §6Grumbles a lot.\n\nWorks   hard.  "));
        assertEquals(Prompts.MAX_BIO_LENGTH, Prompts.cleanBio("a".repeat(5000)).length());
    }

    @Test
    void cleansReplies() {
        assertEquals("Stone, eight of it.", Prompts.cleanReply("John Brown: \"Stone, eight of it.\"", "John Brown"));
        assertEquals("Line one line two", Prompts.cleanReply("  Line one\n\nline two ", "John Brown"));
        assertEquals("«Нет» — значит нет.", Prompts.cleanReply("«Нет» — значит нет.", "John Brown"));
        assertEquals(Prompts.MAX_REPLY_LENGTH, Prompts.cleanReply("a".repeat(1000), "John").length());
        assertEquals("plain text", Prompts.cleanReply("§kplain §ctext§r", "John"));
    }

    @Test
    void changingTheSubjectPicksATopicTheThreadHasntHad() {
        Random random = new Random(3);
        java.util.Set<String> used = new java.util.HashSet<>(Prompts.TOPICS.subList(1, Prompts.TOPICS.size()));
        String steer = Prompts.steer(random, used);
        assertEquals(Prompts.TOPICS.get(0), Prompts.topicOf(steer));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 300; i++) {
            seen.add(Prompts.topicOf(Prompts.steer(random, java.util.List.of())));
        }
        assertTrue(seen.size() > 30, "topics: " + seen.size());
        assertEquals(null, Prompts.topicOf("You partly agree"));
    }

    @Test
    void lineShapesVaryButAnAnswerIsNeverJustAQuestion() {
        Random random = new Random(4);
        int plain = 0;
        for (int i = 0; i < 2000; i++) {
            String form = Prompts.form(random, false);
            plain += form.isEmpty() ? 1 : 0;
            assertFalse(Prompts.form(random, true).contains("question"));
        }
        assertTrue(plain > 1000 && plain < 1300, "plain: " + plain);
    }

    @Test
    void aColonistKeepsOneVoice() {
        assertEquals(Prompts.voice("Idris Y. Crawley"), Prompts.voice("Idris Y. Crawley"));
        java.util.Set<String> voices = new java.util.HashSet<>();
        for (String name : java.util.List.of("Idris Y. Crawley", "Adair D. Asplin", "Rylee G. Gray", "Noe I. Gupta",
                "Elsa A. Crawley", "Landyn X. Gascoigne", "Teresa L. Kumar", "Brooke G. Kumar")) {
            voices.add(Prompts.voice(name));
        }
        assertTrue(voices.size() >= 4, "voices: " + voices.size());
    }

    @Test
    void chatLinesKeepTheirFirstSentences() {
        assertEquals("One. Two.", Prompts.firstSentences("One. Two. Three.", 2));
        assertEquals("Wait... what? No.", Prompts.firstSentences("Wait... what? No. Never.", 3).replace("Wait... what? No.", "Wait... what? No."));
        assertEquals("No dots here", Prompts.firstSentences("No dots here", 2));
        assertEquals("v1.5 is out. Yes!", Prompts.firstSentences("v1.5 is out. Yes! Great.", 2));
    }

    @Test
    void voiceLinesAreCleanedAndCapped() {
        assertEquals("Boards came crooked.\nBread's dry.", Prompts.voiceLines("1. Boards came crooked.\n- \"Bread's dry.\"\n"));
        assertEquals(4, Prompts.voiceLines("a1\nb2\nc3\nd4\ne5").split("\n").length);
    }
}

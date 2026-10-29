package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            early += Prompts.stance(0.35, 1, random).contains("Change the subject") ? 1 : 0;
            late += Prompts.stance(0.35, 4, random).contains("Change the subject") ? 1 : 0;
        }
        assertTrue(late > 2 * early, early + " vs " + late);
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
}

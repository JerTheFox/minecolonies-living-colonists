package me.bytefox.livingcolonists.talk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("morning", Prompts.timeOfDay(0));
        assertEquals("afternoon", Prompts.timeOfDay(6000));
        assertEquals("evening", Prompts.timeOfDay(12000));
        assertEquals("night", Prompts.timeOfDay(18000));
        assertEquals("dawn", Prompts.timeOfDay(23500));
        assertEquals("morning", Prompts.timeOfDay(24000 * 5 + 100));
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

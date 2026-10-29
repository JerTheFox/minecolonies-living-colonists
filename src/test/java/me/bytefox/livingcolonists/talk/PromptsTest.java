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
    void systemPromptNamesTheColonistAndThePlayer() {
        String prompt = Prompts.system("John Brown", "baker", "Riverside", "evening", "Max", false);

        assertTrue(prompt.contains("John Brown"));
        assertTrue(prompt.contains("baker"));
        assertTrue(prompt.contains("Riverside"));
        assertTrue(prompt.contains("evening"));
        assertTrue(prompt.contains("Max"));
    }

    @Test
    void colonyWidePromptSaysTheMessageWentToEveryone() {
        assertTrue(Prompts.system("John Brown", "baker", "Riverside", "evening", "Max", true).contains("whole colony"));
        assertTrue(Prompts.system("John Brown", "baker", "Riverside", "evening", "Max", false).contains("privately"));
    }

    @Test
    void cleansReplies() {
        assertEquals("Stone, eight of it.", Prompts.cleanReply("John Brown: \"Stone, eight of it.\"", "John Brown"));
        assertEquals("Line one line two", Prompts.cleanReply("  Line one\n\nline two ", "John Brown"));
        assertEquals("«Нет» — значит нет.", Prompts.cleanReply("«Нет» — значит нет.", "John Brown"));
        assertEquals(Prompts.MAX_REPLY_LENGTH, Prompts.cleanReply("a".repeat(1000), "John").length());
    }
}

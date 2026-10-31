package me.bytefox.livingcolonists.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PromptBudgetTest {
    private static final String TEMPLATE = "{situation}\n{memories}\n{transcript}";

    @Test
    void aPromptThatFitsIsLeftAlone() {
        Map<String, String> values = Map.of("situation", "a", "memories", "b", "transcript", "c");
        assertSame(values, PromptBudget.fit(values, TEMPLATE, 1000));
    }

    @Test
    void memoriesGoFirstThenTheOldestChatLines() {
        Map<String, String> values = new HashMap<>();
        values.put("situation", "- job\n- home");
        values.put("memories", "fact one\nfact two\nfact three");
        values.put("transcript", "old line\nnewer line\nnewest line");
        int full = PromptBudget.length(values, TEMPLATE);
        Map<String, String> fitted = PromptBudget.fit(values, TEMPLATE, full - "fact three".length() - 1);
        assertEquals("fact one\nfact two", fitted.get("memories"));
        assertEquals(values.get("transcript"), fitted.get("transcript"));
        Map<String, String> tight = PromptBudget.fit(values, TEMPLATE, 40);
        assertEquals("fact one", tight.get("memories"), "a section keeps its first line");
        assertTrue(tight.get("transcript").endsWith("newest line"), tight.get("transcript"));
        assertFalse(tight.get("transcript").startsWith("old line"), tight.get("transcript"));
    }

    @Test
    void sectionsNotInTheTemplateDontCount() {
        Map<String, String> values = Map.of("situation", "a", "unused", "x".repeat(5000));
        assertSame(values, PromptBudget.fit(values, "{situation}", 100));
    }
}

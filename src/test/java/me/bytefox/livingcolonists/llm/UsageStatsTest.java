package me.bytefox.livingcolonists.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class UsageStatsTest {
    @Test
    void addsUpRequestsPerModel() {
        UsageStats stats = new UsageStats();
        stats.record("flash", new ChatResult.Reply("a", new Usage(100, 20, 80)), 1200);
        stats.record("flash", new ChatResult.Failure("HTTP 429", true), 300);
        stats.record("pro", new ChatResult.Reply("b", new Usage(50, 10, 0)), 4000);

        UsageStats.Counts flash = stats.session().get("flash");
        assertEquals(2, flash.requests());
        assertEquals(1, flash.failures());
        assertEquals(100, flash.promptTokens());
        assertEquals(20, flash.completionTokens());
        assertEquals(80, flash.cachedTokens());
        assertEquals(750, flash.averageLatencyMillis());
        assertEquals(1, stats.session().get("pro").requests());
    }

    @Test
    void totalsIncludeWhatWasSavedBefore() {
        UsageStats stats = new UsageStats();
        stats.restore(Map.of("flash", new UsageStats.Counts(10, 1, 1000, 200, 500, 20000)));
        stats.record("flash", new ChatResult.Reply("a", new Usage(100, 20, 80)), 1000);

        assertEquals(1, stats.session().get("flash").requests());
        assertEquals(11, stats.total().get("flash").requests());
        assertEquals(1100, stats.total().get("flash").promptTokens());
    }

    @Test
    void remembersWhetherThereIsSomethingToSave() {
        UsageStats stats = new UsageStats();
        assertFalse(stats.takeDirty());
        stats.record("flash", new ChatResult.Reply("a", Usage.NONE), 10);
        assertTrue(stats.takeDirty());
        assertFalse(stats.takeDirty());
    }
}

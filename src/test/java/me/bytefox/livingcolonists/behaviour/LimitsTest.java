package me.bytefox.livingcolonists.behaviour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.Usage;
import me.bytefox.livingcolonists.llm.UsageStats;
import org.junit.jupiter.api.Test;

class LimitsTest {
    private static final UUID MAX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long HOUR = 72000;

    @Test
    void aPlayerMaySendOnlySoManyMessagesAnHour() {
        HourlyLimit limit = new HourlyLimit();
        assertEquals(Optional.empty(), limit.tryUse(MAX, 0, 2));
        assertEquals(Optional.empty(), limit.tryUse(MAX, 100, 2));
        assertEquals(Optional.of(HOUR - 200), limit.tryUse(MAX, 200, 2));
        assertEquals(Optional.empty(), limit.tryUse(MAX, HOUR + 1, 2));
    }

    @Test
    void noLimitMeansNoLimit() {
        HourlyLimit limit = new HourlyLimit();
        for (int i = 0; i < 100; i++) {
            assertTrue(limit.tryUse(MAX, i, 0).isEmpty());
        }
    }

    @Test
    void tokensAreCountedPerDay() {
        UsageStats stats = new UsageStats();
        LocalDate today = LocalDate.of(2026, 10, 29);
        stats.record("m", new ChatResult.Reply("hi", new Usage(100, 20, 0)), 10, today);
        stats.record("m", new ChatResult.Failure("x", false), 10, today);
        stats.record("m", new ChatResult.Reply("hi", new Usage(5, 5, 0)), 10, today);
        assertEquals(130, stats.tokensOn(today));
        stats.record("m", new ChatResult.Reply("hi", new Usage(7, 3, 0)), 10, today.plusDays(1));
        assertEquals(10, stats.tokensOn(today.plusDays(1)));
        assertEquals(0, stats.tokensOn(today));
    }
}

package me.bytefox.livingcolonists.bonds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class DeadlinesTest {
    private static final long DAY = 24000;
    /** Game time at nine in the morning of day 3 (day time 3000). */
    private static final long NOW = 3 * DAY + 3000;

    private static OptionalLong in(String line) {
        return Deadlines.due(List.of(new Deadlines.Said(line, NOW)), NOW, NOW % DAY);
    }

    @Test
    void theDeadlineCountsFromWhenItWasSaidNotWhenItWasWrittenDown() {
        long said = NOW - 3 * 1200;
        assertEquals(OptionalLong.of(said + 1200), Deadlines.due(List.of(new Deadlines.Said("принесу изумруд через минуту", said)),
                NOW, NOW % DAY));
        long evening = 3 * DAY + 12000;
        assertEquals(OptionalLong.of(evening), Deadlines.due(List.of(new Deadlines.Said("к вечеру", 3 * DAY + 2000)),
                3 * DAY + 13000, 13000), "said in the morning: this evening, though it is evening now");
    }

    @Test
    void minutesAndHoursAreRealTime() {
        assertEquals(OptionalLong.of(NOW + 10 * 1200), in("принесу доски через 10 минут"));
        assertEquals(OptionalLong.of(NOW + 10 * 1200), in("I'll bring planks in ten minutes"));
        assertEquals(OptionalLong.of(NOW + 2 * 72000), in("через два часа построю"));
        assertEquals(OptionalLong.of(NOW + 36000), in("через полчаса"));
        assertEquals(OptionalLong.of(NOW + 1200), in("in a minute"));
    }

    @Test
    void daysFollowTheGameClock() {
        long evening = 3 * DAY + 12000;
        assertEquals(OptionalLong.of(evening), in("к вечеру будет"));
        assertEquals(OptionalLong.of(evening), in("I'll do it tonight"));
        assertEquals(OptionalLong.of(4 * DAY + 12000), in("завтра принесу"));
        assertEquals(OptionalLong.of(NOW + 7 * DAY), in("через неделю"));
        assertEquals(OptionalLong.of(NOW + 3 * DAY), in("in 3 days"));
    }

    @Test
    void theLatestLineWithATimeWinsAndNoTimeIsNothing() {
        assertEquals(OptionalLong.of(NOW + 5 * 1200), Deadlines.due(List.of(new Deadlines.Said("завтра", NOW),
                new Deadlines.Said("нет, через 5 минут", NOW)), NOW, 3000));
        assertTrue(Deadlines.due(List.of(new Deadlines.Said("принесу доски", NOW), new Deadlines.Said("хорошо", NOW)), NOW, 3000)
                .isEmpty());
        assertTrue(in("минутку").isEmpty(), "\"a moment\" alone is no deadline");
    }

    @Test
    void eveningAlreadyPastMeansTomorrowEvening() {
        long late = 3 * DAY + 15000;
        assertEquals(OptionalLong.of(4 * DAY + 12000), Deadlines.due(List.of(new Deadlines.Said("к вечеру", late)), late, 15000));
    }
}

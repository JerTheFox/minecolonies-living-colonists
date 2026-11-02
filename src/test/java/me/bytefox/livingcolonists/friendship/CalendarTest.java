package me.bytefox.livingcolonists.friendship;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The colony calendar (#67): days that pass only with players around, the year, holidays and birthdays. */
class CalendarTest {
    private static final long NEED = 5 * 60 * 20;

    @BeforeAll
    static void table() throws IOException {
        try (InputStream in = CalendarTest.class.getResourceAsStream("/defaults/content/calendar.json")) {
            CalendarTable.use(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aDayPassesOnlyAfterFiveMinutesWithAPlayerAround() {
        Presence presence = new Presence();
        assertFalse(presence.near(10, NEED - 400, NEED), "counted before five minutes");
        assertTrue(presence.near(10, 400, NEED), "five minutes over a game day didn't count");
        assertEquals(0, presence.day());
        assertFalse(presence.near(10, NEED, NEED), "the same game day counted twice");
        assertTrue(presence.isToday(10));
        assertFalse(presence.isToday(11), "the day still counts once the game day is over");
    }

    @Test
    void minutesFromAnEarlierGameDayDontAddUp() {
        Presence presence = new Presence();
        presence.near(10, NEED - 400, NEED);
        assertFalse(presence.near(11, 400, NEED), "minutes of yesterday counted toward today");
        assertTrue(presence.near(11, NEED, NEED));
        assertEquals(0, presence.day(), "game days nobody was around for passed on the calendar");
    }

    @Test
    void ninetySixDaysAYearInFourSeasons() {
        assertEquals(96, CalendarTable.yearDays());
        CalendarDate first = new CalendarDate(0);
        assertEquals(1, first.year());
        assertEquals(0, first.season());
        assertEquals(1, first.dayOfSeason());
        CalendarDate summer = new CalendarDate(24);
        assertEquals(1, summer.season());
        assertEquals(1, summer.dayOfSeason());
        CalendarDate next = new CalendarDate(96 + 12);
        assertEquals(2, next.year());
        assertEquals(0, next.season());
        assertEquals(13, next.dayOfSeason());
    }

    @Test
    void eightHolidaysAYearAndFoundingDayFromTheSecond() {
        assertTrue(CalendarTable.holiday(new CalendarDate(0)).isEmpty(), "the colony's first day is no anniversary");
        assertEquals("founding", CalendarTable.holiday(new CalendarDate(96)).orElseThrow().id());
        assertEquals("harvest", CalendarTable.holiday(new CalendarDate(2 * 24 + 6)).orElseThrow().id());
        assertEquals("year_end", CalendarTable.holiday(new CalendarDate(95)).orElseThrow().id());
        long days = java.util.stream.IntStream.range(96, 192)
                .filter(day -> CalendarTable.holiday(new CalendarDate(day)).isPresent()).count();
        assertEquals(8, days);
    }

    @Test
    void birthdaysAreSpreadOverTheYear() {
        Set<Integer> taken = new HashSet<>();
        for (int citizen = 1; citizen <= 30; citizen++) {
            int day = Birthdays.pick(taken, "1:" + citizen, 96);
            assertTrue(day >= 0 && day < 96, "day " + day);
            assertTrue(taken.add(day), "two colonists born on day " + day);
        }
        assertEquals(Birthdays.pick(Set.of(), "1:7", 96), Birthdays.pick(Set.of(), "1:7", 96), "not the same each time");
    }

    @Test
    void holidayFavoursCountAsLoved() {
        Holiday flowers = CalendarTable.holiday(new CalendarDate(12)).orElseThrow();
        assertTrue(flowers.favours().contains("~flower"));
    }
}

package me.bytefox.livingcolonists.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DiaryBookTest {
    @Test
    void theSameDoingTwiceIsOneEntry() {
        DiaryBook book = new DiaryBook();
        assertTrue(book.note(100, 1000, "worked at the bakery"));
        assertFalse(book.note(700, 1600, "worked at the bakery"));
        assertTrue(book.note(15000, 15000, "slept outside, with no bed of your own"));
        assertEquals("in the morning: worked at the bakery; at night: slept outside, with no bed of your own",
                DiaryBook.text(book.latest(15000, 5)));
    }

    @Test
    void onlyTheLastDayAndTheLatestFewAreKept() {
        DiaryBook book = new DiaryBook();
        for (int i = 0; i < 10; i++) {
            book.note(i * 100, 0, "doing " + i);
        }
        List<DiaryBook.Entry> latest = book.latest(1000, 10);
        assertEquals(DiaryBook.MAX_ENTRIES, latest.size());
        assertEquals("doing 9", latest.get(latest.size() - 1).doing());
        assertTrue(book.latest(1000 + 30000, 10).isEmpty());
    }
}

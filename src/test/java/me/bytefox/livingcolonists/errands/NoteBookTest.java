package me.bytefox.livingcolonists.errands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class NoteBookTest {
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID BEA = UUID.randomUUID();

    private static NoteBook.Note mail(long made) {
        return new NoteBook.Note(NoteBook.Kind.MAIL, 1, 2, ALEX, "Alex", BEA, "Bea", "the bread is ready", made, made);
    }

    private static NoteBook.Note reminder(long due) {
        return new NoteBook.Note(NoteBook.Kind.REMINDER, 1, 2, ALEX, "Alex", ALEX, "Alex", "check the oven", 0, due);
    }

    @Test
    void aReminderWaitsForItsTime() {
        NoteBook book = new NoteBook();
        book.add(reminder(1000));
        assertTrue(book.due(999).isEmpty());
        assertEquals(1, book.due(1000).size());
    }

    @Test
    void deliveredNotesAreGone() {
        NoteBook book = new NoteBook();
        NoteBook.Note note = mail(0);
        book.add(note);
        book.delivered(note);
        assertTrue(book.due(10).isEmpty());
    }

    @Test
    void oldNotesExpire() {
        NoteBook book = new NoteBook();
        book.add(mail(0));
        book.add(reminder(100));
        assertEquals(2, book.due(100).size());
        assertTrue(book.due(100 + NoteBook.MAIL_KEEP + 1).isEmpty());
        assertTrue(book.all().isEmpty());
    }

    @Test
    void onePlayerCanOnlyLeaveSoMany() {
        NoteBook book = new NoteBook();
        for (int i = 0; i < NoteBook.MAX_PER_PLAYER; i++) {
            assertTrue(book.add(mail(i)));
        }
        assertFalse(book.add(mail(99)));
        assertTrue(book.add(reminder(5)));
    }

    @Test
    void delaysStayBetweenAMinuteAndADay() {
        assertEquals(10 * 60 * 20, NoteBook.delay(10));
        assertEquals(60 * 20, NoteBook.delay(0));
        assertEquals(NoteBook.MAX_DELAY, NoteBook.delay(100_000));
    }
}

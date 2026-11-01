package me.bytefox.livingcolonists.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class JobLogTest {
    @Test
    void theFirstSightingIsNoChange() {
        JobLog log = new JobLog();
        log.note("1:5", null, 100);
        assertNull(log.recent("1:5", 200));
    }

    @Test
    void aNewJobIsRecentForAWhile() {
        JobLog log = new JobLog();
        log.note("1:5", null, 100);
        log.note("1:5", "cook", 700);
        log.note("1:5", "cook", 1300);
        assertEquals(new JobLog.Change(null, "cook", 700), log.recent("1:5", 1300));
        assertNull(log.recent("1:5", 700 + JobLog.RECENT + 1));
    }

    @Test
    void losingAJobIsAChangeToo() {
        JobLog log = new JobLog();
        log.note("1:5", "cook", 100);
        log.note("1:5", null, 200);
        assertEquals(new JobLog.Change("cook", null, 200), log.recent("1:5", 300));
    }
}

package me.bytefox.livingcolonists.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/** Dead colonists' memories wait a few days in case the undertaker brings them back. */
class GravesTest {
    private static final long DAY = 24000;

    @Test
    void aDeadColonistIsKeptForAFewDaysThenLetGo() {
        Graves graves = new Graves();
        graves.died("1:5", "Darcy Abbott", 1000);
        assertTrue(graves.keeps("1:5", 1000 + 4 * DAY));
        assertFalse(graves.keeps("1:5", 1000 + Graves.KEEP_TICKS));
        assertFalse(graves.keeps("1:6", 1000), "a colonist nobody saw die isn't kept");
        assertEquals(List.of("1:5"), graves.expire(1000 + Graves.KEEP_TICKS));
        assertFalse(graves.keeps("1:5", 1000), "an expired grave is gone");
    }

    @Test
    void someoneWithTheDeadColonistsNameInTheSameColonyCameBack() {
        Graves graves = new Graves();
        graves.died("1:5", "Darcy Abbott", 1000);
        graves.died("2:5", "Hunter Casey", 1000);
        assertEquals(Optional.of("1:5"), graves.cameBack(1, 12, "Darcy Abbott"), "resurrected with a new id");
        assertEquals(Optional.empty(), graves.cameBack(1, 12, "Hunter Casey"), "another colony's dead");
        assertEquals(Optional.empty(), graves.cameBack(1, 5, "Darcy Abbott"), "the same id is no resurrection");
        assertEquals(Optional.empty(), graves.cameBack(1, 13, "Rowan Hale"));
    }

    @Test
    void gravesAreSavedWithTheWorld() {
        Graves graves = new Graves();
        graves.died("1:5", "Darcy Abbott", 1000);
        Graves loaded = Graves.load(graves.save());
        assertTrue(loaded.keeps("1:5", 2000));
        assertEquals(Optional.of("1:5"), loaded.cameBack(1, 9, "Darcy Abbott"));
        assertFalse(Graves.load(new CompoundTag()).keeps("1:5", 2000));
    }
}

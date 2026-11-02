package me.bytefox.livingcolonists;

import java.util.function.Predicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.personality.PersonalityService;

/**
 * What happens to a colonist's character and memories when they die: they wait a few game days instead of being
 * dropped at once, because MineColonies' undertaker may bring the colonist back from the grave. One brought back comes
 * with the same name and a new id; they get their character, memories, promises and standing with players back under
 * the new id, and remember that they died and came back. Server thread only.
 */
public final class Afterlife {
    /** What a colonist brought back remembers, as a fact of their own. */
    static final String CAME_BACK = "I died, and the colony's undertaker brought me back to life from the grave. "
            + "I remember everything from before.";

    private final PersonalityService personalities;
    private final MemoryService memory;
    private final BondsService bonds;
    private final me.bytefox.livingcolonists.friendship.Calendar calendar;

    public Afterlife(PersonalityService personalities, MemoryService memory, BondsService bonds,
                     me.bytefox.livingcolonists.friendship.Calendar calendar) {
        this.personalities = personalities;
        this.memory = memory;
        this.bonds = bonds;
        this.calendar = calendar;
    }

    /** A colonist died: their memories wait for them. */
    public void died(ICitizenData citizen, long now) {
        memory.graves().died(citizen.getColony().getID() + ":" + citizen.getId(), citizen.getName(), now);
        memory.gravesChanged();
    }

    /** Which colonists' data may be dropped: gone from their colony, and not waiting in a grave. */
    public Predicate<String> forgettable(long now) {
        return key -> Citizens.isGone(key) && !memory.graves().keeps(key, now);
    }

    /** Graves nobody came back from in time are let go; then {@link #forgettable} drops their data. */
    public void expire(long now) {
        if (!memory.graves().expire(now).isEmpty()) {
            memory.gravesChanged();
        }
    }

    /** Looks for colonists brought back from the dead in the colony, and gives them back what was theirs. */
    public void check(IColony colony) {
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            memory.graves().cameBack(colony.getID(), citizen.getId(), citizen.getName())
                    .filter(Citizens::isGone)
                    .ifPresent(old -> cameBack(old, citizen));
        }
    }

    private void cameBack(String old, ICitizenData citizen) {
        String now = colonyKey(citizen);
        personalities.move(old, now);
        memory.move(old, now);
        bonds.move(old, now);
        calendar.move(old, now);
        memory.graves().remove(old);
        memory.gravesChanged();
        memory.rememberDeed(citizen, CAME_BACK);
        Counters.add("colonists.resurrected");
        ModLog.action("{} came back from the dead: character and memories kept ({} -> {})", citizen.getName(), old, now);
    }

    private static String colonyKey(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }
}

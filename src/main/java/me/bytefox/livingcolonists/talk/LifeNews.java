package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.memory.MemoryService;

/**
 * Something in a colonist's life the others may talk about (#57): a new job, which the colonist also remembers,
 * and falling ill. Family and coworkers are the likeliest to bring it up. Server thread only.
 */
final class LifeNews {
    private final MemoryService memory;
    private final ColonyEvents events;
    private final Random random = new Random();

    LifeNews(MemoryService memory, ColonyEvents events) {
        this.memory = memory;
        this.events = events;
    }

    /**
     * A job the colony gave a colonist. Right after a building was finished it came with that building,
     * which was the news; otherwise the colonist remembers it. A job taken away is no news.
     */
    void jobChanged(GameEvents.JobChanged change) {
        ICitizenData citizen = change.citizen();
        IColony colony = citizen.getColony();
        if (change.to() == null || citizen.isChild() || colony.getWorld() == null
                || events.builtJustNow(colony.getID(), colony.getWorld().getGameTime())) {
            return;
        }
        memory.rememberSeen(colony, List.of(citizen.getId()), citizen.getName(), citizen.getName() + " became the colony's "
                + change.to() + (change.from() == null ? "" : ", no longer a " + change.from()) + ".", 3);
        // Now and then the people close to them remark on it (#57).
        if (random.nextDouble() < LIFE_NEWS_CHANCE) {
            events.announce(colony.getWorld().getServer(), colony, citizen.getName() + " has just become the colony's "
                    + change.to() + ".", closeTo(citizen));
        }
    }

    /** How often something in a colonist's life (a new job, an illness) becomes something the others remark on. */
    private static final double LIFE_NEWS_CHANCE = 0.5;

    /** A colonist fell ill: now and then their family or coworkers bring it up (#57). */
    void fellIll(GameEvents.FellIll ill) {
        ICitizenData citizen = ill.citizen();
        IColony colony = citizen.getColony();
        if (colony.getWorld() == null || random.nextDouble() >= LIFE_NEWS_CHANCE) {
            return;
        }
        events.announce(colony.getWorld().getServer(), colony, citizen.getName() + " has fallen ill with " + ill.illness()
                + ".", closeTo(citizen));
    }

    /** Family and coworkers of a colonist, the likeliest to remark on their life; never the colonist. */
    private static List<Integer> closeTo(ICitizenData citizen) {
        List<Integer> close = new ArrayList<>();
        var parents = citizen.getParents();
        for (ICitizenData other : citizen.getColony().getCitizenManager().getCitizens()) {
            if (other.getId() == citizen.getId()) {
                continue;
            }
            var theirParents = other.getParents();
            boolean family = parents != null && (other.getName().equals(parents.getA()) || other.getName().equals(parents.getB()))
                    || theirParents != null && (citizen.getName().equals(theirParents.getA())
                    || citizen.getName().equals(theirParents.getB()));
            boolean coworker = citizen.getWorkBuilding() != null && citizen.getWorkBuilding().equals(other.getWorkBuilding());
            if (family || coworker) {
                close.add(other.getId());
            }
        }
        java.util.Collections.shuffle(close);
        return close;
    }

}

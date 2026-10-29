package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.ICitizenEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenDiedEvent;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Colonists bring up what happens in the colony: a death, a birth, a child growing up, a newcomer,
 * a finished or upgraded building, a raid starting and ending. New entries of MineColonies' event
 * log and the raid state are checked every few seconds; an event opens a colony conversation (see
 * {@link ColonyThreads#startEvent}). Events from before the server started are not brought up.
 * Server thread only.
 *
 * <p>Idea from Colonist Errands by Lovkar ({@code DeathWatcher}: the colony mourns a death and
 * celebrates a birth), written anew.</p>
 */
public final class ColonyEvents {
    /** How often the colonies are checked, in server ticks (five seconds). */
    private static final int CHECK_INTERVAL = 100;

    private final ColonyThreads threads;
    private final Map<Integer, Set<IColonyEventDescription>> seen = new HashMap<>();
    private final Map<Integer, Boolean> raided = new HashMap<>();

    /** A newborn stays out of conversations, unless named, for this long (one Minecraft day). */
    private static final long NEWBORN_QUIET_TICKS = 24000;

    /**
     * What happened and who is likeliest to bring it up first.
     *
     * @param newborns colonists just born, who don't chat yet
     */
    private record Event(String what, List<Integer> speakers, List<Integer> newborns) {
        Event(String what, List<Integer> speakers) {
            this(what, speakers, List.of());
        }
    }

    public ColonyEvents(ColonyThreads threads) {
        this.threads = threads;
    }

    public void clear() {
        seen.clear();
        raided.clear();
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getWorld() == null) {
                continue;
            }
            try {
                List<Event> events = newEvents(colony);
                // One conversation at a time: the first event that gets one; the rest pass unremarked.
                for (Event event : events) {
                    if (!event.newborns().isEmpty()) {
                        threads.hush(colony, event.newborns(), server.getTickCount() + NEWBORN_QUIET_TICKS);
                    }
                }
                for (Event event : events) {
                    if (threads.startEvent(server, colony, event.what(), event.speakers())) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                LivingColonists.LOGGER.warn("Could not check the events of colony {}", colony.getName(), e);
            }
        }
    }

    private List<Event> newEvents(IColony colony) {
        List<Event> events = new ArrayList<>();
        boolean raid = colony.getRaiderManager().isRaided();
        Boolean wasRaided = raided.put(colony.getID(), raid);
        if (wasRaided != null && raid != wasRaided) {
            events.add(new Event(raid ? "Raiders are attacking the colony right now!"
                    : "The raid on the colony is over.", guards(colony)));
        }
        List<IColonyEventDescription> log = colony.getEventDescriptionManager().getEventDescriptions();
        Set<IColonyEventDescription> before = seen.get(colony.getID());
        Set<IColonyEventDescription> now = Collections.newSetFromMap(new IdentityHashMap<>());
        now.addAll(log);
        seen.put(colony.getID(), now);
        if (before == null) {
            return events;
        }
        for (IColonyEventDescription entry : log) {
            if (!before.contains(entry)) {
                Event event = describe(colony, entry);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    private static Event describe(IColony colony, IColonyEventDescription entry) {
        String type = entry.getEventTypeId().getPath();
        if (entry instanceof ICitizenEventDescription citizenEvent) {
            String name = citizenEvent.getCitizenName();
            if (type.contains("died")) {
                String cause = entry instanceof CitizenDiedEvent died && died.getDeathCause() != null
                        ? " (" + died.getDeathCause() + ")" : "";
                List<Integer> children = childrenOf(colony, name);
                String family = names(colony, children);
                return new Event(name + " has died" + cause + "." + (family.isEmpty() ? "" : " They leave behind " + family + ".")
                        + " The colony is shaken.", children);
            }
            if (type.contains("born")) {
                List<Integer> parents = parentsOf(colony, name);
                String family = names(colony, parents);
                return new Event("A baby, " + name + ", was just born in the colony" + (family.isEmpty() ? "" : " to " + family)
                        + ".", parents, named(colony, name));
            }
            if (type.contains("grown")) {
                return new Event(name + " has just grown up and is an adult now.", named(colony, name));
            }
            if (type.equals("citizen_spawn")) {
                return new Event(name + " has just arrived in the colony as a new colonist.", named(colony, name));
            }
            return null;
        }
        if (entry instanceof IBuildingEventDescription building && (type.contains("built") || type.contains("upgraded"))) {
            String name = Component.translatable(building.getBuildingName()).getString();
            return new Event(type.contains("built") ? "The " + name + " was just finished."
                    : "The " + name + " was just upgraded to level " + building.getLevel() + ".", List.of());
        }
        return null;
    }

    /** Relatives by name ("Anna and Oleg"), so they know it's their own family; empty if none. */
    private static String names(IColony colony, List<Integer> ids) {
        return ids.stream().map(id -> colony.getCitizenManager().getCivilian(id)).filter(Objects::nonNull)
                .map(ICitizenData::getName).collect(Collectors.joining(" and "));
    }

    private static List<Integer> named(IColony colony, String name) {
        return colony.getCitizenManager().getCitizens().stream().filter(c -> c.getName().equals(name))
                .map(ICitizenData::getId).toList();
    }

    private static List<Integer> parentsOf(IColony colony, String child) {
        List<Integer> parents = new ArrayList<>();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (citizen.getName().equals(child)) {
                Tuple<String, String> names = citizen.getParents();
                if (names != null) {
                    parents.addAll(named(colony, names.getA()));
                    parents.addAll(named(colony, names.getB()));
                }
            }
        }
        return parents;
    }

    private static List<Integer> childrenOf(IColony colony, String parent) {
        return colony.getCitizenManager().getCitizens().stream().filter(c -> {
            Tuple<String, String> names = c.getParents();
            return names != null && (parent.equals(names.getA()) || parent.equals(names.getB()));
        }).map(ICitizenData::getId).toList();
    }

    private static List<Integer> guards(IColony colony) {
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getJob() != null && c.getJob().isGuard())
                .map(ICitizenData::getId).toList();
    }
}

package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.ICitizenEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.colony.eventhooks.citizenEvents.CitizenDiedEvent;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.ModLog;
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
    /** Recent events of each colony as told later, for a player who was away (#34); not saved. */
    /** Kept with the world, so those away still hear the news after a restart; null while no world is loaded. */
    private InitiativeData data;
    private static final int HISTORY = 8;


    /** Game time of each colony's latest finished or upgraded building. */
    private final Map<Integer, Long> lastBuilt = new HashMap<>();
    /** A job given this soon after a building was finished came with it: the building was the news. */
    private static final long WITH_A_BUILDING = 3 * 60 * 20;

    /** A newborn stays out of conversations, unless named, for this long (one Minecraft day). */
    private static final long NEWBORN_QUIET_TICKS = 24000;

    /**
     * What happened and who is likeliest to bring it up first.
     *
     * @param newborns colonists just born, who don't chat yet
     */
    private record Event(String what, List<Integer> speakers, List<Integer> newborns, boolean building, boolean urgent) {
        Event(String what, List<Integer> speakers) {
            this(what, speakers, List.of(), false, false);
        }

        Event(String what, List<Integer> speakers, List<Integer> newborns) {
            this(what, speakers, newborns, false, false);
        }

        /** A death or a raid: it cuts into colonists' own chatter and waits longer for its turn. */
        Event urgently() {
            return new Event(what, speakers, newborns, building, true);
        }
    }

    /** An event waiting for the colony chat to be free, until it is stale (server ticks). */
    private record Queued(Event event, long until) {
    }

    /** Events not talked about yet, by colony, oldest first. */
    private final Map<Integer, Deque<Queued>> waiting = new HashMap<>();
    /** How long an event waits for its turn: five minutes, ten for a death or a raid. */
    private static final long WAIT_TICKS = 5 * 60 * 20;
    private static final long URGENT_WAIT_TICKS = 10 * 60 * 20;
    private static final int MAX_WAITING = 5;

    public ColonyEvents(ColonyThreads threads) {
        this.threads = threads;
        ModEvents.BUS.on(GameEvents.ColonyHappened.class, this::remember);
    }

    /** Keeps what happened for those away, and when a building was last finished. */
    private void remember(GameEvents.ColonyHappened event) {
        IColony colony = event.colony();
        if (colony.getWorld() == null) {
            return;
        }
        if (event.building()) {
            lastBuilt.put(colony.getID(), colony.getWorld().getGameTime());
        }
        String past = ArrivalPolicy.past(event.what());
        if (past != null && data != null) {
            Deque<InitiativeData.Told> log = data.history.computeIfAbsent(colony.getID(), id -> new ArrayDeque<>());
            log.addLast(new InitiativeData.Told(past, colony.getWorld().getGameTime()));
            if (log.size() > HISTORY) {
                log.removeFirst();
            }
            data.setDirty();
        }
    }

    public void clear() {
        seen.clear();
        raided.clear();
        lastBuilt.clear();
        sites.clear();
        waiting.clear();
    }

    /** A colony milestone (#39): told in the colony chat if players are around, and kept for those away. */
    void announce(MinecraftServer server, IColony colony, String what) {
        announce(server, colony, what, List.of());
    }

    /** The same, with the colonists likeliest to bring it up first. */
    void announce(MinecraftServer server, IColony colony, String what, List<Integer> speakers) {
        if (colony.getWorld() == null) {
            return;
        }
        ModEvents.BUS.post(new GameEvents.ColonyHappened(colony, what, false, false));
        queue(colony.getID(), new Event(what, speakers), server.getTickCount());
    }

    private void queue(int colonyId, Event event, long now) {
        Deque<Queued> queue = waiting.computeIfAbsent(colonyId, id -> new ArrayDeque<>());
        queue.addLast(new Queued(event, now + (event.urgent() ? URGENT_WAIT_TICKS : WAIT_TICKS)));
        while (queue.size() > MAX_WAITING) {
            queue.removeFirst();
        }
    }

    /** Whether a building was finished or upgraded in the colony a moment before {@code gameTime}. */
    boolean builtJustNow(int colonyId, long gameTime) {
        return gameTime - lastBuilt.getOrDefault(colonyId, Long.MIN_VALUE / 2) <= WITH_A_BUILDING;
    }

    /** What happened in the colony after {@code gameTime}, oldest first, in the past tense. */
    List<String> since(int colonyId, long gameTime) {
        return data == null ? List.of() : data.history.getOrDefault(colonyId, new ArrayDeque<>()).stream()
                .filter(past -> past.gameTime() > gameTime).map(InitiativeData.Told::what).toList();
    }

    void attach(InitiativeData data) {
        this.data = data;
    }

    void detach() {
        data = null;
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (IColony colony : Colonies.all()) {
            if (colony.getWorld() == null) {
                continue;
            }
            try {
                List<Event> events = newEvents(colony);
                for (Event event : events) {
                    ModEvents.BUS.post(new GameEvents.ColonyHappened(colony, event.what(), event.building(), event.urgent()));
                }
                long now = server.getTickCount();
                for (Event event : events) {
                    if (!event.newborns().isEmpty()) {
                        threads.hush(colony, event.newborns(), now + NEWBORN_QUIET_TICKS);
                    }
                    queue(colony.getID(), event, now);
                }
                // One conversation at a time: an event waits while the chat is busy, a death or a raid first.
                Deque<Queued> queue = waiting.get(colony.getID());
                if (queue != null) {
                    queue.removeIf(queued -> {
                        boolean stale = queued.until() < now;
                        if (stale) {
                            Counters.add("events.expired");
                        }
                        return stale;
                    });
                    Queued next = queue.stream().filter(queued -> queued.event().urgent()).findFirst()
                            .orElse(queue.peekFirst());
                    if (next != null && threads.startEvent(server, colony, next.event().what(), next.event().speakers(),
                            next.event().urgent())) {
                        queue.remove(next);
                    }
                }
            } catch (RuntimeException e) {
                ModLog.warn("Could not check the events of colony {}", colony.getName(), e);
            }
        }
    }

    /** Each colony's building positions as last seen, to notice a new hut being set out (#50). */
    private final Map<Integer, Set<net.minecraft.core.BlockPos>> sites = new HashMap<>();

    private List<Event> newEvents(IColony colony) {
        List<Event> events = new ArrayList<>();
        Set<net.minecraft.core.BlockPos> placed = new java.util.HashSet<>(colony.getServerBuildingManager().getBuildings().keySet());
        Set<net.minecraft.core.BlockPos> known = sites.put(colony.getID(), placed);
        if (known != null) {
            for (net.minecraft.core.BlockPos pos : placed) {
                var building = colony.getServerBuildingManager().getBuilding(pos);
                if (!known.contains(pos) && building != null && building.getBuildingLevel() == 0
                        && building.getBuildingType() != null) {
                    events.add(new Event("Someone has just set out the place for a new "
                            + Component.translatable(building.getBuildingType().getTranslationKey()).getString()
                            + " in the colony.", builders(colony)));
                }
            }
        }
        boolean raid = Colonies.isRaided(colony);
        Boolean wasRaided = raided.put(colony.getID(), raid);
        if (wasRaided != null && raid != wasRaided) {
            Event event = new Event(raid ? "Raiders are attacking the colony right now!"
                    : "The raid on the colony is over.", guards(colony));
            events.add(raid ? event.urgently() : event);
        }
        List<IColonyEventDescription> log = Colonies.eventLog(colony);
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
                        + " The colony is shaken.", children).urgently();
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
                    : "The " + name + " was just upgraded to level " + building.getLevel() + ".", List.of(), List.of(), true, false);
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

    private static List<Integer> builders(IColony colony) {
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getJob() != null && c.getJob().getJobRegistryEntry().getKey().getPath().equals("builder"))
                .map(ICitizenData::getId).toList();
    }

    private static List<Integer> guards(IColony colony) {
        return colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getJob() != null && c.getJob().isGuard())
                .map(ICitizenData::getId).toList();
    }
}

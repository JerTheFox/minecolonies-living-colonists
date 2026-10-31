package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.errands.Notes;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.perception.PerceptionService;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/**
 * Colonists now and then notice a colony member coming back or leaving (#34): a greeting with the
 * news since they were last here, a death, a trip to another world or how they look; a remark in the
 * colony chat when one leaves while others stay. Rare by design, see {@link ArrivalPolicy}. Server thread only.
 */
final class Arrivals {
    /** How often players are checked, in server ticks (five seconds). */
    private static final int CHECK_TICKS = 5 * 20;
    /** Colonists the greeting picks from: the warmest few. */
    private static final int WARMEST = 3;

    private record Pending(int colonyId, long at, ArrivalPolicy.Visit visit) {
    }

    private record Farewell(int colonyId, ResourceKey<net.minecraft.world.level.Level> dimension, long at, String name) {
    }

    private final TalkService talk;
    private final ColonyThreads threads;
    private final ColonyEvents events;
    private final Initiative initiative;
    private final BondsService bonds;
    private final PerceptionService perception;
    private final MemoryService memory;
    private final Random random = new Random();
    private InitiativeData data;

    /** The colony each player was last inside, -1 for none. */
    private final Map<UUID, Integer> inside = new HashMap<>();
    private final Map<UUID, Long> loginAt = new HashMap<>();
    private final Map<UUID, Long> logoutAt = new HashMap<>();
    /** Players who joined the server and haven't come into a colony since. */
    private final Set<UUID> fresh = new HashSet<>();
    /** Game time of each player's last death, and of their last trip to another world with its name. */
    private final Map<UUID, Long> diedAt = new HashMap<>();
    private final Map<UUID, Long> tripAt = new HashMap<>();
    private final Map<UUID, String> tripTo = new HashMap<>();
    /** Big deeds colonists heard each player did, with the game time, newest last. */
    /** A deed older than this (three game days) is no news any more. */
    private static final long DEED_NEWS = 3 * ArrivalPolicy.AWAY;
    private static final int MAX_DEEDS = 3;


    /** The notable item last seen in each player's hand, by item id. */
    private final Map<UUID, String> lastHeld = new HashMap<>();
    private final Map<UUID, Long> lastGreeting = new HashMap<>();
    private final Map<UUID, Long> lastFarewell = new HashMap<>();
    private final Map<Integer, Long> lastInColony = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Farewell> farewells = new HashMap<>();

    Arrivals(TalkService talk, ColonyThreads threads, ColonyEvents events, Initiative initiative, BondsService bonds,
             PerceptionService perception, MemoryService memory) {
        this.perception = perception;
        this.memory = memory;
        this.talk = talk;
        ModEvents.BUS.on(GameEvents.PlayerJoined.class, event -> loggedIn(event.player()));
        ModEvents.BUS.on(GameEvents.PlayerLeft.class, event -> loggedOut(event.player()));
        ModEvents.BUS.on(GameEvents.PlayerDied.class, event -> died(event.player()));
        ModEvents.BUS.on(GameEvents.PlayerChangedWorld.class, event -> changedWorld(event.player(), event.to()));
        ModEvents.BUS.on(GameEvents.AdvancementEarned.class, event -> earned(event.player(), event.id(), event.title()));
        this.threads = threads;
        this.events = events;
        this.initiative = initiative;
        this.bonds = bonds;
    }

    void attach(InitiativeData data) {
        this.data = data;
    }

    void detach() {
        data = null;
    }

    void clear() {
        inside.clear();
        loginAt.clear();
        logoutAt.clear();
        fresh.clear();
        diedAt.clear();
        tripAt.clear();
        tripTo.clear();
        lastHeld.clear();
        lastGreeting.clear();
        lastFarewell.clear();
        lastInColony.clear();
        pending.clear();
        farewells.clear();
    }

    /** A player gone this long (a real day) is forgotten here, but for the deeds kept with the world. */
    private static final long FORGET_TICKS = 24L * 60 * 60 * 20;

    /** Drops what is kept for players who left long ago. */
    void sweep(Set<UUID> online, long now) {
        Set<UUID> gone = new HashSet<>();
        logoutAt.forEach((id, at) -> {
            if (!online.contains(id) && now - at > FORGET_TICKS) {
                gone.add(id);
            }
        });
        for (Map<UUID, ?> map : List.of(loginAt, logoutAt, diedAt, tripAt, tripTo, lastHeld, lastGreeting, lastFarewell,
                pending, farewells, inside)) {
            map.keySet().removeAll(gone);
        }
        fresh.removeAll(gone);
    }

    void loggedIn(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = player.server.getTickCount();
        // Back within a few minutes: a reconnect, no goodbye and no welcome.
        boolean reconnect = farewells.remove(id) != null
                || now - logoutAt.getOrDefault(id, Long.MIN_VALUE / 2) < ArrivalPolicy.RECONNECT;
        if (!reconnect) {
            fresh.add(id);
            loginAt.put(id, now);
        }
    }

    void loggedOut(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = player.server.getTickCount();
        logoutAt.put(id, now);
        pending.remove(id);
        fresh.remove(id);
        Integer colonyId = inside.remove(id);
        if (colonyId != null && colonyId >= 0) {
            farewells.put(id, new Farewell(colonyId, player.level().dimension(), now + ArrivalPolicy.FAREWELL_DELAY,
                    TalkService.playerName(player)));
        }
    }

    void died(ServerPlayer player) {
        diedAt.put(player.getUUID(), player.server.overworld().getGameTime());
    }

    void changedWorld(ServerPlayer player, ResourceKey<net.minecraft.world.level.Level> to) {
        String world = ArrivalPolicy.world(to.location().toString());
        if (world != null) {
            tripTo.put(player.getUUID(), world);
            tripAt.put(player.getUUID(), player.server.overworld().getGameTime());
        }
    }

    /** A player earned an advancement: a big deed becomes hearsay for their return, a colony milestone news. */
    void earned(ServerPlayer player, String id, String title) {
        String news = Rumours.colonyNews(id);
        if (news != null) {
            IColony colony = Colonies.ownedBy(player);
            if (colony != null) {
                events.announce(player.server, colony, news);
            }
            return;
        }
        String deed = Rumours.deed(id, Rumours.extra(Config.EXTRA_ADVANCEMENTS.get()), title);
        if (deed != null) {
            if (data == null) {
                return;
            }
            java.util.Deque<InitiativeData.Told> list = data.deeds.computeIfAbsent(player.getUUID().toString(),
                    key -> new java.util.ArrayDeque<>());
            list.addLast(new InitiativeData.Told(deed, player.server.overworld().getGameTime()));
            while (list.size() > MAX_DEEDS) {
                list.removeFirst();
            }
            data.setDirty();
        }
    }

    void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (data == null || now % CHECK_TICKS != 0) {
            return;
        }
        Level level = Config.level(Module.INITIATIVE);
        long gameTime = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            IColony colony = Colonies.at(player);
            boolean member = colony != null && TalkService.isInside(player, colony)
                    && ColonyAccess.canTalk(TalkService.role(player, colony), Config.MEMBERS_ONLY.get());
            int here = member ? colony.getID() : -1;
            Integer before = inside.put(id, here);
            if (here < 0) {
                continue;
            }
            String key = colony.getID() + ":" + id;
            if ((before == null || before != here) && level != Level.OFF) {
                arrived(player, colony, key, gameTime, now);
            }
            data.seen.put(key, gameTime);
            data.setDirty();
        }
        pending.entrySet().removeIf(entry -> entry.getValue().at() <= now && greet(server, entry.getKey(), entry.getValue(), level, now));
        farewells.entrySet().removeIf(entry -> entry.getValue().at() <= now && sayGoodbye(server, entry.getKey(), entry.getValue(), level, now));
    }

    /** A colony member just came into the colony: whether to greet them, and why. */
    private void arrived(ServerPlayer player, IColony colony, String key, long gameTime, long now) {
        UUID id = player.getUUID();
        Long seen = data.seen.get(key);
        boolean session = fresh.remove(id) && now - loginAt.getOrDefault(id, Long.MIN_VALUE / 2) <= ArrivalPolicy.SESSION_WINDOW;
        if (seen == null) {
            // Never here before: nothing to come back to, but a word on how to talk to the colonists (#48).
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("livingcolonists.help.first_visit",
                    colony.getName()).withStyle(net.minecraft.ChatFormatting.GRAY));
            return;
        }
        ItemStack hand = player.getMainHandItem();
        String held = null;
        if (!hand.isEmpty() && (hand.getRarity() != Rarity.COMMON || hand.isEnchanted() || hand.has(DataComponents.CUSTOM_NAME))) {
            String item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
            if (!item.equals(lastHeld.put(id, item))) {
                held = ItemNames.of(hand);
            }
        }
        ArrivalPolicy.Visit visit = new ArrivalPolicy.Visit(session, gameTime - seen,
                events.since(colony.getID(), seen), diedAt.getOrDefault(id, Long.MIN_VALUE) > seen,
                tripAt.getOrDefault(id, Long.MIN_VALUE) > seen ? tripTo.get(id) : null, held,
                player.getHealth() / player.getMaxHealth() < 0.5,
                data.deeds.getOrDefault(id.toString(), new java.util.ArrayDeque<>()).stream()
                        .filter(deed -> deed.gameTime() > seen && gameTime - deed.gameTime() <= DEED_NEWS)
                        .map(InitiativeData.Told::what).toList());
        if (ArrivalPolicy.qualifies(visit)) {
            pending.put(id, new Pending(colony.getID(), now + ArrivalPolicy.DELAY_MIN
                    + random.nextInt((int) ArrivalPolicy.DELAY_SPREAD), visit));
        }
    }

    /** Greets a player now if the moment is right; true once it is settled either way. */
    private boolean greet(MinecraftServer server, UUID id, Pending visit, Level level, long now) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || level == Level.OFF || !talk.canWriteFirst()) {
            return true;
        }
        IColony colony = Colonies.at(player);
        if (colony == null || colony.getID() != visit.colonyId() || !TalkService.isInside(player, colony)
                || now - talk.lastMessage(id) < ArrivalPolicy.JUST_WROTE || threads.isBusy(colony.getID(), now)
                || talk.errands().hasPending(player, now) || Colonies.isRaided(colony)
                || now - lastGreeting.getOrDefault(id, Long.MIN_VALUE / 2) < ArrivalPolicy.playerGap(level)
                || now - lastInColony.getOrDefault(colony.getID(), Long.MIN_VALUE / 2) < ArrivalPolicy.colonyGap(level)) {
            return true;
        }
        boolean reason = ArrivalPolicy.hasReason(visit.visit());
        if (random.nextDouble() >= ArrivalPolicy.chance(level, reason)) {
            return true;
        }
        List<ICitizenData> able = able(colony);
        if (able.isEmpty()) {
            return true;
        }
        able.sort(Comparator.comparingInt((ICitizenData c) -> bonds.playerRapport(c, id)).reversed());
        ICitizenData speaker = able.get(random.nextInt(Math.min(WARMEST, able.size())));
        String name = TalkService.playerName(player);
        List<String> news = visit.visit().news();
        // News of the colony is everyone's to tell: it goes to the colony chat, where others may join in.
        boolean said = !news.isEmpty() && threads.startChatter(server, colony, speaker.getId(), "how " + name
                + " has just come back to the colony, and what happened while they were away: " + String.join(" ",
                news.stream().limit(3).toList()) + " Tell them what matters most of it, turning to them by name.", -1, Airtime.Kind.NEWS)
                || talk.writeFirst(player, speaker, ArrivalPolicy.purpose(visit.visit(), name), true, null, true,
                Airtime.Kind.GREETING)
                == Notes.Delivery.SENT;
        if (said) {
            lastGreeting.put(id, now);
            lastInColony.put(colony.getID(), now);
            initiative.postpone(id, now);
            ModLog.action("{} greets {} back{}", speaker.getName(), player.getGameProfile().getName(),
                    reason ? "" : ", for no reason");
        }
        return true;
    }

    /** A colonist remarks in the colony chat that a player left, if others are still there to hear it. */
    private boolean sayGoodbye(MinecraftServer server, UUID id, Farewell farewell, Level level, long now) {
        IColony colony = Colonies.byId(farewell.colonyId(), farewell.dimension());
        if (colony == null || level == Level.OFF || !talk.canWriteFirst() || threads.isBusy(colony.getID(), now)
                || now - lastFarewell.getOrDefault(id, Long.MIN_VALUE / 2) < ArrivalPolicy.playerGap(level)
                || now - lastInColony.getOrDefault(colony.getID(), Long.MIN_VALUE / 2) < ArrivalPolicy.colonyGap(level)
                || random.nextDouble() >= ArrivalPolicy.farewellChance(level)) {
            return true;
        }
        List<ICitizenData> able = able(colony);
        if (able.isEmpty()) {
            return true;
        }
        ICitizenData speaker = able.get(random.nextInt(able.size()));
        String about = "how " + farewell.name() + " has just left for now. Talk about them lightly, as people do when"
                + " someone has gone, in your own voice; don't make up where they went or why.";
        // startChatter needs players in the colony to hear it, so a goodbye to an empty colony stays unsaid.
        if (threads.startChatter(server, colony, speaker.getId(), about, -1, Airtime.Kind.GREETING)) {
            lastFarewell.put(id, now);
            lastInColony.put(colony.getID(), now);
            // Remembered only when something was left undone, by the one who remarked on it, as a minor fact.
            building(colony).ifPresent(project -> memory.rememberSeen(colony, List.of(speaker.getId()), farewell.name(),
                    farewell.name() + " left the colony for a while when the " + project + " was still being built.", 2));
            ModLog.action("{} remarks that {} left", speaker.getName(), farewell.name());
        }
        return true;
    }

    /** What the colony's builders are building, in plain words, if anything. */
    private java.util.Optional<String> building(IColony colony) {
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            String project = perception.facts(citizen).construction();
            if (project != null) {
                return java.util.Optional.of(project.split(" \\(in |, level")[0]);
            }
        }
        return java.util.Optional.empty();
    }

    /** Colonists awake and free to speak. */
    private List<ICitizenData> able(IColony colony) {
        List<ICitizenData> able = new ArrayList<>();
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (!Colonies.awake(citizen) || citizen.isChild() || threads.isHushed(colony.getID(), citizen.getId())
                    || talk.errands().isWalking(citizen)) {
                continue;
            }
            able.add(citizen);
        }
        return able;
    }
}

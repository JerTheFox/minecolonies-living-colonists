/*
 * The promise watcher is based on PromiseWatcher from Colonist Errands by Lovkar, licensed
 * GPL-3.0-or-later (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/PromiseWatcher.java). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.memory.Digests;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Promises players make to colonists and how players treat them. Both are written down from
 * private talks by the character model (see {@code MemoryService}); a watcher also notices on its
 * own when a promise came true: the colonist got a home, a job or got well, or the promised
 * building was finished. Server thread only.
 */
public final class BondsService {
    private static final long DAY = 24000;
    /** How often promises are checked, in server ticks (ten seconds). */
    private static final int CHECK_INTERVAL = 200;
    /** Open promises a colonist keeps track of at most; more are not written down. */
    private static final int MAX_OPEN = 6;
    /** Resolved promises kept per colonist for the record of who keeps their word. */
    private static final int MAX_RESOLVED = 20;
    /** Longest deadline taken from a promise, in days. */
    private static final int MAX_DUE_DAYS = 60;

    private BondsData data;
    /** The colony events already seen, per colony; events from before the server started count as seen. */
    private final Map<Integer, Set<IColonyEventDescription>> seenEvents = new HashMap<>();

    public void attach(BondsData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
        seenEvents.clear();
    }

    // ---- what the digest of a private talk brings

    /** The player's open promises to the colonist, in the order the digest numbers them. */
    public List<Promise> open(ICitizenData citizen, UUID player) {
        return promises(citizen).stream().filter(p -> p.isOpen() && p.maker().equals(player)).toList();
    }

    /**
     * Applies what the character model wrote down about a private talk: new promises, promises the
     * player says are done or given up, and how the player behaved.
     *
     * @param numbered the open promises as they were numbered for the model
     */
    public void apply(ICitizenData citizen, UUID player, String playerName, Digests.Digest digest, List<Promise> numbered) {
        if (data == null) {
            return;
        }
        long now = now(citizen.getColony());
        data.playerNames.put(player, playerName);
        for (Digests.Resolution resolution : digest.resolutions()) {
            if (resolution.number() < 1 || resolution.number() > numbered.size()) {
                continue;
            }
            Promise.Status status = switch (resolution.kind()) {
                case "kept" -> Promise.Status.KEPT;
                case "broken" -> Promise.Status.BROKEN;
                default -> Promise.Status.CANCELLED;
            };
            resolve(citizen, numbered.get(resolution.number() - 1).id(), status, now);
        }
        List<Promise> list = data.promises.computeIfAbsent(key(citizen), k -> new ArrayList<>());
        for (Digests.NewPromise made : digest.promises()) {
            if (list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
                LivingColonists.LOGGER.info("{} already holds {} open promises, a new one was not written down",
                        citizen.getName(), MAX_OPEN);
                break;
            }
            Promise.About about = Promise.About.of(made.about());
            long due = made.days() > 0 ? now + Math.min(made.days(), MAX_DUE_DAYS) * DAY : 0;
            list.add(new Promise(data.nextId++, player, playerName, made.text(), about, now, due, Promise.Status.OPEN, 0,
                    needUnmet(citizen, about)));
        }
        for (Digests.Conduct conduct : digest.conduct()) {
            note(citizen, player, conduct.kind() ? Bonds.KIND : Bonds.RUDE, conduct.note(), now);
        }
        data.setDirty();
    }

    // ---- prompt text

    /**
     * What the colonist knows of promises and of the player they answer: open and recent promises,
     * how this player treats them and what the colony says about the player.
     *
     * @param player the player being answered; null when colonists talk among themselves
     */
    public String block(ICitizenData citizen, ServerPlayer player, String playerName) {
        if (data == null) {
            return "";
        }
        long now = now(citizen.getColony());
        List<String> parts = new ArrayList<>();
        parts.add(Bonds.promisesBlock(promises(citizen), now));
        if (player != null) {
            UUID id = player.getUUID();
            parts.add(Bonds.relationBlock(playerName, data.relations.getOrDefault(key(citizen, id), Relation.EMPTY), now));
            parts.add(reputation(citizen.getColony(), id, playerName));
        }
        return parts.stream().filter(part -> !part.isEmpty()).collect(Collectors.joining("\n"));
    }

    private String reputation(IColony colony, UUID player, String playerName) {
        String prefix = colony.getID() + ":";
        String suffix = ":" + player;
        List<Integer> rapports = new ArrayList<>();
        data.relations.forEach((key, relation) -> {
            if (key.startsWith(prefix) && key.endsWith(suffix) && !relation.isEmpty()) {
                rapports.add(relation.rapport());
            }
        });
        int kept = 0;
        int broken = 0;
        for (Map.Entry<String, List<Promise>> entry : data.promises.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            for (Promise promise : entry.getValue()) {
                if (promise.maker().equals(player)) {
                    kept += promise.status() == Promise.Status.KEPT ? 1 : 0;
                    broken += promise.status() == Promise.Status.BROKEN ? 1 : 0;
                }
            }
        }
        return Bonds.reputation(playerName, rapports, kept, broken);
    }

    // ---- watcher

    /** Notices promises that came true or lapsed. Server tick. */
    public void tick(MinecraftServer server) {
        if (data == null || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getWorld() == null) {
                continue;
            }
            try {
                watchBuildings(server, colony);
                watchNeeds(server, colony);
            } catch (RuntimeException e) {
                LivingColonists.LOGGER.warn("Could not check promises in colony {}", colony.getName(), e);
            }
        }
    }

    /** New "built" and "upgraded" events of the colony are checked against open promises. */
    private void watchBuildings(MinecraftServer server, IColony colony) {
        List<IColonyEventDescription> events = colony.getEventDescriptionManager().getEventDescriptions();
        Set<IColonyEventDescription> seen = seenEvents.get(colony.getID());
        Set<IColonyEventDescription> now = Collections.newSetFromMap(new IdentityHashMap<>());
        now.addAll(events);
        seenEvents.put(colony.getID(), now);
        if (seen == null) {
            return;
        }
        for (IColonyEventDescription event : events) {
            if (seen.contains(event) || !(event instanceof IBuildingEventDescription building)) {
                continue;
            }
            String type = event.getEventTypeId().getPath();
            if (!type.contains("built") && !type.contains("upgraded")) {
                continue;
            }
            String name = Component.translatable(building.getBuildingName()).getString();
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                for (Promise promise : promises(citizen)) {
                    if (promise.isOpen() && Bonds.mentionsBuilding(promise.text(), name)) {
                        keptByItself(server, citizen, promise, "the " + name + " is finished");
                    }
                }
            }
        }
    }

    /** Home, job and health promises close when the need, once seen unmet, is met; others lapse. */
    private void watchNeeds(MinecraftServer server, IColony colony) {
        long now = now(colony);
        String prefix = colony.getID() + ":";
        for (Map.Entry<String, List<Promise>> entry : List.copyOf(data.promises.entrySet())) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            ICitizenData citizen = colony.getCitizenManager().getCivilian(Integer.parseInt(entry.getKey().split(":")[1]));
            if (citizen == null) {
                continue;
            }
            for (Promise promise : List.copyOf(entry.getValue())) {
                if (!promise.isOpen()) {
                    continue;
                }
                var lapse = Bonds.expiry(promise, now);
                if (lapse.isPresent()) {
                    lapsed(server, citizen, promise, lapse.get(), now);
                    continue;
                }
                if (promise.about() != Promise.About.HOUSING && promise.about() != Promise.About.WORK
                        && promise.about() != Promise.About.HEALTH) {
                    continue;
                }
                if (needUnmet(citizen, promise.about())) {
                    if (!promise.needSeen()) {
                        replace(citizen, promise.withNeedSeen());
                    }
                } else if (promise.needSeen()) {
                    keptByItself(server, citizen, promise, switch (promise.about()) {
                        case HOUSING -> "they have a home now";
                        case WORK -> "they have a job now";
                        default -> "they are well again";
                    });
                }
            }
        }
    }

    private void keptByItself(MinecraftServer server, ICitizenData citizen, Promise promise, String how) {
        resolve(citizen, promise.id(), Promise.Status.KEPT, now(citizen.getColony()));
        LivingColonists.LOGGER.info("{} noticed a promise came true ({}): {}", citizen.getName(), how, promise.text());
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null) {
            maker.sendSystemMessage(Component.translatable("livingcolonists.promise.kept", citizen.getName()));
        }
    }

    private void lapsed(MinecraftServer server, ICitizenData citizen, Promise promise, Promise.Status status, long now) {
        resolve(citizen, promise.id(), status, now);
        LivingColonists.LOGGER.info("A promise to {} lapsed as {}: {}", citizen.getName(), status, promise.text());
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null && status == Promise.Status.BROKEN) {
            maker.sendSystemMessage(Component.translatable("livingcolonists.promise.broken", citizen.getName()));
        }
    }

    /** Resolves an open promise by id, moving the maker's rapport; resolved ones stay as they are. */
    private void resolve(ICitizenData citizen, long id, Promise.Status status, long now) {
        for (Promise promise : promises(citizen)) {
            if (promise.id() != id || !promise.isOpen()) {
                continue;
            }
            replace(citizen, promise.resolve(status, now));
            if (status == Promise.Status.KEPT) {
                note(citizen, promise.maker(), Bonds.KEPT, "kept a promise to you: " + promise.text(), now);
            } else if (status == Promise.Status.BROKEN) {
                note(citizen, promise.maker(), Bonds.BROKEN, "broke a promise to you: " + promise.text(), now);
            }
            trimResolved(citizen);
            return;
        }
    }

    private void note(ICitizenData citizen, UUID player, int delta, String text, long now) {
        data.relations.merge(key(citizen, player), Relation.EMPTY.with(delta, text, now),
                (old, fresh) -> old.with(delta, text, now));
        data.setDirty();
    }

    private void replace(ICitizenData citizen, Promise promise) {
        List<Promise> list = data.promises.get(key(citizen));
        if (list == null) {
            return;
        }
        list.replaceAll(p -> p.id() == promise.id() ? promise : p);
        data.setDirty();
    }

    private void trimResolved(ICitizenData citizen) {
        List<Promise> list = data.promises.get(key(citizen));
        while (list != null && list.stream().filter(p -> !p.isOpen()).count() > MAX_RESOLVED) {
            list.remove(list.stream().filter(p -> !p.isOpen()).findFirst().orElseThrow());
        }
    }

    private static boolean needUnmet(ICitizenData citizen, Promise.About about) {
        return switch (about) {
            case HOUSING -> citizen.getHomeBuilding() == null;
            case WORK -> citizen.getWorkBuilding() == null || citizen.getJob() == null;
            case HEALTH -> citizen.getCitizenDiseaseHandler().isSick();
            default -> false;
        };
    }

    // ---- for /lcdebug

    public List<Promise> promises(ICitizenData citizen) {
        List<Promise> list = data == null ? null : data.promises.get(key(citizen));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Relations of the colonist with players, by player name. */
    public Map<String, Relation> relations(ICitizenData citizen) {
        Map<String, Relation> out = new HashMap<>();
        if (data == null) {
            return out;
        }
        String prefix = key(citizen) + ":";
        data.relations.forEach((key, relation) -> {
            if (key.startsWith(prefix)) {
                UUID player = UUID.fromString(key.substring(prefix.length()));
                out.put(data.playerNames.getOrDefault(player, player.toString()), relation);
            }
        });
        return out;
    }

    /** Forgets promises and relations of colonists whose key matches (gone colonists, a deleted colony). */
    public void forget(Predicate<String> key) {
        if (data == null) {
            return;
        }
        int before = data.promises.size() + data.relations.size();
        data.promises.keySet().removeIf(key);
        data.relations.keySet().removeIf(key);
        if (data.promises.size() + data.relations.size() != before) {
            data.setDirty();
        }
    }

    /** Forgets the colonist's promises and relations; returns how many were dropped. */
    public int forget(ICitizenData citizen) {
        if (data == null) {
            return 0;
        }
        List<Promise> promises = data.promises.remove(key(citizen));
        int before = data.relations.size();
        data.relations.keySet().removeIf(key -> key.startsWith(key(citizen) + ":"));
        data.setDirty();
        return (promises == null ? 0 : promises.size()) + before - data.relations.size();
    }

    private static String key(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }

    private static String key(ICitizenData citizen, UUID player) {
        return key(citizen) + ":" + player;
    }

    private static long now(IColony colony) {
        return colony.getWorld().getGameTime();
    }
}

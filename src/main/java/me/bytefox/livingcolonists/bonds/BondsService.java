/*
 * The promise watcher is based on PromiseWatcher from Colonist Errands by Lovkar, licensed
 * GPL-3.0-or-later (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/PromiseWatcher.java). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.ModBuildings;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.requestsystem.token.StandardToken;
import com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import me.bytefox.livingcolonists.Citizens;
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
    /** "I will work on it!" in a colonist's request dialog is a promise due in one Minecraft day. */
    private static final long REQUEST_PROMISE_TICKS = DAY;
    /** How often promised colony requests are checked, in server ticks (one second): finished ones vanish fast. */
    private static final int REQUEST_CHECK_INTERVAL = 20;
    private static final String BUTTON_OKAY = "com.minecolonies.coremod.gui.chat.okay";
    private static final String BUTTON_FULFILL = "com.minecolonies.coremod.gui.chat.fulfill";
    private static final String BUTTON_CANCEL = "com.minecolonies.coremod.gui.chat.cancel";
    /** {@link Promise#requestId} of a promise made in a plain dialog: this prefix and the dialog's translation key. */
    private static final String DIALOG = "dialog:";
    /** At most one promise reminder per player in this many server ticks (ten minutes). */
    private static final long REMINDER_GAP_TICKS = 12000;
    /** A nickname-like part of a colonist's name the character model may use instead of the full name. */
    private static final int MIN_FIRST_NAME = 3;

    private BondsData data;
    /** The colony events already seen, per colony; events from before the server started count as seen. */
    private final Map<Integer, Set<IColonyEventDescription>> seenEvents = new HashMap<>();
    /** Who reminds a player of an overdue promise; set by the talk layer. */
    private Reminder reminder;
    /** Server tick of each player's last reminder, so reminders don't pile up. */
    private final Map<UUID, Long> lastReminder = new HashMap<>();
    /** The last state seen of each promised colony request, by promise id, to tell delivered from cancelled. */
    private final Map<Long, String> requestStates = new HashMap<>();

    /** The name colonists know a player by; set by the talk layer. */
    private Function<ServerPlayer, String> naming = player -> player.getGameProfile().getName();

    /** An open promise and the colonist it was made to. */
    public record Owned(int citizenId, Promise promise) {
    }

    public void setPlayerNaming(Function<ServerPlayer, String> naming) {
        this.naming = naming;
    }

    /** Starts a colonist's private reminder; returns whether it went ahead. */
    public interface Reminder {
        boolean remind(ServerPlayer maker, ICitizenData citizen, Promise promise);
    }

    public void setReminder(Reminder reminder) {
        this.reminder = reminder;
    }

    public void attach(BondsData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
        seenEvents.clear();
        lastReminder.clear();
        requestStates.clear();
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
                    needUnmet(citizen, about), false, ""));
        }
        for (Digests.Conduct conduct : digest.conduct()) {
            note(citizen, player, conduct.kind() ? Bonds.KIND : Bonds.RUDE, conduct.note(), now);
        }
        data.setDirty();
    }

    /**
     * A button pressed in a colonist's request dialog: "I will work on it!" becomes a promise tied
     * to the request; "Cancel" on a promised request drops it, a breach once it is overdue.
     */
    public void onRequestResponse(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player,
                                  String playerName) {
        if (data == null || !(request.getIdentifier() instanceof UUID id)) {
            return;
        }
        long now = now(citizen.getColony());
        String requestId = id.toString();
        Promise existing = promises(citizen).stream().filter(p -> p.isOpen() && requestId.equals(p.requestId()))
                .findFirst().orElse(null);
        switch (button) {
            case BUTTON_OKAY -> {
                if (existing != null) {
                    return;
                }
                List<Promise> list = data.promises.computeIfAbsent(key(citizen), k -> new ArrayList<>());
                if (list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
                    return;
                }
                IRequest<?> asked = request(citizen.getColony(), request);
                String what = asked == null ? "what they asked for" : asked.getShortDisplayString().getString();
                String text = playerName + " promised to bring " + citizen.getName() + " " + what + ".";
                list.add(new Promise(data.nextId++, player.getUUID(), playerName, text, Promise.About.SUPPLY, now,
                        now + REQUEST_PROMISE_TICKS, Promise.Status.OPEN, 0, false, false, requestId));
                data.playerNames.put(player.getUUID(), playerName);
                data.setDirty();
                player.displayClientMessage(Component.translatable("livingcolonists.promise.made", citizen.getName()),
                        true);
                LivingColonists.LOGGER.info("{} wrote down a promise from the request dialog: {}", citizen.getName(), text);
            }
            case BUTTON_FULFILL -> {
                if (existing != null) {
                    resolve(citizen, existing.id(), Promise.Status.KEPT, now);
                }
            }
            case BUTTON_CANCEL -> {
                if (existing != null) {
                    resolve(citizen, existing.id(), Bonds.cancelledByMaker(existing, now), now);
                }
            }
            default -> {
            }
        }
    }

    /**
     * "I will work on it!" in a plain dialog the colonist opens about a need becomes a promise:
     * a hospital or a cure, an upgrade of their workplace, a warehouse, a guard tower near home or
     * work, better food. Other dialogs (a full chest, the rain) are no promise. Building and health
     * promises close by themselves when the building is finished or the colonist is cured.
     */
    public void onDialogResponse(ICitizenData citizen, String inquiry, String button, ServerPlayer player,
                                 String playerName) {
        if (data == null || !BUTTON_OKAY.equals(button)) {
            return;
        }
        String name = citizen.getName();
        String text;
        Promise.About about;
        int days;
        switch (inquiry) {
            case "com.minecolonies.coremod.ai.nohospital", "com.minecolonies.coremod.ai.waitingforcure" -> {
                text = playerName + " promised to get " + name + " cured.";
                about = Promise.About.HEALTH;
                days = 2;
            }
            case "com.minecolonies.core.restaurant.poorrestaurant", "com.minecolonies.coremod.request.toolow" -> {
                if (citizen.getWorkBuilding() == null) {
                    return;
                }
                text = playerName + " promised to upgrade " + name + "'s "
                        + Component.translatable(citizen.getWorkBuilding().getBuildingDisplayName()).getString() + ".";
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.job.deliveryman.nowarehouse" -> {
                text = playerName + " promised to build or upgrade the "
                        + Component.translatable(ModBuildings.wareHouse.get().getTranslationKey()).getString() + ".";
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.gui.chat.noguardnearhome", "com.minecolonies.coremod.gui.chat.noguardnearwork" -> {
                text = playerName + " promised to build a "
                        + Component.translatable(ModBuildings.guardTower.get().getTranslationKey()).getString() + " near "
                        + name + (inquiry.endsWith("home") ? "'s home." : "'s workplace.");
                about = Promise.About.BUILDING;
                days = 3;
            }
            case "com.minecolonies.coremod.entity.citizen.no.fooddiversity",
                 "com.minecolonies.coremod.entity.citizen.no.fooddiversity.urgent" -> {
                text = playerName + " promised " + name + " more varied food in the dining hall.";
                about = Promise.About.FOOD;
                days = 0;
            }
            case "com.minecolonies.coremod.entity.citizen.no.foodquality",
                 "com.minecolonies.coremod.entity.citizen.no.foodquality.urgent" -> {
                text = playerName + " promised " + name + " better food in the dining hall.";
                about = Promise.About.FOOD;
                days = 0;
            }
            default -> {
                return;
            }
        }
        String id = DIALOG + inquiry;
        List<Promise> list = data.promises.computeIfAbsent(key(citizen), k -> new ArrayList<>());
        if (list.stream().anyMatch(p -> p.isOpen() && id.equals(p.requestId()))
                || list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
            return;
        }
        long now = now(citizen.getColony());
        list.add(new Promise(data.nextId++, player.getUUID(), playerName, text, about, now, days > 0 ? now + days * DAY : 0,
                Promise.Status.OPEN, 0, needUnmet(citizen, about), false, id));
        data.playerNames.put(player.getUUID(), playerName);
        data.setDirty();
        player.displayClientMessage(Component.translatable("livingcolonists.promise.made", name), true);
        LivingColonists.LOGGER.info("{} wrote down a promise from a dialog: {}", name, text);
    }

    /** Every open promise players made to colonists of the colony, in the order the colony digest numbers them. */
    public List<Owned> openInColony(IColony colony) {
        List<Owned> open = new ArrayList<>();
        if (data == null) {
            return open;
        }
        String prefix = colony.getID() + ":";
        data.promises.forEach((key, list) -> {
            if (key.startsWith(prefix)) {
                int citizenId = Integer.parseInt(key.substring(prefix.length()));
                list.stream().filter(Promise::isOpen).forEach(p -> open.add(new Owned(citizenId, p)));
            }
        });
        open.sort(java.util.Comparator.comparingLong(owned -> owned.promise().id()));
        return open;
    }

    /** The open promises of the colony numbered for the character model, with whom each was made to. */
    public static String numberedInColony(IColony colony, List<Owned> open, long now) {
        if (open.isEmpty()) {
            return "(none)";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            Promise promise = open.get(i).promise();
            ICitizenData citizen = colony.getCitizenManager().getCivilian(open.get(i).citizenId());
            String to = citizen == null ? "?" : citizen.getName();
            String when = promise.due() == 0 ? "no deadline" : now > promise.due() ? "overdue" : "due later";
            lines.add((i + 1) + ". " + promise.text() + " (to " + to + ", " + when + ")");
        }
        return String.join("\n", lines);
    }

    /**
     * Applies what the character model wrote down about a colony conversation: promises players made
     * to a named colonist, and open promises the chat says were kept, broken or let off.
     */
    public void applyColony(MinecraftServer server, IColony colony, Digests.Digest digest, List<Owned> numbered) {
        if (data == null) {
            return;
        }
        long now = now(colony);
        for (Digests.Resolution resolution : digest.resolutions()) {
            if (resolution.number() < 1 || resolution.number() > numbered.size()) {
                continue;
            }
            Owned owned = numbered.get(resolution.number() - 1);
            ICitizenData citizen = colony.getCitizenManager().getCivilian(owned.citizenId());
            if (citizen != null) {
                resolve(citizen, owned.promise().id(), switch (resolution.kind()) {
                    case "kept" -> Promise.Status.KEPT;
                    case "broken" -> Promise.Status.BROKEN;
                    default -> Promise.Status.CANCELLED;
                }, now);
            }
        }
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        for (Digests.ColonyPromise made : digest.colonyPromises()) {
            ICitizenData citizen = byName(citizens, made.colonist());
            UUID player = playerByName(server, made.player());
            if (citizen == null || player == null) {
                continue;
            }
            List<Promise> list = data.promises.computeIfAbsent(key(citizen), k -> new ArrayList<>());
            if (list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
                continue;
            }
            Promise.About about = Promise.About.of(made.about());
            long due = made.days() > 0 ? now + Math.min(made.days(), MAX_DUE_DAYS) * DAY : 0;
            String playerName = data.playerNames.getOrDefault(player, made.player());
            list.add(new Promise(data.nextId++, player, playerName, Digests.cleanFact(made.text()), about, now, due,
                    Promise.Status.OPEN, 0, needUnmet(citizen, about), false, ""));
            LivingColonists.LOGGER.info("{} wrote down a promise from the colony chat: {}", citizen.getName(), made.text());
        }
        data.setDirty();
    }

    /** A player by the name colonists know them by (or their nickname): online first, then those seen before. */
    private UUID playerByName(MinecraftServer server, String name) {
        String wanted = name.strip();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (naming.apply(player).equalsIgnoreCase(wanted) || player.getGameProfile().getName().equalsIgnoreCase(wanted)) {
                data.playerNames.put(player.getUUID(), naming.apply(player));
                return player.getUUID();
            }
        }
        return data.playerNames.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(wanted))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    /** Promises tied to colony requests close when the request is delivered or cancelled. */
    private void watchRequests(MinecraftServer server) {
        for (Map.Entry<String, List<Promise>> entry : List.copyOf(data.promises.entrySet())) {
            String[] parts = entry.getKey().split(":");
            IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
            ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
            if (citizen == null) {
                continue;
            }
            for (Promise promise : List.copyOf(entry.getValue())) {
                if (!promise.isOpen() || promise.requestId().isEmpty() || promise.requestId().startsWith(DIALOG)) {
                    continue;
                }
                IRequest<?> request = request(colony, new StandardToken(UUID.fromString(promise.requestId())));
                String state = request == null ? null : request.getState().name();
                var outcome = Bonds.requestOutcome(state, requestStates.get(promise.id()));
                if (outcome.isEmpty()) {
                    requestStates.put(promise.id(), state);
                    continue;
                }
                requestStates.remove(promise.id());
                if (outcome.get() == Promise.Status.KEPT) {
                    keptByItself(server, citizen, promise, "the request was delivered");
                } else {
                    resolve(citizen, promise.id(), outcome.get(), now(colony));
                }
            }
        }
    }

    /** The colony request for a token, or null once the request manager forgot it. */
    private static IRequest<?> request(IColony colony, IToken<?> token) {
        try {
            return colony.getRequestManager().getRequestForToken(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        if (data == null) {
            return;
        }
        if (server.getTickCount() % REQUEST_CHECK_INTERVAL == 0) {
            watchRequests(server);
        }
        if (server.getTickCount() % CHECK_INTERVAL != 0) {
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
                if (promise.due() > 0 && now > promise.due() && !promise.reminded()) {
                    remindOnce(server, citizen, promise);
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

    /** The colonist reminds the maker of an overdue promise in private, once, if the maker is around. */
    private void remindOnce(MinecraftServer server, ICitizenData citizen, Promise promise) {
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        long tick = server.getTickCount();
        if (maker == null || reminder == null || tick - lastReminder.getOrDefault(maker.getUUID(), Long.MIN_VALUE / 2)
                < REMINDER_GAP_TICKS) {
            return;
        }
        if (reminder.remind(maker, citizen, promise)) {
            lastReminder.put(maker.getUUID(), tick);
            replace(citizen, promise.withReminded());
        }
    }

    private void keptByItself(MinecraftServer server, ICitizenData citizen, Promise promise, String how) {
        resolve(citizen, promise.id(), Promise.Status.KEPT, now(citizen.getColony()));
        LivingColonists.LOGGER.info("{} noticed a promise came true ({}): {}", citizen.getName(), how, promise.text());
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null) {
            maker.displayClientMessage(Component.translatable("livingcolonists.promise.kept", citizen.getName()), true);
        }
    }

    private void lapsed(MinecraftServer server, ICitizenData citizen, Promise promise, Promise.Status status, long now) {
        resolve(citizen, promise.id(), status, now);
        LivingColonists.LOGGER.info("A promise to {} lapsed as {}: {}", citizen.getName(), status, promise.text());
        ServerPlayer maker = server.getPlayerList().getPlayer(promise.maker());
        if (maker != null && status == Promise.Status.BROKEN) {
            maker.displayClientMessage(Component.translatable("livingcolonists.promise.broken", citizen.getName()), true);
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
        int before = data.promises.size() + data.relations.size() + data.colonists.size();
        data.promises.keySet().removeIf(key);
        data.relations.keySet().removeIf(key);
        // A pair is dropped when either colonist matches.
        data.colonists.keySet().removeIf(pair -> {
            String[] parts = pair.split(":");
            return key.test(pair) || key.test(parts[0] + ":" + parts[2]);
        });
        if (data.promises.size() + data.relations.size() + data.colonists.size() != before) {
            data.setDirty();
        }
    }

    // ---- colonists among themselves

    /** How colonists got along in a colony conversation, as the character model wrote it down. */
    public void noteColonists(IColony colony, List<ICitizenData> participants, List<Digests.ColonistBond> bonds) {
        if (data == null || bonds.isEmpty()) {
            return;
        }
        long now = now(colony);
        for (Digests.ColonistBond bond : bonds) {
            ICitizenData first = byName(participants, bond.first());
            ICitizenData second = byName(participants, bond.second());
            if (first == null || second == null || first.getId() == second.getId()) {
                continue;
            }
            int delta = bond.friendly() ? Bonds.KIND : Bonds.RUDE;
            String note = Digests.cleanFact(bond.note());
            data.colonists.merge(pair(colony.getID(), first.getId(), second.getId()), Relation.EMPTY.with(delta, note, now),
                    (old, fresh) -> old.with(delta, note, now));
        }
        data.setDirty();
    }

    /** -100 to 100: how two colonists of the colony get along, 0 without history. */
    public int rapport(IColony colony, int first, int second) {
        Relation relation = data == null ? null : data.colonists.get(pair(colony.getID(), first, second));
        return relation == null ? 0 : relation.rapport();
    }

    /** How the colonist gets along with the others named, for the colony chat prompt; empty if with nobody special. */
    public String colonistBlock(ICitizenData citizen, Collection<ICitizenData> others) {
        if (data == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ICitizenData other : others) {
            if (other.getId() == citizen.getId()) {
                continue;
            }
            Relation relation = data.colonists.get(pair(citizen.getColony().getID(), citizen.getId(), other.getId()));
            String line = relation == null ? "" : Bonds.colonistLine(other.getName(), relation);
            if (!line.isEmpty()) {
                lines.add("- " + line);
            }
        }
        return lines.isEmpty() ? "" : "How you get along with people in this chat:\n" + String.join("\n", lines);
    }

    /** How the colonist gets along with each colonist they have a history with, by name, for {@code /lcdebug}. */
    public Map<String, Relation> colonistRelations(ICitizenData citizen) {
        Map<String, Relation> out = new java.util.TreeMap<>();
        if (data == null) {
            return out;
        }
        IColony colony = citizen.getColony();
        String prefix = colony.getID() + ":";
        data.colonists.forEach((key, relation) -> {
            String[] parts = key.split(":");
            if (!key.startsWith(prefix)) {
                return;
            }
            int first = Integer.parseInt(parts[1]);
            int second = Integer.parseInt(parts[2]);
            int other = first == citizen.getId() ? second : second == citizen.getId() ? first : -1;
            ICitizenData data = other < 0 ? null : colony.getCitizenManager().getCivilian(other);
            if (data != null) {
                out.put(data.getName(), relation);
            }
        });
        return out;
    }

    /** Every pair of colonists of the colony with a history, as "A + B", for {@code /lcdebug}. */
    public Map<String, Relation> colonyRelations(IColony colony) {
        Map<String, Relation> out = new java.util.TreeMap<>();
        if (data == null) {
            return out;
        }
        String prefix = colony.getID() + ":";
        data.colonists.forEach((key, relation) -> {
            String[] parts = key.split(":");
            if (key.startsWith(prefix)) {
                ICitizenData first = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
                ICitizenData second = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[2]));
                if (first != null && second != null) {
                    out.put(first.getName() + " + " + second.getName(), relation);
                }
            }
        });
        return out;
    }

    /** By full name, then by first name, ignoring case. */
    private static ICitizenData byName(List<ICitizenData> citizens, String name) {
        String wanted = name.strip();
        for (ICitizenData citizen : citizens) {
            if (citizen.getName().equalsIgnoreCase(wanted)) {
                return citizen;
            }
        }
        for (ICitizenData citizen : citizens) {
            String first = citizen.getName().split(" ")[0];
            if (first.length() >= MIN_FIRST_NAME && first.equalsIgnoreCase(wanted)) {
                return citizen;
            }
        }
        return null;
    }

    private static String pair(int colonyId, int first, int second) {
        return colonyId + ":" + Math.min(first, second) + ":" + Math.max(first, second);
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

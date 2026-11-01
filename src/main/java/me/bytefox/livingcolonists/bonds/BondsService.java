/*
 * The promise watcher (now PromiseWatch) is based on PromiseWatcher from Colonist Errands by Lovkar, licensed
 * GPL-3.0-or-later (https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/PromiseWatcher.java). Rewritten for Living Colonists.
 */
package me.bytefox.livingcolonists.bonds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.memory.Digests;
import me.bytefox.livingcolonists.memory.PlayerMarks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;

/**
 * Promises players make to colonists and how players treat them. Both are written down from
 * private talks by the character model (see {@code MemoryService}); a watcher also notices on its
 * own when a promise came true: the colonist got a home, a job or got well, or the promised
 * building was finished. Server thread only.
 */
public final class BondsService {
    static final long DAY = 24000;
    /** Open promises a colonist keeps track of at most; more are not written down. */
    static final int MAX_OPEN = 6;
    /** Resolved promises kept per colonist for the record of who keeps their word. */
    private static final int MAX_RESOLVED = 20;
    /** Longest deadline taken from a promise, in days. */
    private static final int MAX_DUE_DAYS = 60;

    private BondsData data;
    private final ColonistBonds colonists = new ColonistBonds(() -> data);
    private final PromiseDialogs dialogs = new PromiseDialogs(this);
    private final PromiseWatch watch = new PromiseWatch(this);

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
        watch.setReminder(reminder);
    }

    /** How colonists get along among themselves. */
    public ColonistBonds colonists() {
        return colonists;
    }

    PromiseDialogs dialogs() {
        return dialogs;
    }

    PromiseWatch watch() {
        return watch;
    }

    BondsData data() {
        return data;
    }

    public void attach(BondsData data) {
        this.data = data;
    }

    public void detach() {
        data = null;
        watch.clear();
        dialogs.clear();
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
    public void apply(ICitizenData citizen, UUID player, String playerName, Digests.Digest digest, List<Promise> numbered,
                      List<Deadlines.Said> playerLines) {
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
            if (owesInColony(citizen.getColony(), player).isPresent()) {
                ModLog.action("{} didn't take a new promise: {} still owes an earlier one", citizen.getName(),
                        playerName);
                break;
            }
            if (list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
                ModLog.action("{} already holds {} open promises, a new one was not written down",
                        citizen.getName(), MAX_OPEN);
                break;
            }
            Promise.About about = Promise.About.of(made.about());
            long due = due(citizen.getColony(), playerLines, made.days(), now);
            list.add(new Promise(data.nextId++, player, playerName, made.text(), made.worth(), about, now, due, Promise.Status.OPEN, 0,
                    needUnmet(citizen, about), false, ""));
            keptByGift(citizen, player, now);
        }
        for (Digests.Conduct conduct : digest.conduct()) {
            note(citizen, player, conduct.kind() ? Bonds.KIND : Bonds.RUDE, conduct.note(), now);
        }
        data.setDirty();
    }

    /**
     * A new promise's deadline: from the player's own words when they name a time ("in ten minutes",
     * "к вечеру", "tomorrow"), else the model's guess in days; 0 for none (#11).
     */
    private static long due(IColony colony, List<Deadlines.Said> playerLines, int modelDays, long now) {
        long dayTime = colony.getWorld() == null ? now : colony.getWorld().getDayTime();
        java.util.OptionalLong said = Deadlines.due(playerLines, now, dayTime);
        if (said.isPresent()) {
            return Math.min(said.getAsLong(), now + MAX_DUE_DAYS * DAY);
        }
        return modelDays > 0 ? now + Math.min(modelDays, MAX_DUE_DAYS) * DAY : 0;
    }

    /** What {@code count} of an item is worth, 1 (a trifle) to 5 (something precious), by code. */
    public static int worthOf(ItemStack stack, int count) {
        Worth.Kind kind = stack.is(Tags.Items.STORAGE_BLOCKS) ? Worth.Kind.STORAGE_BLOCK
                : stack.is(Tags.Items.GEMS) ? Worth.Kind.GEM
                : stack.is(Tags.Items.INGOTS) ? Worth.Kind.INGOT
                : stack.is(Tags.Items.ORES) ? Worth.Kind.ORE : Worth.Kind.PLAIN;
        return Worth.of(new Worth.Traits(stack.getRarity().ordinal(), kind, stack.getMaxDamage(), stack.isEnchanted(),
                stack.getMaxStackSize()), count);
    }

    /** A button pressed in a colonist's request dialog (see {@link PromiseDialogs}). */
    public void onRequestResponse(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player,
                                  String playerName) {
        dialogs.onRequestResponse(citizen, request, button, player, playerName);
    }

    /** "I will work on it!" in a plain need dialog (see {@link PromiseDialogs}). */
    public void onDialogResponse(ICitizenData citizen, String inquiry, String button, ServerPlayer player,
                                 String playerName) {
        dialogs.onDialogResponse(citizen, inquiry, button, player, playerName);
    }

    /**
     * The open promise this player made in talk (not in a colony dialog) to someone in the colony, if
     * any. Until it is kept or dropped, colonists don't take a new one from them: promising a trifle
     * every minute would otherwise buy any friendship.
     */
    public java.util.Optional<Owned> owesInColony(IColony colony, UUID player) {
        return openInColony(colony).stream()
                .filter(owned -> owned.promise().maker().equals(player) && owned.promise().requestId().isEmpty())
                .findFirst();
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
    public void applyColony(MinecraftServer server, IColony colony, Digests.Digest digest, List<Owned> numbered,
                            Map<String, List<Deadlines.Said>> linesBySpeaker) {
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
            ICitizenData citizen = ColonistBonds.byName(citizens, made.colonist());
            UUID player = playerByName(server, made.player());
            if (citizen == null || player == null) {
                continue;
            }
            if (owesInColony(colony, player).isPresent()) {
                ModLog.action("{} didn't take a new promise: {} still owes an earlier one", citizen.getName(),
                        made.player());
                continue;
            }
            List<Promise> list = data.promises.computeIfAbsent(key(citizen), k -> new ArrayList<>());
            if (list.stream().filter(Promise::isOpen).count() >= MAX_OPEN) {
                continue;
            }
            Promise.About about = Promise.About.of(made.about());
            long due = due(colony, linesBySpeaker.getOrDefault(made.player(), List.of()), made.days(), now);
            String playerName = data.playerNames.getOrDefault(player, made.player());
            list.add(new Promise(data.nextId++, player, playerName, Digests.cleanFact(made.text()), made.worth(), about, now, due,
                    Promise.Status.OPEN, 0, needUnmet(citizen, about), false, ""));
            ModLog.action("{} wrote down a promise from the colony chat: {}", citizen.getName(),
                    PlayerMarks.resolve(made.text()));
            keptByGift(citizen, player, now);
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
            owesInColony(citizen.getColony(), id).ifPresent(owned -> {
                ICitizenData to = citizen.getColony().getCitizenManager().getCivilian(owned.citizenId());
                parts.add(Bonds.owesLine(playerName, owned.promise().text(),
                        to == null || to.getId() == citizen.getId() ? null : to.getName()));
            });
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

    /** Notices promises that came true or lapsed (see {@link PromiseWatch}). Server tick. */
    public void tick(MinecraftServer server) {
        watch.tick(server);
    }

    /** Resolves an open promise by id, moving the maker's rapport; resolved ones stay as they are. */
    void resolve(ICitizenData citizen, long id, Promise.Status status, long now) {
        for (Promise promise : promises(citizen)) {
            if (promise.id() != id || !promise.isOpen()) {
                continue;
            }
            replace(citizen, promise.resolve(status, now));
            if (status == Promise.Status.KEPT) {
                credit(citizen, promise, now);
            } else if (status == Promise.Status.BROKEN) {
                note(citizen, promise.maker(), Bonds.brokenDelta(promise.worth()), "broke a promise to you: " + promise.text(),
                        now);
            }
            trimResolved(citizen);
            return;
        }
    }

    /** A kept promise warms the colonist, by its worth and at most once a day per player (see {@link Bonds#mayCredit}). */
    private void credit(ICitizenData citizen, Promise promise, long now) {
        int delta = Bonds.keptDelta(promise.worth());
        String key = key(citizen, promise.maker());
        if (delta <= 0 || !Bonds.mayCredit(data.credited.getOrDefault(key, Long.MIN_VALUE), now)) {
            return;
        }
        data.credited.put(key, now);
        note(citizen, promise.maker(), delta, "kept a promise to you: " + promise.text(), now);
    }

    private void note(ICitizenData citizen, UUID player, int delta, String text, long now) {
        data.relations.merge(key(citizen, player), Relation.EMPTY.with(delta, text, now),
                (old, fresh) -> old.with(delta, text, now));
        data.setDirty();
    }

    void replace(ICitizenData citizen, Promise promise) {
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

    static boolean needUnmet(ICitizenData citizen, Promise.About about) {
        return switch (about) {
            case HOUSING -> citizen.getHomeBuilding() == null;
            case WORK -> citizen.getWorkBuilding() == null || citizen.getJob() == null;
            case HEALTH -> citizen.getCitizenDiseaseHandler().isSick();
            default -> false;
        };
    }

    // ---- for /lcdebug

    /** Gifts of the last little while per colonist and player, so a promise written down after its gift is kept. */
    private final Map<String, java.util.Deque<Given>> gifts = new java.util.HashMap<>();
    private static final int MAX_GIFTS_KEPT = 5;
    /** A gift keeps a promise written down this long after it, in game ticks (half a game day). */
    private static final long GIFT_COUNTS = 12000;

    private record Given(List<String> items, long at) {
    }

    /** Open promises of the player to the colonist that a recent gift fulfils are kept. */
    private void keptByGift(ICitizenData citizen, UUID player, long now) {
        java.util.Deque<Given> given = gifts.get(key(citizen, player));
        if (given == null) {
            return;
        }
        for (Promise promise : List.copyOf(promises(citizen))) {
            if (promise.isOpen() && player.equals(promise.maker()) && given.stream()
                    .anyMatch(g -> now - g.at() <= GIFT_COUNTS && Gifts.fulfils(promise.text(), g.items()))) {
                resolve(citizen, promise.id(), Promise.Status.KEPT, now);
                ModLog.action("{} counts a gift as keeping a promise: {}", citizen.getName(), PlayerMarks.resolve(promise.text()));
            }
        }
    }

    /** When each colonist last warmed to each player for a gift, game time; not saved. */
    private final Map<String, Long> lastGift = new java.util.HashMap<>();

    /**
     * A player gave the colonist something (#11): a kind act worth what the gift is worth, at most once a
     * game day per colonist and player, so gifts of dirt can't be farmed. Whether it warmed them.
     */
    public boolean gift(ICitizenData citizen, UUID player, String playerName, String text, int worth, List<String> items) {
        if (data == null) {
            return false;
        }
        long now = now(citizen.getColony());
        String key = key(citizen, player);
        gifts.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).addLast(new Given(items, now));
        while (gifts.get(key).size() > MAX_GIFTS_KEPT) {
            gifts.get(key).removeFirst();
        }
        keptByGift(citizen, player, now);
        if (now - lastGift.getOrDefault(key, Long.MIN_VALUE / 2) < Gifts.WARMS_EVERY) {
            return false;
        }
        lastGift.put(key, now);
        data.playerNames.put(player, playerName);
        note(citizen, player, Gifts.warmth(worth), text, now);
        return true;
    }

    public List<Promise> promises(ICitizenData citizen) {
        List<Promise> list = data == null ? null : data.promises.get(key(citizen));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Relations of the colonist with players, by player name. */
    /** How the colonist feels about a player, -100 to 100; 0 when they have no history. */
    public int playerRapport(ICitizenData citizen, UUID player) {
        return data == null ? 0 : data.relations.getOrDefault(key(citizen, player), Relation.EMPTY).rapport();
    }

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
        data.credited.keySet().removeIf(key);
        // A pair is dropped when either colonist matches.
        data.colonists.keySet().removeIf(pair -> {
            String[] parts = pair.split(":");
            return key.test(pair) || key.test(parts[0] + ":" + parts[2]);
        });
        if (data.promises.size() + data.relations.size() + data.colonists.size() != before) {
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

    static String key(ICitizenData citizen) {
        return citizen.getColony().getID() + ":" + citizen.getId();
    }

    private static String key(ICitizenData citizen, UUID player) {
        return key(citizen) + ":" + player;
    }

    static long now(IColony colony) {
        return colony.getWorld().getGameTime();
    }
}

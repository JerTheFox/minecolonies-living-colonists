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
import me.bytefox.livingcolonists.friendship.Taste;
import me.bytefox.livingcolonists.Citizens;
import me.bytefox.livingcolonists.memory.Digests;
import me.bytefox.livingcolonists.memory.PlayerMarks;
import net.minecraft.network.chat.Component;
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
            Promise promise = new Promise(data.nextId++, player, playerName, made.text(), made.worth(), about, now, due,
                    Promise.Status.OPEN, 0, needUnmet(citizen, about), false, "").asked();
            list.add(promise);
            ask(citizen, promise);
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
                .filter(owned -> owned.promise().maker().equals(player) && owned.promise().requestId().isEmpty()
                        && owned.promise().binding())
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
            Promise promise = new Promise(data.nextId++, player, playerName, Digests.cleanFact(made.text()), made.worth(), about,
                    now, due, Promise.Status.OPEN, 0, needUnmet(citizen, about), false, "").asked();
            list.add(promise);
            ask(citizen, promise);
            ModLog.action("{} wrote down a promise from the colony chat: {}", citizen.getName(),
                    PlayerMarks.resolve(made.text()));
            keptByGift(citizen, player, now);
        }
        data.setDirty();
    }

    /**
     * Asks the maker, if online, whether they meant a promise the character model found in talk: "[Yes, I promise]"
     * makes it binding, "[No]" drops it. Unanswered, it turns vague (see {@link Bonds#confirmation}).
     */
    private void ask(ICitizenData citizen, Promise promise) {
        if (citizen.getColony().getWorld() == null || citizen.getColony().getWorld().getServer() == null) {
            return;
        }
        ServerPlayer maker = citizen.getColony().getWorld().getServer().getPlayerList().getPlayer(promise.maker());
        if (maker == null) {
            return;
        }
        String name = citizen.getName();
        net.minecraft.network.chat.MutableComponent yes = Component.translatable("livingcolonists.promise.ask.yes")
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/lc promise yes " + promise.id()))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("livingcolonists.promise.ask.yes.hint", name))));
        net.minecraft.network.chat.MutableComponent no = Component.translatable("livingcolonists.promise.ask.no")
                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.RED)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/lc promise no " + promise.id()))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("livingcolonists.promise.ask.no.hint", name))));
        maker.sendSystemMessage(Component.translatable("livingcolonists.promise.ask", name, PlayerMarks.resolve(promise.text()))
                .withStyle(net.minecraft.ChatFormatting.GRAY).append(" ").append(yes).append(" ").append(no));
    }

    /**
     * The player's answer about one of their promises, by id: yes makes it binding; no drops a promise never confirmed
     * quietly, and gives up a confirmed one (a breach if it is overdue). Returns 1 if there was such a promise.
     */
    public int answer(ServerPlayer player, long id, boolean meant) {
        if (data == null) {
            return 0;
        }
        for (Map.Entry<String, List<Promise>> entry : data.promises.entrySet()) {
            for (Promise promise : entry.getValue()) {
                if (promise.id() != id || !promise.isOpen() || !promise.maker().equals(player.getUUID())) {
                    continue;
                }
                String[] parts = entry.getKey().split(":");
                IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
                ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
                if (citizen == null) {
                    return 0;
                }
                long now = now(colony);
                if (meant) {
                    replace(citizen, promise.withConfirm(Promise.Confirm.CONFIRMED));
                    player.sendSystemMessage(Component.translatable("livingcolonists.promise.confirmed", citizen.getName()));
                    handOverBook(citizen, player);
                } else if (promise.binding()) {
                    resolve(citizen, id, Bonds.cancelledByMaker(promise, now), now);
                    player.sendSystemMessage(Component.translatable("livingcolonists.promise.given_up", citizen.getName()));
                } else {
                    resolve(citizen, id, Promise.Status.CANCELLED, now);
                    player.sendSystemMessage(Component.translatable("livingcolonists.promise.dropped", citizen.getName()));
                }
                ModLog.action("{} answered about a promise to {}: {} ({})", player.getGameProfile().getName(), citizen.getName(),
                        meant ? "meant it" : "didn't", PlayerMarks.resolve(promise.text()));
                return 1;
            }
        }
        player.sendSystemMessage(Component.translatable("livingcolonists.promise.unknown"));
        return 0;
    }

    /** The pages of the player's promise notebook: their open promises in every colony, overdue and soonest first. */
    public List<Component> notebook(ServerPlayer player) {
        List<PromiseBook.Entry> entries = new ArrayList<>();
        if (data != null) {
            for (Map.Entry<String, List<Promise>> entry : data.promises.entrySet()) {
                String[] parts = entry.getKey().split(":");
                IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
                ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
                if (citizen == null) {
                    continue;
                }
                long now = now(colony);
                long dayTime = colony.getWorld() == null ? now : colony.getWorld().getDayTime();
                for (Promise promise : entry.getValue()) {
                    if (promise.isOpen() && promise.maker().equals(player.getUUID())) {
                        entries.add(new PromiseBook.Entry(citizen.getName(), colony.getName(), promise, now, dayTime));
                    }
                }
            }
        }
        entries.sort(java.util.Comparator.comparingLong(e -> e.promise().due() <= 0 ? Long.MAX_VALUE : e.promise().due()));
        return PromiseBook.pages(entries);
    }

    /** How a colonist feels about a player, for the notebook (#66): rapport and the happiness from it (1 is none). */
    public record PlayerRelation(String colonist, String colony, int rapport, double mood, Map<Taste, List<String>> tastes,
                                 ICitizenData citizen) {
    }

    /** Every colonist with a relation to the player, warmest first. */
    public List<PlayerRelation> relationsOf(ServerPlayer player) {
        List<PlayerRelation> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        String suffix = ":" + player.getUUID();
        for (Map.Entry<String, Relation> entry : data.relations.entrySet()) {
            if (!entry.getKey().endsWith(suffix) || entry.getValue().isEmpty()) {
                continue;
            }
            String[] parts = entry.getKey().split(":");
            IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
            ICitizenData citizen = colony == null ? null : colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
            if (citizen == null) {
                continue;
            }
            Moods.Lasting mood = data.moods.get(parts[0] + ":" + parts[1]);
            double factor = mood == null || mood.until() <= now(colony) ? 1.0 : mood.factor();
            out.add(new PlayerRelation(citizen.getName(), colony.getName(), entry.getValue().rapport(), factor,
                    knownTastes(citizen, player.getUUID()), citizen));
        }
        out.sort(java.util.Comparator.comparingInt(PlayerRelation::rapport).reversed());
        return out;
    }

    /**
     * The first promise a player stands by brings them a notebook from the colonist ("Write it down, or you'll
     * forget"), once per player; a lost one is crafted again.
     */
    void handOverBook(ICitizenData citizen, ServerPlayer player) {
        if (data == null || !data.bookGiven.add(player.getUUID())) {
            return;
        }
        data.setDirty();
        ItemStack book = new ItemStack(me.bytefox.livingcolonists.items.ModItems.PROMISE_BOOK.get());
        if (!player.addItem(book)) {
            player.drop(book, false);
        }
        player.sendSystemMessage(Component.translatable("livingcolonists.book.given", citizen.getName()));
        ModLog.action("{} gave {} a promise notebook", citizen.getName(), player.getGameProfile().getName());
    }

    /** The player's open promises in the colony, each with a button to give it up. Returns how many. */
    public int list(ServerPlayer player, IColony colony) {
        List<Owned> mine = openInColony(colony).stream().filter(owned -> owned.promise().maker().equals(player.getUUID())).toList();
        if (mine.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.promise.none", colony.getName()));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("livingcolonists.promise.list", colony.getName()));
        for (Owned owned : mine) {
            ICitizenData citizen = colony.getCitizenManager().getCivilian(owned.citizenId());
            Promise promise = owned.promise();
            net.minecraft.network.chat.MutableComponent line = Component.literal("- " + (citizen == null ? "?" : citizen.getName())
                    + ": " + PlayerMarks.resolve(promise.text()) + " ");
            if (!promise.binding()) {
                line.append(Component.translatable("livingcolonists.promise.list.vague")).append(" ");
                line.append(Component.translatable("livingcolonists.promise.ask.yes").withStyle(style -> style
                        .withColor(net.minecraft.ChatFormatting.GREEN)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/lc promise yes " + promise.id())))).append(" ");
            }
            line.append(Component.translatable("livingcolonists.promise.give_up").withStyle(style -> style
                    .withColor(net.minecraft.ChatFormatting.RED)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/lc promise no " + promise.id()))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                            net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("livingcolonists.promise.give_up.hint")))));
            player.sendSystemMessage(line);
        }
        return mine.size();
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
        if (server.getTickCount() % MOODS_CHECK_TICKS == 0) {
            restoreMoods();
        }
    }

    /** How often the moods MineColonies dropped are put back: every ten seconds. */
    private static final int MOODS_CHECK_TICKS = 200;

    /** MineColonies keeps only its own happiness modifiers when the world loads: ours are put back from the mod's data. */
    private void restoreMoods() {
        if (data == null || data.moods.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Moods.Lasting> entry : List.copyOf(data.moods.entrySet())) {
            String[] parts = entry.getKey().split(":");
            IColony colony = Citizens.colony(Integer.parseInt(parts[0]));
            if (colony == null || colony.getWorld() == null) {
                continue;
            }
            long now = now(colony);
            if (entry.getValue().until() <= now) {
                data.moods.remove(entry.getKey());
                data.setDirty();
                continue;
            }
            ICitizenData citizen = colony.getCitizenManager().getCivilian(Integer.parseInt(parts[1]));
            if (citizen != null && !Moods.hasPlayersMood(citizen)) {
                Moods.apply(citizen, entry.getValue(), now);
            }
        }
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
        int before = data.relations.getOrDefault(key(citizen, player), Relation.EMPTY).rapport();
        data.relations.merge(key(citizen, player), Relation.EMPTY.with(delta, text, now),
                (old, fresh) -> old.with(delta, text, now));
        me.bytefox.livingcolonists.events.ModEvents.BUS.post(new me.bytefox.livingcolonists.events.GameEvents.RapportChanged(
                citizen, player, before, data.relations.get(key(citizen, player)).rapport()));
        data.setDirty();
        // How a player treats them shows in their happiness for a few days (#61).
        if (delta != 0) {
            Moods.Lasting old = data.moods.get(key(citizen));
            double base = old == null || old.until() <= now ? 1.0 : old.factor();
            Moods.Lasting mood = new Moods.Lasting(Moods.treatment(base, delta), now + Moods.PLAYERS_DAYS * DAY);
            data.moods.put(key(citizen), mood);
            Moods.apply(citizen, mood, now);
        }
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

    /**
     * A player gave the colonist something (#11, #67): it moves their rapport by what they think of it, for at most
     * {@link Gifts#COUNTED_A_WEEK} gifts a week per colonist and player, so gifts can't be farmed. The colonist
     * remembers what they thought of each item either way. Whether the gift counted.
     *
     * @param tastes item id to what the colonist thinks of it; {@code taste} is what the whole gift counts as
     * @param times  more than 1 for a birthday gift (#67): it counts that many times and outside the weekly limit
     */
    public boolean gift(ICitizenData citizen, UUID player, String playerName, String text, int worth, List<String> items,
            Taste taste, Map<String, Taste> tastes, int times) {
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
        Map<String, String> known = data.tastes.computeIfAbsent(key, k -> new HashMap<>());
        tastes.forEach((item, what) -> known.put(item, what.name()));
        data.setDirty();
        List<Long> counted = data.gifted.computeIfAbsent(key, k -> new ArrayList<>());
        counted.removeIf(at -> now - at >= Gifts.WEEK || at > now);
        if (times <= 1) {
            if (counted.size() >= Gifts.COUNTED_A_WEEK) {
                return false;
            }
            counted.add(now);
        }
        data.playerNames.put(player, playerName);
        int warmth = taste.warmth(worth);
        note(citizen, player, warmth > 0 ? warmth * Math.max(1, times) : warmth, text, now);
        return true;
    }

    /** The warmest friendship level the colonist reached with the player, by rank; -1 for none yet (#67). */
    public int reached(ICitizenData citizen, UUID player) {
        return data == null ? -1 : data.reached.getOrDefault(key(citizen, player), -1);
    }

    public void reach(ICitizenData citizen, UUID player, int rank) {
        if (data != null) {
            data.reached.put(key(citizen, player), rank);
            data.setDirty();
        }
    }

    /** The colonist and the player had something to do with each other on this calendar day. */
    public void contact(ICitizenData citizen, UUID player, int day) {
        if (data != null) {
            data.contact.put(key(citizen, player), day);
            data.setDirty();
        }
    }

    /**
     * A new calendar day in the colony (#67): friendships with players the colonist hasn't seen for a while cool a little,
     * grudges fade a little ({@link me.bytefox.livingcolonists.friendship.FriendshipLevels#cooled}). Nothing to remember,
     * no change in happiness.
     */
    public void cool(int colonyId, int today) {
        if (data == null) {
            return;
        }
        String prefix = colonyId + ":";
        for (Map.Entry<String, Relation> entry : data.relations.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            Integer last = data.contact.get(entry.getKey());
            if (last == null || last > today) {
                // Known from before the calendar: the days without contact count from now.
                data.contact.put(entry.getKey(), today);
                data.setDirty();
                continue;
            }
            Relation relation = entry.getValue();
            int cooled = me.bytefox.livingcolonists.friendship.FriendshipLevels.cooled(relation.rapport(), today - last);
            if (cooled != relation.rapport()) {
                entry.setValue(new Relation(cooled, relation.notes()));
                data.setDirty();
            }
        }
    }

    /** The colonist told the player what they think of an item, as if it had been a gift. */
    public void learnTaste(ICitizenData citizen, UUID player, String item, Taste taste) {
        if (data != null) {
            data.tastes.computeIfAbsent(key(citizen, player), k -> new HashMap<>()).put(item, taste.name());
            data.setDirty();
        }
    }

    /** What the colonist thought of the things the player gave them, item ids by taste, in no set order. */
    public Map<Taste, List<String>> knownTastes(ICitizenData citizen, UUID player) {
        Map<Taste, List<String>> out = new java.util.EnumMap<>(Taste.class);
        Map<String, String> known = data == null ? null : data.tastes.get(key(citizen, player));
        if (known != null) {
            new java.util.TreeMap<>(known).forEach((item, taste) -> out.computeIfAbsent(Taste.valueOf(taste), t -> new ArrayList<>()).add(item));
        }
        return out;
    }

    /** The player's open promises, for the notebook's mark of something new. */
    public String promiseNews(ServerPlayer player) {
        if (data == null) {
            return "";
        }
        return data.promises.values().stream().flatMap(List::stream)
                .filter(promise -> promise.isOpen() && player.getUUID().equals(promise.maker()))
                .map(promise -> Long.toString(promise.id())).sorted().collect(java.util.stream.Collectors.joining(","));
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

    /** A colonist brought back from the dead keeps the promises made to them and how they get on with players. */
    public void move(String from, String to) {
        if (data == null) {
            return;
        }
        boolean moved = me.bytefox.livingcolonists.Citizens.move(data.promises, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.moods, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.relations, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.credited, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.gifted, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.tastes, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.reached, from, to);
        moved |= me.bytefox.livingcolonists.Citizens.move(data.contact, from, to);
        if (moved) {
            data.setDirty();
        }
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
        data.moods.keySet().removeIf(key);
        data.gifted.keySet().removeIf(key);
        data.tastes.keySet().removeIf(key);
        data.reached.keySet().removeIf(key);
        data.contact.keySet().removeIf(key);
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

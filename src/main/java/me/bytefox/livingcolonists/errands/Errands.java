package me.bytefox.livingcolonists.errands;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The errands colonists can run for players. Look-ups answer at once. Orders go through code: only
 * the colony's owner and officers may give them (a friend gets a refusal in character), the order
 * must be in the player's own recent words, and nothing happens until the player says yes, by
 * button or by a short "yes" in chat. The last order can be taken back with {@code /lc undo}.
 * Server thread only.
 */
public final class Errands {
    public static final String PLAYER_WORDS = "player_words";
    private static final int KEPT_LOOKUPS = 5;
    /** If the colonist's line never comes (silence, a dropped thread), the buttons show after this long. */
    private static final long QUESTION_FALLBACK_TICKS = 15 * 20;
    private static final int MAX_LOOKUP_TEXT = 600;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record Ticket(Errand.Order order, String citizen, int citizenId, String colony, IColony where) {
    }

    /** Where a carried-out order goes into the colonist's memory. */
    public interface Deeds {
        void remember(ICitizenData citizen, String deed);
    }

    private final Map<String, Errand> errands = new LinkedHashMap<>();
    private final OrderBook<Ticket> book = new OrderBook<>();
    private final NotifyWhen notify;
    private final Notes notes;
    private final Deeds deeds;
    private final ItemIndex items;
    private final Walks walks = new Walks();
    private final Couriers couriers;
    private final GuardTally tally = new GuardTally();
    private final Guards guards;
    /** Yes and no buttons waiting for the colonist's line that asks for them. */
    private final List<Question> questions = new ArrayList<>();

    private record Question(int colonyId, int citizenId, UUID player, Component text, long showBy) {
    }
    /** The latest look-ups in each colony, so everyone in a conversation knows what was looked up. */
    private final Map<Integer, Deque<Lookup>> lookups = new HashMap<>();
    /** Game time of the latest look-up or order in each colony. */
    private final Map<Integer, Long> lastErrand = new HashMap<>();

    private record Lookup(long gameTime, String text) {
    }

    public Errands(PerceptionService perception, Notes.Writer writer, Deeds deeds) {
        this.deeds = deeds;
        items = new ItemIndex();
        notes = new Notes(writer);
        notes.errands().forEach(this::add);
        notify = new NotifyWhen(items);
        add(new CheckStock(items));
        ColonyLookups.all(perception).forEach(this::add);
        add(new ResearchStatus());
        add(new Handbook());
        add(new UpgradeBuilding());
        add(new TakeJob());
        add(new RequestCraft(items));
        add(new Prioritize());
        GuardGear.all().forEach(this::add);
        add(new FarmerPlant(items));
        add(new StartResearch());
        HouseOrders.all(items).forEach(this::add);
        WalkErrands.all(walks).forEach(this::add);
        couriers = new Couriers(walks, items);
        couriers.errands().forEach(this::add);
        guards = new Guards(tally);
        guards.errands().forEach(this::add);
        add(notify);
    }

    private void add(Errand errand) {
        errands.put(errand.name(), errand);
    }

    /**
     * The functions worth offering in this colonist's colony: guard orders only where there are
     * towers, courier jobs only with a courier, fields only with a farm. Fewer functions keep requests
     * smaller and the model's choice clearer.
     */
    public List<ToolSpec> specs(ICitizenData citizen) {
        IColony colony = citizen.getColony();
        boolean towers = colony.getServerBuildingManager().getBuildings().values().stream()
                .anyMatch(b -> b instanceof AbstractBuildingGuards);
        boolean couriers = colony.getCitizenManager().getCitizens().stream()
                .anyMatch(c -> c.getJob() != null && c.getJob().getJobRegistryEntry().getKey().getPath().equals("deliveryman"));
        // By the colony only, not the colonist: the same functions for everyone keep the provider's cache warm.
        // Whether this colonist may do it (a farmer's field, a guard's patrol) is checked when it's called.
        Map<String, Boolean> offered = Map.ofEntries(
                Map.entry("farmer_plant", has(colony, "farmer")),
                Map.entry("patrol_here", towers),
                Map.entry("guard_me", towers), Map.entry("defend_here", towers), Map.entry("red_alert", towers),
                Map.entry("stand_down", towers), Map.entry("guard_gear", towers), Map.entry("arm_guards", towers),
                Map.entry("guard_leaderboard", towers),
                Map.entry("fetch_item", couriers), Map.entry("deliver_item", couriers), Map.entry("courier_board", couriers),
                Map.entry("start_research", has(colony, "university")), Map.entry("research_status", has(colony, "university")),
                Map.entry("add_to_menu", has(colony, "cook")),
                Map.entry("sort_warehouse", has(colony, "warehouse")), Map.entry("check_stock", has(colony, "warehouse")));
        return errands.values().stream().filter(errand -> offered.getOrDefault(errand.name(), true)).map(Errand::spec).toList();
    }

    private static boolean has(IColony colony, String path) {
        return colony.getServerBuildingManager().getBuildings().values().stream()
                .anyMatch(b -> b.getBuildingType().getRegistryName().getPath().equals(path));
    }

    public boolean handles(String tool) {
        return errands.containsKey(tool);
    }

    /** Runs one call for the model; the reply is JSON. */
    public String run(ToolCall call, Errand.Context context) {
        Errand errand = errands.get(call.name());
        lastErrand.put(context.colony().getID(), context.colony().getWorld().getGameTime());
        JsonObject arguments;
        try {
            arguments = call.arguments().isBlank() ? new JsonObject() : JsonParser.parseString(call.arguments()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return Errand.error("The arguments were not valid JSON.").toString();
        }
        if (errand.isOrder()) {
            Optional<JsonObject> refusal = refuse(errand, context, arguments);
            if (refusal.isPresent()) {
                return refusal.get().toString();
            }
        }
        Errand.Result result;
        try {
            result = errand.run(context, arguments);
        } catch (RuntimeException e) {
            LivingColonists.LOGGER.warn("Errand {} failed", call.name(), e);
            return Errand.error("That couldn't be done right now.").toString();
        }
        if (result instanceof Errand.Result.Answer answer) {
            LivingColonists.LOGGER.info("{} by {}: {}{} -> {}", errand.isOrder() ? "Order turned down" : "Look-up",
                    context.citizen().getName(), call.name(), call.arguments(), answer.reply());
        }
        if (result instanceof Errand.Result.Answer answer && !errand.isOrder()) {
            String text = context.citizen().getName() + " looked up " + call.name() + call.arguments() + ": " + answer.reply();
            Deque<Lookup> colony = lookups.computeIfAbsent(context.colony().getID(), id -> new ArrayDeque<>());
            colony.addLast(new Lookup(context.colony().getWorld().getGameTime(),
                    text.length() > MAX_LOOKUP_TEXT ? text.substring(0, MAX_LOOKUP_TEXT) + "…" : text));
            while (colony.size() > KEPT_LOOKUPS) {
                colony.removeFirst();
            }
        }
        return switch (result) {
            case Errand.Result.Answer answer -> answer.reply().toString();
            case Errand.Result.Proposal proposal -> propose(context, proposal.order()).toString();
        };
    }

    /** Why an order can't be taken, if it can't: who asks, and whether they asked in their own words. */
    private Optional<JsonObject> refuse(Errand errand, Errand.Context context, JsonObject arguments) {
        ServerPlayer player = context.player();
        if (player == null) {
            return Optional.of(Errand.error("Orders come only from a player asking you directly."));
        }
        if (!ColonyAccess.canGiveOrders(TalkService.role(player, context.colony()), Config.MEMBERS_ONLY.get())) {
            log(context, errand.name(), "refused: rank");
            return Optional.of(Errand.error("Only the colony's owner and officers give you orders. Turn it down in "
                    + "your own way, without fuss, and suggest asking them."));
        }
        if (!PlayerWords.grounded(Errand.string(arguments, PLAYER_WORDS), context.playerLines())) {
            log(context, errand.name(), "refused: not in the player's words");
            return Optional.of(Errand.error("The player didn't ask for this in their own words. Don't do it unless "
                    + "they ask, and then quote their words exactly in " + PLAYER_WORDS + "."));
        }
        return Optional.empty();
    }

    private JsonObject propose(Errand.Context context, Errand.Order order) {
        ServerPlayer player = context.player();
        long now = player.server.getTickCount();
        book.propose(player.getUUID(), new Ticket(order, context.citizen().getName(), context.citizen().getId(),
                context.colony().getName(), context.colony()), now);
        log(context, order.tool(), "asked: " + order.summary());
        // The buttons come right after the colonist's own line asking for a yes, not before it.
        questions.add(new Question(context.colony().getID(), context.citizen().getId(), player.getUUID(),
                question(context.citizen().getName(), order.question()), now + QUESTION_FALLBACK_TICKS));
        JsonObject reply = new JsonObject();
        reply.addProperty("success", true);
        reply.addProperty("waiting_for_yes", order.summary());
        reply.addProperty("info", "Nothing is done or ordered yet: the player now sees yes and no buttons, and it "
                + "happens only once they agree. In one short line, ask " + TalkService.playerName(player) + " by name "
                + "whether to do it, as a question, like \"Build the residence up to level one, then?\": never say you "
                + "have placed, filed, ordered or started it, and don't guess how long it will take. Don't call this again.");
        return reply;
    }

    private static Component question(String citizen, Component question) {
        MutableComponent yes = Component.translatable("livingcolonists.order.yes").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lc yes"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("livingcolonists.order.yes.hint"))));
        MutableComponent no = Component.translatable("livingcolonists.order.no").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lc no"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("livingcolonists.order.no.hint"))));
        return Component.translatable("livingcolonists.order.confirm", citizen, question).withStyle(ChatFormatting.GRAY)
                .append(" ").append(yes).append(" ").append(no);
    }

    /**
     * A player's chat message while an order waits for them: a short yes or no settles it, and
     * the message goes no further. Anything else is ordinary talk.
     */
    public boolean answer(ServerPlayer player, String message) {
        if (book.pending(player.getUUID(), player.server.getTickCount()).isEmpty()) {
            return false;
        }
        return switch (OrderBook.answer(message)) {
            case YES -> confirm(player) > 0;
            case NO -> cancel(player) > 0;
            case NONE -> false;
        };
    }

    /** {@code /lc yes}: carries out the order waiting for this player. */
    public int confirm(ServerPlayer player) {
        Optional<Ticket> ticket = book.take(player.getUUID(), player.server.getTickCount());
        if (ticket.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.order.nothing"));
            return 0;
        }
        Errand.Order order = ticket.get().order();
        if (!ColonyAccess.canGiveOrders(TalkService.role(player, ticket.get().where()), Config.MEMBERS_ONLY.get())) {
            log(ticket.get(), player, "refused at the yes: rank");
            player.sendSystemMessage(Component.translatable("livingcolonists.order.rank").withStyle(ChatFormatting.RED));
            return 0;
        }
        Errand.Outcome outcome;
        try {
            outcome = order.execute().apply(player);
        } catch (RuntimeException e) {
            LivingColonists.LOGGER.warn("Order {} failed", order.tool(), e);
            outcome = new Errand.Outcome(false, Component.translatable("livingcolonists.order.failed"), null);
        }
        if (outcome.done()) {
            book.done(player.getUUID(), ticket.get(), outcome.undo());
            ICitizenData citizen = ticket.get().where().getCitizenManager().getCivilian(ticket.get().citizenId());
            if (citizen != null) {
                deeds.remember(citizen, "On " + TalkService.playerName(player) + "'s order I saw to it: " + order.summary() + ".");
            }
        }
        log(ticket.get(), player, (outcome.done() ? "done: " : "failed: ") + order.summary());
        player.sendSystemMessage(Component.translatable("livingcolonists.order.outcome", ticket.get().citizen(),
                outcome.message()).withStyle(outcome.done() ? ChatFormatting.GRAY : ChatFormatting.RED));
        return outcome.done() ? 1 : 0;
    }

    /** {@code /lc no}: drops the order waiting for this player. */
    public int cancel(ServerPlayer player) {
        Optional<Ticket> ticket = book.take(player.getUUID(), player.server.getTickCount());
        if (ticket.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.order.nothing"));
            return 0;
        }
        log(ticket.get(), player, "cancelled: " + ticket.get().order().summary());
        player.sendSystemMessage(Component.translatable("livingcolonists.order.cancelled", ticket.get().citizen())
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** {@code /lc undo}: takes back this player's last order, where it can be taken back. */
    public int undo(ServerPlayer player) {
        Optional<OrderBook.Undo<Ticket>> last = book.takeUndo(player.getUUID());
        if (last.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.order.nothing_to_undo"));
            return 0;
        }
        last.get().undo().run();
        log(last.get().order(), player, "undone: " + last.get().order().order().summary());
        player.sendSystemMessage(Component.translatable("livingcolonists.order.undone", last.get().order().citizen())
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** Whether anything was looked up or ordered in a colony since a game time. */
    public boolean errandSince(int colonyId, long since) {
        return lastErrand.getOrDefault(colonyId, Long.MIN_VALUE) >= since;
    }

    /** What was looked up in a colony since a game time, oldest first. */
    public List<String> lookups(int colonyId, long since) {
        return lookups.getOrDefault(colonyId, new ArrayDeque<>()).stream().filter(lookup -> lookup.gameTime() >= since)
                .map(Lookup::text).toList();
    }

    /** {@code /lcdebug orders}: what was ordered lately, newest last. */
    public List<String> log() {
        return book.log();
    }

    /** Once a second: the watches for things colonists were asked to tell about, and late buttons. */
    public void tick(MinecraftServer server) {
        notes.tick(server);
        walks.tick(server);
        couriers.tick(server);
        guards.tick(server);
        if (server.getTickCount() % 20 == 0) {
            notify.tick(server);
            long now = server.getTickCount();
            questions.removeIf(question -> question.showBy() <= now && show(server, question, book));
        }
    }

    /**
     * Before the world is saved on shutdown: towers on a duty from a player go back to their own,
     * or they would keep following or holding a spot after a restart with nobody to stand them down.
     */
    public void beforeStop() {
        guards.clear();
    }

    /** At server start: item names get ready in the background for the common locales. */
    public void warmUp() {
        items.warmUp("ru_ru");
        items.warmUp("en_us");
    }

    /** Whether an order waits for this player's yes. */
    public boolean hasPending(ServerPlayer player, long now) {
        return book.pending(player.getUUID(), now).isPresent();
    }

    /** Whether the colonist is on a walk for a player. */
    public boolean isWalking(ICitizenData citizen) {
        return walks.isWalking(citizen);
    }

    /** The guards' kills, kept with the world. */
    public GuardTally tally() {
        return tally;
    }

    /** Messages and reminders, kept with the world. */
    public Notes notes() {
        return notes;
    }

    /** A colonist's line went out: the buttons it asks the player to press come right after it. */
    public void afterLine(MinecraftServer server, int colonyId, int citizenId) {
        questions.removeIf(question -> question.colonyId() == colonyId && question.citizenId() == citizenId
                && show(server, question, book));
    }

    /** Shows the buttons, unless the order was answered or ran out meanwhile: stale buttons would only confuse. */
    private static boolean show(MinecraftServer server, Question question, OrderBook<?> book) {
        ServerPlayer player = server.getPlayerList().getPlayer(question.player());
        if (player != null && book.pending(question.player(), server.getTickCount()).isPresent()) {
            player.sendSystemMessage(question.text());
        }
        return true;
    }

    public void clear() {
        notify.clear();
        questions.clear();
        walks.clear();
        couriers.clear();
        guards.clear();
        book.clear();
        lookups.clear();
        lastErrand.clear();
    }

    private void log(Errand.Context context, String tool, String what) {
        String player = context.player() == null ? "-" : context.player().getGameProfile().getName();
        line(context.colony().getName(), player, context.citizen().getName(), tool + " " + what);
    }

    private void log(Ticket ticket, ServerPlayer player, String what) {
        line(ticket.colony(), player.getGameProfile().getName(), ticket.citizen(), ticket.order().tool() + " " + what);
    }

    private void line(String colony, String player, String citizen, String what) {
        String line = LocalTime.now().format(CLOCK) + " [" + colony + "] " + player + " -> " + citizen + ": " + what;
        book.log(line);
        LivingColonists.LOGGER.info("Order {}", line);
    }

    /** A function with one required text parameter, or none. */
    static ToolSpec spec(String name, String description, String parameter, String parameterDescription) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (parameter != null) {
            parameters.put(parameter, parameterDescription);
        }
        return spec(name, description, parameters);
    }

    /** An order that happens at once, without a yes: its parameters and the player's own words. */
    static ToolSpec commandSpec(String name, String description, Map<String, String> parameters) {
        Map<String, String> all = new LinkedHashMap<>(parameters);
        all.put(PLAYER_WORDS, "The player's own words asking for this, quoted exactly from their message");
        return spec(name, description + " Only when the player asks for it themselves.", all);
    }

    /** An order: its parameters, and the player's own words asking for it. */
    static ToolSpec orderSpec(String name, String description, Map<String, String> parameters) {
        Map<String, String> all = new LinkedHashMap<>(parameters);
        all.put(PLAYER_WORDS, "The player's own words asking for this, quoted exactly from their message");
        return spec(name, description + " Only when the player asks for it themselves; they confirm before it happens.", all);
    }

    static ToolSpec spec(String name, String description, Map<String, String> parameters) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        parameters.forEach((key, text) -> {
            JsonObject property = new JsonObject();
            property.addProperty("type", "string");
            property.addProperty("description", text);
            properties.add(key, property);
            required.add(key);
        });
        schema.add("properties", properties);
        schema.add("required", required);
        return new ToolSpec(name, description, schema);
    }
}

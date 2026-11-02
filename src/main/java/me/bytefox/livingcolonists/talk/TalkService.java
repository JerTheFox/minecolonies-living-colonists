package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Cooldown;
import me.bytefox.livingcolonists.behaviour.HourlyLimit;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Promise;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.memory.Fact;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.errands.Errands;
import me.bytefox.livingcolonists.errands.Notes;
import me.bytefox.livingcolonists.perception.ColonyTools;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptBudget;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Private talks between a player and a colonist, and the shared machinery for any colonist line:
 * prompt values, the request with colony functions, and helpers. Colony chat lives in
 * {@link ColonyThreads}.
 *
 * <p>Threading: requests start on the server thread, which copies everything the prompt needs
 * into strings and ids. HTTP runs on the HTTP client's threads. Results come back to the server
 * thread through {@link MinecraftServer#execute}, where colonists and players are looked up again
 * by id, because either may be gone by then. Colonists are addressed through their colony data,
 * so they can answer even when their entity is in an unloaded chunk.</p>
 */
public final class TalkService {
    /** Who a colonist is when personalities are off: a plain hand for running the colony by chat. */
    private static final String PLAIN = "You are businesslike: you answer what you are asked, plainly and briefly, and "
            + "see to what the colony's owner and officers ask without fuss. No small talk, jokes or worries of your own.";

    private final LlmClient client;
    private final PromptLibrary prompts;
    private final PersonalityService personalities;
    private final PerceptionService perception;
    private final MemoryService memory;
    private final BondsService bonds;
    private final ColonyTools tools;
    private final me.bytefox.livingcolonists.mood.ColonyMoods moods = new me.bytefox.livingcolonists.mood.ColonyMoods();
    private final Errands errands;
    private final Initiative initiative;
    private final Arrivals arrivals;
    private InitiativeData initiativeData;
    private final GiftWatch gifts;
    private final PlayerDeeds deeds;
    private final RelationsDrift drift;
    /** One budget for every line colonists say unprompted (#40). */
    private final Airtime airtime = new Airtime();
    /** How close a colonist must be to see what a player holds and wears, in blocks. */
    private static final double LOOK_RANGE = 16;
    /** When each player last wrote to colonists, in server ticks: unprompted lines wait until they're quiet. */
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private final ColonyThreads threads;
    private final Replies replies;
    private final ColonyEvents events;
    private final SpeechOutput output = new BubbleSpeechOutput(new ChatSpeechOutput());
    private final Typing typing = new Typing();
    private final Random random = me.bytefox.livingcolonists.Dice.random();

    /** Pause between one player's messages to colonists. Server thread only. */
    private final Cooldown cooldown = new Cooldown();
    /** Optional cap on one player's messages to colonists per hour. Server thread only. */
    private final HourlyLimit hourly = new HourlyLimit();
    /** Colonists waiting for a private reply, as {@link #key}. Server thread only. */
    private final Set<Long> thinking = new HashSet<>();
    /** Private replies waiting for their typing pause (see {@link LinePacing}). Server thread only. */
    private final PriorityQueue<PendingLine> pending = new PriorityQueue<>(Comparator.comparingLong(PendingLine::tick));

    /** Private lines each player got from colonists lately, newest last: what a colonist's own line mustn't repeat. */
    private final Map<UUID, java.util.Deque<String>> heard = new HashMap<>();
    private static final int HEARD_LINES = 8;

    private record PendingLine(long tick, ResourceKey<Level> dimension, Speaker speaker, UUID playerId, String text) {
    }

    public TalkService(LlmClient client, PromptLibrary prompts, PersonalityService personalities,
                       PerceptionService perception, MemoryService memory, BondsService bonds) {
        this.client = client;
        this.prompts = prompts;
        this.personalities = personalities;
        this.perception = perception;
        this.memory = memory;
        this.bonds = bonds;
        this.tools = new ColonyTools(perception);
        this.errands = new Errands(perception, this::writeFirst, memory::rememberDeed);
        this.replies = new Replies(this, client, tools, errands, memory);
        this.threads = new ColonyThreads(this, prompts, memory, personalities, output, typing, bonds);
        this.events = new ColonyEvents(threads, moods);
        // A player dying in the colony worries it (#65).
        ModEvents.BUS.on(GameEvents.PlayerDied.class, event -> {
            IColony colony = me.bytefox.livingcolonists.colony.Colonies.at(event.player());
            if (colony != null) {
                moods.raise(colony.getID(), "player_died", Audience.playerName(event.player()) + " died in the colony",
                        List.of(), event.player().server.getTickCount());
            }
        });
        // An illness worries the colony a little (#65); the news itself is LifeNews's.
        ModEvents.BUS.on(GameEvents.FellIll.class, event -> {
            ICitizenData ill = event.citizen();
            if (ill.getColony().getWorld() != null && ill.getColony().getWorld().getServer() != null) {
                moods.raise(ill.getColony().getID(), "illness", ill.getName() + " has fallen ill with " + event.illness(),
                        List.of(ill.getId()), ill.getColony().getWorld().getServer().getTickCount());
            }
        });
        this.initiative = new Initiative(this, threads, perception, memory, bonds);
        this.arrivals = new Arrivals(this, threads, events, initiative, bonds, perception, memory);
        LifeNews news = new LifeNews(memory, events);
        ModEvents.BUS.on(GameEvents.JobChanged.class, news::jobChanged);
        ModEvents.BUS.on(GameEvents.FellIll.class, news::fellIll);
        this.gifts = new GiftWatch(this, bonds, memory, personalities);
        this.deeds = new PlayerDeeds(events);
        this.drift = new RelationsDrift(this, threads, bonds, memory);
        this.friendship = new me.bytefox.livingcolonists.friendship.Friendship(bonds, calendar);
        this.letters = new me.bytefox.livingcolonists.friendship.Letters(bonds, calendar);
        this.help = new me.bytefox.livingcolonists.friendship.Help(bonds);
        this.workBoost = new me.bytefox.livingcolonists.friendship.WorkBoost(bonds);
        this.menu = new TalkMenu(bonds, calendar, perception, personalities);
        this.barks = new me.bytefox.livingcolonists.social.Barks(bonds, moods, this::voice,
                (occasion, voice, locale, dice) -> SetLines.pick(occasion, voice, locale, dice),
                (colonist, text, player) -> Bubbles.line(colonist, text, List.of(player)));
        FriendshipNews friendshipNews = new FriendshipNews(this, bonds, memory);
        ModEvents.BUS.on(GameEvents.FriendshipMilestone.class, friendshipNews::milestone);
        CalendarNews calendarNews = new CalendarNews(events, errands.tally(), calendar);
        ModEvents.BUS.on(GameEvents.CalendarDay.class, calendarNews::newDay);
    }

    Replies replies() {
        return replies;
    }

    public ColonyThreads threads() {
        return threads;
    }

    public BondsService bonds() {
        return bonds;
    }

    /** How often the colony's lasting troubles stir its mood: every two minutes. */
    private static final long CONDITIONS_TICKS = 2 * 60 * 20;

    /** Hunger, homelessness and unhappiness that last make the colony glum (#65). */
    private void colonyConditions(long now) {
        for (IColony colony : me.bytefox.livingcolonists.colony.Colonies.all()) {
            if (!active(colony)) {
                continue;
            }
            try {
                List<ICitizenData> adults = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).toList();
                int hungry = (int) adults.stream().filter(c -> c.getSaturation() < me.bytefox.livingcolonists.mood.Conditions.HUNGRY).count();
                int homeless = (int) adults.stream().filter(c -> c.getHomeBuilding() == null).count();
                int unhappy = (int) adults.stream()
                        .filter(c -> c.getCitizenHappinessHandler().getHappiness(colony, c) < me.bytefox.livingcolonists.mood.Conditions.UNHAPPY)
                        .count();
                me.bytefox.livingcolonists.mood.Conditions.troubles(adults.size(), hungry, homeless, unhappy)
                        .forEach(trouble -> moods.raise(colony.getID(), trouble.occasion(), trouble.cause(), List.of(), now));
            } catch (RuntimeException | LinkageError e) {
                // A colony MineColonies can't tell about now is looked at next time.
            }
        }
    }

    private final me.bytefox.livingcolonists.friendship.Calendar calendar = new me.bytefox.livingcolonists.friendship.Calendar();

    private final me.bytefox.livingcolonists.friendship.Friendship friendship;
    private final me.bytefox.livingcolonists.friendship.Letters letters;
    private final me.bytefox.livingcolonists.friendship.Help help;
    private final me.bytefox.livingcolonists.friendship.WorkBoost workBoost;

    private final TalkMenu menu;
    private final me.bytefox.livingcolonists.social.Barks barks;

    /** How a colonist sounds in set lines, by their character (#68). */
    String voice(ICitizenData citizen) {
        me.bytefox.livingcolonists.personality.Personality personality = personalities.get(citizen);
        return me.bytefox.livingcolonists.social.Voices.of(personality == null ? null : personality.archetype());
    }

    /** The talk menu without a model (#68). */
    public TalkMenu menu() {
        return menu;
    }

    /** Letters from colonists (#67). */
    public me.bytefox.livingcolonists.friendship.Letters letters() {
        return letters;
    }

    /** Help in trouble (#67). */
    public me.bytefox.livingcolonists.friendship.Help help() {
        return help;
    }

    /** The colony calendars (#67). */
    public me.bytefox.livingcolonists.friendship.Calendar calendar() {
        return calendar;
    }

    /** The colonies' moods (#65). */
    public me.bytefox.livingcolonists.mood.ColonyMoods moods() {
        return moods;
    }

    public Errands errands() {
        return errands;
    }

    /** Whether colonists can write first at all now: conversation on, a model set and the day's tokens left. */
    boolean canWriteFirst() {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        // Without a model colonists still greet and now and then say a catchphrase, in set lines (#68).
        return Config.level(Module.CONVERSATION) != me.bytefox.livingcolonists.behaviour.Level.OFF
                && (settings.isEmpty() || !client.overDailyLimit() && !client.paused(settings.get()));
    }

    /** When the player last wrote to colonists, in server ticks; long ago if never. */
    long lastMessage(UUID player) {
        return lastMessage.getOrDefault(player, Long.MIN_VALUE / 2);
    }

    /** Colonists writing first: what they already brought up, kept with the world. */
    public void attachInitiative(InitiativeData data) {
        initiativeData = data;
        initiative.attach(data);
        arrivals.attach(data);
        events.attach(data);
    }

    public void detachInitiative() {
        initiativeData = null;
        initiative.detach();
        arrivals.detach();
        events.detach();
    }

    /** Drops what gone colonists brought up, by their {@code colonyId:citizenId} key. */
    public void forgetInitiative(java.util.function.Predicate<String> citizenKey) {
        initiative.forget(citizenKey);
    }

    LlmClient client() {
        return client;
    }

    Airtime airtime() {
        return airtime;
    }

    public GiftWatch gifts() {
        return gifts;
    }

    /** Whether colonists talk at all: the mod is on for the server (#55). */
    public static boolean enabled() {
        return !Config.SPEC.isLoaded() || Config.ENABLED.get();
    }

    /** Whether this colony's colonists talk: the mod is on, and not turned off for the colony (#55). */
    public boolean active(IColony colony) {
        return enabled() && (initiativeData == null || !initiativeData.off.contains(colony.getID()));
    }

    /** Turns the mod off or on for one colony; whether that changed anything. */
    public boolean setColonyOff(IColony colony, boolean off) {
        if (initiativeData == null) {
            return false;
        }
        boolean changed = off ? initiativeData.off.add(colony.getID()) : initiativeData.off.remove(colony.getID());
        if (changed) {
            initiativeData.setDirty();
            if (off) {
                threads.stop(colony);
            }
        }
        return changed;
    }

    /** A player killed something: raiders or monsters fought off in the colony are news (#50). */
    public void playerKilled(ServerPlayer player, net.minecraft.world.entity.Entity victim) {
        deeds.killed(player, victim);
    }

    /** The level the shared budget for unprompted lines runs at. */
    static me.bytefox.livingcolonists.behaviour.Level airtimeLevel() {
        me.bytefox.livingcolonists.behaviour.Level level = Config.level(Module.INITIATIVE);
        return level == me.bytefox.livingcolonists.behaviour.Level.OFF ? me.bytefox.livingcolonists.behaviour.Level.DEFAULT
                : level;
    }

    /** Sends private replies whose turn has come and moves colony threads on. Server tick. */
    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now % CONDITIONS_TICKS == 0) {
            colonyConditions(now);
        }
        while (!pending.isEmpty() && pending.peek().tick() <= now) {
            PendingLine line = pending.poll();
            ICitizenData citizen = Audience.resolve(line.dimension(), line.speaker().colonyId(), line.speaker().citizenId());
            ServerPlayer player = server.getPlayerList().getPlayer(line.playerId());
            typing.stop(server, line.speaker());
            if (citizen != null && player != null) {
                output.colonistWhispered(line.speaker(), player, line.text());
                java.util.Deque<String> lines = heard.computeIfAbsent(player.getUUID(), id -> new java.util.ArrayDeque<>());
                lines.addLast(line.text());
                while (lines.size() > HEARD_LINES) {
                    lines.removeFirst();
                }
                errands.afterLine(server, line.speaker().colonyId(), line.speaker().citizenId());
                memory.rememberPrivate(citizen, player.getUUID(), line.speaker().name(), line.text());
            }
        }
        calendar.tick(server);
        help.tick(server);
        barks.tick(server);
        workBoost.tick(server);
        threads.tick(server);
        errands.tick(server);
        initiative.tick(server);
        arrivals.tick(server);
        drift.tick(server);
        if (now % SWEEP_TICKS == 0) {
            sweep(server, now);
        }
        events.tick(server);
        typing.tick(server);
    }

    /** How often what is kept for gone players and long-past timers is dropped (ten minutes). */
    private static final long SWEEP_TICKS = 10 * 60 * 20;

    /** Keeps a long-running server from piling up per-player state of players long gone (#40). */
    private void sweep(MinecraftServer server, long now) {
        Set<UUID> online = new HashSet<>();
        server.getPlayerList().getPlayers().forEach(player -> online.add(player.getUUID()));
        heard.keySet().retainAll(online);
        lastMessage.entrySet().removeIf(entry -> !online.contains(entry.getKey()) && now - entry.getValue() > SWEEP_TICKS);
        initiative.sweep(online, now);
        arrivals.sweep(online, now);
        airtime.sweep(now, airtimeLevel());
    }

    /** The last prompt sent for this colonist's reply, if any. */
    public Optional<String> lastPrompt(ICitizenData citizen) {
        return replies.lastPrompt(citizen);
    }

    public void clear() {
        replies.clear();
        cooldown.clear();
        hourly.clear();
        thinking.clear();
        pending.clear();
        threads.clear();
        events.clear();
        typing.clear();
        errands.clear();
        initiative.clear();
        arrivals.clear();
        airtime.clear();
        gifts.clear();
        deeds.clear();
        lastMessage.clear();
    }

    /** A player's private message to one colonist; the colonist remembers the talk. */
    public void sayPrivately(ServerPlayer player, ICitizenData citizen, String message) {
        lastMessage.put(player.getUUID(), (long) player.server.getTickCount());
        if (errands.answer(player, message)) {
            return;
        }
        if (!admit(player, citizen.getColony())) {
            return;
        }
        if (!Config.modelOn()) {
            // Without a model the colonist answers with a set line by how they feel about the player (#68).
            Speaker speaker = Audience.speakerOf(citizen);
            output.playerWhispered(player, speaker, message);
            memory.rememberPrivate(citizen, player.getUUID(), Audience.playerName(player), message);
            noModelHint(player);
            whisper(player, citizen, new SetLine("reply." + warmth(bonds.playerRapport(citizen, player.getUUID()))));
            return;
        }
        Speaker speaker = Audience.speakerOf(citizen);
        long key = key(speaker.colonyId(), speaker.citizenId());
        if (thinking.contains(key)) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.busy", speaker.name()));
            return;
        }
        output.playerWhispered(player, speaker, message);
        List<MemoryLine> history = memory.privateTalk(citizen, player.getUUID());
        String playerName = Audience.playerName(player);
        memory.rememberPrivate(citizen, player.getUUID(), playerName, message);

        List<ChatMessage> messages = new ArrayList<>();
        for (MemoryLine line : history) {
            messages.add(line.speaker().equals(speaker.name()) ? ChatMessage.assistant(line.text()) : ChatMessage.user(line.text()));
        }
        messages.add(ChatMessage.user(message));
        Map<String, String> values = promptValues(citizen, player);
        values.put("language", replyLanguage(citizen, player, speaker, history, message));
        Optional<Fact> rumor = memory.rumor(citizen, player.getUUID(), random);
        rumor.ifPresent(fact -> values.put("initiative", values.get("initiative") + " " + Prompts.gossip(fact.text())));

        thinking.add(key);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = citizen.getColony().getDimension();
        long earliest = server.getTickCount() + LinePacing.gap(random);
        typing.start(server, speaker, List.of(player), earliest);
        // The facts this message touches, by meaning when embeddings are set up: a moment, while the colonist "reads".
        memory.memoriesFor(citizen, Set.of(Fact.aboutCitizen(citizen.getId()), Fact.aboutPlayer(playerId)), message, server,
                memories -> {
            values.put("memories", memories);
            String template = prompts.get(PromptLibrary.REPLY_PRIVATE);
            String prompt = Templates.render(template, PromptBudget.fit(values, template));
            replies.generate(citizen, prompt, messages, playerId, server, result -> {
                thinking.remove(key);
                ServerPlayer author = server.getPlayerList().getPlayer(playerId);
                Optional<String> text = Replies.lineOf(speaker, result);
                if (text.isEmpty()) {
                    typing.stop(server, speaker);
                    if (author != null && result instanceof ChatResult.Failure) {
                        author.sendSystemMessage(Component.translatable("livingcolonists.talk.failed", speaker.name()));
                    }
                    return;
                }
                rumor.ifPresent(fact -> told(dimension, speaker, fact));
                long at = LinePacing.slot(server.getTickCount(), earliest + LinePacing.typingTicks(text.get().length()), 0);
                pending.add(new PendingLine(at, dimension, speaker, playerId, text.get()));
            });
        });
    }

    /**
     * A colonist writes first to remind a player of an overdue promise, in private, while the player
     * is in the colony. Returns whether the reminder went ahead.
     */
    public boolean remindPromise(ServerPlayer player, ICitizenData citizen, Promise promise) {
        boolean sent = writeFirst(player, citizen, "to remind them of a promise that is overdue now: \"" + promise.text()
                + "\". Say it in your own way and mood, in one or two sentences.", true, null, false, Airtime.Kind.NOTE,
                new SetLine("promise.overdue", promise.display().getString())) == Notes.Delivery.SENT;
        if (sent) {
            ModLog.action("{} reminds {} of an overdue promise: {}", citizen.getName(),
                    player.getGameProfile().getName(), promise.text());
        }
        return sent;
    }

    /**
     * A colonist writes to a player first, in private, for a reason the prompt gives (a reminder, a
     * message passed on).
     *
     * @param inColonyOnly only while the player is inside the colony
     * @param failed        runs if the model gave no line after all; may be null
     * @param ownTopic      something the colonist brings up of their own accord, checked against going round in
     *                      circles; false for a message passed on or a reminder, which must say what it was asked to
     */
    public Notes.Delivery writeFirst(ServerPlayer player, ICitizenData citizen, String purpose, boolean inColonyOnly,
                                     Runnable failed, boolean ownTopic) {
        return writeFirst(player, citizen, purpose, inColonyOnly, failed, ownTopic,
                ownTopic ? Airtime.Kind.FIRST_LINE : Airtime.Kind.NOTE);
    }

    /** The same, as a line of {@code kind} for the shared budget: held back for later if it is spent. */
    Notes.Delivery writeFirst(ServerPlayer player, ICitizenData citizen, String purpose, boolean inColonyOnly,
                              Runnable failed, boolean ownTopic, Airtime.Kind kind) {
        return writeFirst(player, citizen, purpose, inColonyOnly, failed, ownTopic, kind, null);
    }

    /** What a colonist says without a model (#68): an occasion of {@link SetLines} and what fills it in. */
    record SetLine(String occasion, Object... args) {
    }

    /** The same; without a model, {@code fallback} is said instead, if there is one. */
    Notes.Delivery writeFirst(ServerPlayer player, ICitizenData citizen, String purpose, boolean inColonyOnly,
                              Runnable failed, boolean ownTopic, Airtime.Kind kind, SetLine fallback) {
        IColony colony = citizen.getColony();
        Speaker speaker = Audience.speakerOf(citizen);
        long key = key(speaker.colonyId(), speaker.citizenId());
        if (Config.level(Module.CONVERSATION) == me.bytefox.livingcolonists.behaviour.Level.OFF
                || Config.modelOn() && client.overDailyLimit() || !Config.modelOn() && fallback == null) {
            return Notes.Delivery.NEVER;
        }
        if (!active(colony)) {
            return Notes.Delivery.NEVER;
        }
        boolean inside = Audience.isInside(player, colony);
        if (inColonyOnly && !inside || thinking.contains(key)) {
            return Notes.Delivery.LATER;
        }
        String bucket = Airtime.player(player.getUUID());
        if (!airtime.allows(kind, bucket, player.server.getTickCount(), airtimeLevel())) {
            return Notes.Delivery.LATER;
        }
        airtime.spend(bucket, player.server.getTickCount());
        Counters.add("said." + kind.key());
        if (!Config.modelOn()) {
            return whisper(player, citizen, fallback) ? Notes.Delivery.SENT : Notes.Delivery.NEVER;
        }
        String playerName = Audience.playerName(player);
        List<ChatMessage> messages = new ArrayList<>();
        for (MemoryLine line : memory.privateTalk(citizen, player.getUUID())) {
            messages.add(line.speaker().equals(speaker.name()) ? ChatMessage.assistant(line.text()) : ChatMessage.user(line.text()));
        }
        messages.add(ChatMessage.user("[" + playerName + (inside ? " is in the colony" : " is away from the colony")
                + ". You write to them first.]"));
        Map<String, String> values = promptValues(citizen, player);
        values.put("initiative", "You write to " + playerName + " first, unprompted, " + purpose + " "
                + "Write in " + Languages.ofLocale(player.clientInformation().language()) + ".");
        values.put("language", Languages.ofLocale(player.clientInformation().language()));
        String template = prompts.get(PromptLibrary.REPLY_PRIVATE);
        String prompt = Templates.render(template, PromptBudget.fit(values, template));
        thinking.add(key);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = colony.getDimension();
        long earliest = server.getTickCount() + LinePacing.gap(random);
        typing.start(server, speaker, List.of(player), earliest);
        replies.generateFresh(citizen, prompt, messages, null, server,
                ownTopic ? List.copyOf(heard.getOrDefault(playerId, new java.util.ArrayDeque<>())) : List.of(),
                result -> {
            thinking.remove(key);
            Optional<String> text = Replies.lineOf(speaker, result);
            if (text.isEmpty()) {
                typing.stop(server, speaker);
                if (failed != null && server.getPlayerList().getPlayer(playerId) != null) {
                    failed.run();
                }
                return;
            }
            long at = LinePacing.slot(server.getTickCount(), earliest + LinePacing.typingTicks(text.get().length()), 0);
            pending.add(new PendingLine(at, dimension, speaker, playerId, text.get()));
        });
        return Notes.Delivery.SENT;
    }

    /**
     * A set line said privately to the player, after a typing pause, as a reply from the model would be (#68). Whether
     * the occasion had a line.
     */
    boolean whisper(ServerPlayer player, ICitizenData citizen, SetLine line) {
        Optional<String> text = SetLines.pick(line.occasion(), voice(citizen), player.clientInformation().language(), random, line.args());
        if (text.isEmpty()) {
            return false;
        }
        Speaker speaker = Audience.speakerOf(citizen);
        MinecraftServer server = player.server;
        long earliest = server.getTickCount() + LinePacing.gap(random);
        typing.start(server, speaker, List.of(player), earliest);
        long at = LinePacing.slot(server.getTickCount(), earliest + LinePacing.typingTicks(text.get().length()), 0);
        pending.add(new PendingLine(at, citizen.getColony().getDimension(), speaker, player.getUUID(), text.get()));
        Counters.add("lines.set");
        return true;
    }

    /** The colonist passed a fact on as gossip, so they won't retell it. */
    void told(ResourceKey<Level> dimension, Speaker speaker, Fact fact) {
        ICitizenData citizen = Audience.resolve(dimension, speaker.colonyId(), speaker.citizenId());
        if (citizen != null) {
            memory.markTold(citizen, fact);
        }
    }

    /** A message to the whole colony goes into its conversation thread. */
    public void sayToColony(ServerPlayer player, IColony colony, String message) {
        lastMessage.put(player.getUUID(), (long) player.server.getTickCount());
        if (admit(player, colony)) {
            if (!Config.modelOn()) {
                noModelHint(player);
            }
            threads.onPlayerMessage(player, colony, message);
        }
    }

    /**
     * Values for reply templates: who the colonist is, what they know, and how to treat the player.
     *
     * @param player the player being answered; null when colonists talk among themselves
     */
    Map<String, String> promptValues(ICitizenData citizen, ServerPlayer player) {
        Speaker speaker = Audience.speakerOf(citizen);
        MinecraftServer server = citizen.getColony().getWorld().getServer();
        Map<String, String> values = new HashMap<>(personalities.promptValues(citizen, server));
        values.put("name", speaker.name());
        // Without personalities (the MINIMAL profile) colonists are a plain way to run the colony by chat.
        boolean plain = Config.level(Module.PERSONALITY) == me.bytefox.livingcolonists.behaviour.Level.OFF;
        values.put("character", plain ? PLAIN
                : (values.getOrDefault("character", "") + "\nHow you talk: " + Prompts.voice(speaker.name())).strip());
        if (citizen.isChild()) {
            values.put("character", (values.getOrDefault("character", "") + "\nYou are still a child: talk like one, "
                    + "simply and in short sentences, about the things children care about.").strip());
        }
        values.put("job", speaker.job());
        values.put("colony", speaker.colony());
        values.put("time", Prompts.timeOfDay(citizen.getColony().getWorld().getDayTime()));
        values.put("mood", plain ? "calm and busy with your work" : Prompts.mood(citizen.getId(),
                citizen.getColony().getWorld().getDayTime() / 24000, happiness(citizen)));
        values.put("situation", perception.situation(citizen));
        // The colony's mood colours every line, harder for those it touches closely (#65).
        if (citizen.getColony().getWorld() != null && citizen.getColony().getWorld().getServer() != null) {
            moods.tone(citizen.getColony().getID(), citizen.getId(), citizen.getColony().getWorld().getServer().getTickCount())
                    .ifPresent(tone -> values.put("situation", values.get("situation") + "\n" + tone));
        }
        CalendarNews.situation(calendar, citizen).ifPresent(line -> values.put("situation", values.get("situation") + "\n" + line));
        List<String> lately = errands.lookups(citizen.getColony().getID(), citizen.getColony().getWorld().getGameTime());
        if (!lately.isEmpty()) {
            // What anyone in the colony looked up lately is known to all (#56).
            values.put("situation", values.get("situation") + "\nLooked up in the colony lately, true whatever anyone says:\n- "
                    + String.join("\n- ", lately));
            if (lately.stream().anyMatch(line -> line.contains("where_enemies"))) {
                values.put("situation", values.get("situation") + "\n"
                        + me.bytefox.livingcolonists.perception.Enemies.now(citizen.getColony()));
            }
        }
        if (player != null && Config.level(Module.PERCEPTION) != me.bytefox.livingcolonists.behaviour.Level.OFF) {
            String look = look(citizen, player);
            if (!look.isEmpty()) {
                values.put("situation", values.get("situation") + "\n" + look);
            }
        }
        Set<String> present = player == null ? Set.of(Fact.aboutCitizen(citizen.getId()))
                : Set.of(Fact.aboutCitizen(citizen.getId()), Fact.aboutPlayer(player.getUUID()));
        values.put("memories", memory.memories(citizen, present, ""));
        values.put("initiative", plain ? "Answer only what the player said." : Prompts.initiative(random));
        if (player != null) {
            memory.seen(player.getUUID(), Audience.playerName(player), player.getGameProfile().getName());
            Map<String, List<String>> names = Config.playerNames();
            values.put("player", PlayerNames.name(player.getGameProfile().getName(), names));
            values.put("addressing", PlayerNames.addressing(player.getGameProfile().getName(), names, random,
                    bonds.playerRapport(citizen, player.getUUID())));
            values.put("bonds", bonds.block(citizen, player, values.get("player")));
        } else {
            values.put("bonds", bonds.block(citizen, null, null));
            values.put("player", "a player");
            values.put("addressing", "");
        }
        return values;
    }

    /** MineColonies' happiness, 0 to 10; middling if it can't be read. */
    private static double happiness(ICitizenData citizen) {
        try {
            return citizen.getCitizenHappinessHandler().getHappiness(citizen.getColony(), citizen);
        } catch (RuntimeException e) {
            return 6;
        }
    }


    /** Whether the player may talk to this colony's colonists right now; tells them if not. */
    private boolean admit(ServerPlayer player, IColony colony) {
        if (!active(colony)) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.off", colony.getName()));
            return false;
        }
        if (!Audience.mayTalk(player, colony)) {
            return false;
        }
        if (client.overDailyLimit()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.limit.daily"));
            return false;
        }
        Optional<Long> wait = cooldown.tryUse(player.getUUID(), player.server.getTickCount(),
                Config.COOLDOWN_SECONDS.get() * 20L);
        if (wait.isPresent()) {
            player.displayClientMessage(Component.translatable("livingcolonists.talk.cooldown",
                    (wait.get() + 19) / 20), true);
            return false;
        }
        Optional<Long> hour = hourly.tryUse(player.getUUID(), player.server.getTickCount(),
                Config.MESSAGES_PER_PLAYER_PER_HOUR.get());
        if (hour.isPresent()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.limit.hourly", (hour.get() + 1199) / 1200));
            return false;
        }
        return true;
    }



    /** Players already told this run that colonists talk in set lines. */
    private final Set<UUID> toldNoModel = new HashSet<>();

    /** Once a run, the player hears that colonists answer with set lines: no language model is set up (#68). */
    private void noModelHint(ServerPlayer player) {
        if (toldNoModel.add(player.getUUID())) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.no_model").withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    /** How a reply without a model sounds: warm, neutral or cold, by how the colonist feels about the player. */
    static String warmth(int rapport) {
        return rapport >= 20 ? "warm" : rapport <= -20 ? "cold" : "neutral";
    }



    /**
     * The language of a private reply, by code: from the player's message, or for a short one from
     * their message before it; when the code can't tell, the template's own wording leaves it to the
     * model.
     */
    private static String replyLanguage(ICitizenData citizen, ServerPlayer player, Speaker speaker,
                                        List<MemoryLine> history, String message) {
        String locale = player.clientInformation().language();
        List<String> names = citizen.getColony().getCitizenManager().getCitizens().stream().map(ICitizenData::getName).toList();
        Optional<String> before = Optional.empty();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!history.get(i).speaker().equals(speaker.name())) {
                before = Languages.reply(history.get(i).text(), locale, names, Optional.empty());
                break;
            }
        }
        return Languages.reply(message, locale, names, before).orElse("the language of the player's last message");
    }


    /** What the colonist sees of the player if they are close enough: what they hold and wear, how hurt they are. */
    private static String look(ICitizenData citizen, ServerPlayer player) {
        var entity = citizen.getEntity();
        if (entity.isEmpty() || entity.get().level() != player.level() || entity.get().distanceToSqr(player) > LOOK_RANGE * LOOK_RANGE) {
            return "";
        }
        net.minecraft.world.item.ItemStack hand = player.getMainHandItem();
        net.minecraft.world.item.ItemStack chest = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
        return ArrivalPolicy.look(Audience.playerName(player), hand.isEmpty() ? null : ItemNames.of(hand),
                chest.isEmpty() ? null : ItemNames.of(chest), player.getHealth() / player.getMaxHealth());
    }





    static long key(int colonyId, int citizenId) {
        return ((long) colonyId << 32) | (citizenId & 0xFFFFFFFFL);
    }
}

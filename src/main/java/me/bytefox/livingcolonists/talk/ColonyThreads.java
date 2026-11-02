package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.Level;
import me.bytefox.livingcolonists.behaviour.Module;
import me.bytefox.livingcolonists.bonds.Bonds;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.memory.Fact;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.memory.ThreadPolicy;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptBudget;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The colony chat as living conversation threads. A message to the colony opens or continues the
 * colony's thread: whoever it addresses answers first (by name, or by profession through a short
 * routing request); otherwise a colonist picked by talkativeness. After every colonist line the
 * code decides whether someone else speaks, with odds that fade as the thread grows (see
 * {@link ThreadPolicy}); the chosen colonist sees the thread and may stay silent, which ends it.
 *
 * <p>Lines come one at a time, each written after the previous one was said, and go out with a
 * reading and typing pause. A player's message in the middle makes the line being written stale:
 * it is dropped, and the thread answers the player instead. Server thread only.</p>
 */
public final class ColonyThreads {
    /** Colony chat lines shown to the model. */
    private static final int TRANSCRIPT_LINES = 12;
    /** How much of the talk a colonist who just joins in sees. */
    private static final int JOINING_LINES = 4;
    /** Lines from before the conversation began, shown for context. */
    private static final int EARLIER_LINES = 2;
    /** An answer to a player in the colony chat goes twice as slowly as a private reply, so players can read along. */
    private static final double THREAD_PACE = 2;
    /** Colonists talking among themselves go slower still, so a player can get a word in. */
    private static final double CHATTER_PACE = 3;
    /** The most sentences a line in the colony chat keeps. */
    /** Colony chat lines a colonist's own line is checked against, so the chat doesn't go round in circles. */
    private static final int RECENT_LINES = 8;
    private static final int MAX_SENTENCES = 2;
    /** A player who wrote within this many ticks (a minute) is talking with the colony right now. */
    private static final long ACTIVE_TICKS = 60 * 20;
    /** While a player talks, others wait this long (12 seconds) after a line before joining in, so the player can answer first. */
    private static final long INTERJECT_TICKS = 12 * 20;
    /** How many colonists in a row may stay silent on a player's message before the colony lets it be. */
    private static final int MAX_SILENT = 2;
    /** Lines of a talk among colonists that cost nothing; each further one takes {@link #LONG_TALK_COST}. */
    private static final int FREE_LINES = 4;
    private static final double LONG_TALK_COST = 0.25;
    /** A "you" without a name this soon after a colonist's line answers that colonist, in ticks. */
    private static final long REPLY_TICKS = 60 * 20;
    /** A thread with no line for this long (one minute) is over. */
    private static final long IDLE_TICKS = 1200;
    /** A colony brings up an event at most once in this many ticks (five minutes). */
    private static final long EVENT_GAP_TICKS = 6000;
    /** At most this many colonists answer one message addressed to several. */
    private static final int MAX_ADDRESSED = 3;

    private final TalkService talk;
    private final PromptLibrary prompts;
    private final MemoryService memory;
    private final PersonalityService personalities;
    private final SpeechOutput output;
    private final Typing typing;
    private final BondsService bonds;
    private final Random random = me.bytefox.livingcolonists.Dice.random();
    private final SpeakerChoice speakers;
    private final JobNames jobNames = new JobNames();
    private long currentTick;
    private final Map<Integer, ChatThread> threads = new HashMap<>();
    /** Colonists who don't join conversations unless named, until a server tick: newborns. Key as {@link TalkService#key}. */
    private final Map<Long, Long> hushed = new HashMap<>();
    private final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>(Comparator.comparingLong(Scheduled::tick));

    private record Scheduled(long tick, Runnable action) {
    }

    ColonyThreads(TalkService talk, PromptLibrary prompts, MemoryService memory, PersonalityService personalities,
                  SpeechOutput output, Typing typing, BondsService bonds) {
        this.talk = talk;
        this.prompts = prompts;
        this.memory = memory;
        this.personalities = personalities;
        this.output = output;
        this.typing = typing;
        this.bonds = bonds;
        this.speakers = new SpeakerChoice(memory, personalities, bonds, this::isHushed);
    }

    public void clear() {
        threads.clear();
        hushed.clear();
        scheduled.clear();
    }

    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        currentTick = now;
        while (!scheduled.isEmpty() && scheduled.peek().tick() <= now) {
            scheduled.poll().action().run();
        }
        expire(server, now);
    }

    /** The colony's recent chat, for {@code /lcdebug thread}. */
    public List<MemoryLine> transcript(IColony colony) {
        return memory.colonyChat(colony);
    }

    /** Stops the colony's conversation: lines being written or waiting to go out are dropped. */
    public boolean stop(IColony colony) {
        ChatThread thread = threads.remove(colony.getID());
        if (thread == null) {
            return false;
        }
        thread.version++;
        thread.addressed.clear();
        thread.askedByPlayer.clear();
        typing.stopColony(colony.getWorld().getServer(), colony.getID());
        return true;
    }

    public String state(IColony colony) {
        ChatThread thread = threads.get(colony.getID());
        return thread == null ? "no thread yet" : "colonist turns since a player spoke: " + thread.colonistTurns
                + ", participants: " + thread.participants.size() + ", addressed queue: " + thread.addressed.size();
    }

    /**
     * A colony event opens a conversation: one of {@code preferred} (or anyone) brings it up, and the
     * thread goes on as usual. Only with players around, not over a conversation that's going on,
     * and not more often than every few minutes. Returns whether it started.
     *
     * @param what the event in English, like "Anna Becker has died."
     */
    /**
     * A colonist starts a conversation in the colony chat on their own (#5), about {@code about}.
     * Only with players around and nothing going on in the chat. Returns whether it started.
     */
    private final Catchphrases catchphrases = new Catchphrases();

    /** Below this energy a talk is winding down: one with an ending gets it now. */
    private static final double ENDING_ENERGY = 1.5;

    boolean startChatter(MinecraftServer server, IColony colony, int speakerId, String about, int askedId,
                         Airtime.Kind kind) {
        return startChatter(server, colony, speakerId, about, askedId, kind, 0);
    }

    /** The same, about something that matters {@code interest} (0-3) to the colony, so it runs longer (#65). */
    boolean startChatter(MinecraftServer server, IColony colony, int speakerId, String about, int askedId,
                         Airtime.Kind kind, int interest) {
        if (!talk.active(colony)) {
            return false;
        }
        Collection<ServerPlayer> audience = Audience.colonyAudience(server, colony, null);
        Level level = Config.level(Module.CONVERSATION);
        if (level == Level.OFF || audience.isEmpty()) {
            return false;
        }
        long now = server.getTickCount();
        ChatThread existing = threads.get(colony.getID());
        if (existing != null && now - existing.lastLineTick < IDLE_TICKS
                || existing != null && existing.lastPlayer == null
                && now - existing.lastLineTick < ThreadPolicy.pauseAfterTalk(level)
                || !talk.airtime().allows(kind, Airtime.colony(colony.getID()), now, TalkService.airtimeLevel())) {
            return false;
        }
        talk.airtime().spend(Airtime.colony(colony.getID()), now);
        Counters.add("said." + kind.key());
        ChatThread thread = threads.computeIfAbsent(colony.getID(), id -> new ChatThread());
        thread.version++;
        typing.stopColony(server, colony.getID());
        thread.startTime = colony.getWorld().getGameTime();
        thread.colonistTurns = 0;
        thread.addressed.clear();
        thread.askedByPlayer.clear();
        thread.participants.clear();
        thread.topics.clear();
        thread.lines.clear();
        thread.lastSpeaker = -1;
        thread.lastPlayer = null;
        thread.lastLineTick = now;
        thread.opening = null;
        thread.chatter = about;
        thread.energy = ThreadPolicy.startEnergy(level, random) + ThreadPolicy.interestEnergy(interest);
        // A shape by weighted dice, an ordinary chat most of the time; now and then an ending (#65).
        thread.shape = interest > 0 ? null : TalkShapes.pick(random);
        thread.ending = TalkShapes.ending(random);
        if (thread.shape != null) {
            Counters.add("talk.shape." + thread.shape.key());
        }
        thread.language = Languages.ofLocale(Audience.language(audience));
        thread.addressed.add(speakerId);
        thread.asked = askedId;
        nextTurn(server, colony, thread, thread.version);
        return true;
    }

    /**
     * Someone for a colonist to turn to by name (see {@link SpeakerChoice#pickAddressee}).
     */
    Optional<ICitizenData> pickAddressee(IColony colony, ICitizenData speaker) {
        return speakers.pickAddressee(colony, speaker);
    }

    double contrariness(ICitizenData citizen) {
        return speakers.contrariness(citizen);
    }

    double talkativeness(ICitizenData citizen) {
        return speakers.talkativeness(citizen);
    }

    /** These colonists join conversations only when named, for {@code ticks}: a newborn doesn't chat. */
    public void hush(IColony colony, List<Integer> citizenIds, long untilTick) {
        citizenIds.forEach(id -> hushed.put(TalkService.key(colony.getID(), id), untilTick));
        hushed.values().removeIf(until -> until < currentTick);
    }

    /**
     * Opens a colony conversation about an event, if the chat is free. An urgent one (a death, a raid)
     * cuts into colonists' own chatter and skips the gap between events, but never into a talk with a player.
     */
    public boolean startEvent(MinecraftServer server, IColony colony, String what, List<Integer> preferred, boolean urgent,
                              int interest) {
        if (!talk.active(colony)) {
            return false;
        }
        Collection<ServerPlayer> audience = Audience.colonyAudience(server, colony, null);
        if (Config.level(Module.CONVERSATION) == Level.OFF || audience.isEmpty()) {
            return false;
        }
        long now = server.getTickCount();
        ChatThread existing = threads.get(colony.getID());
        if (existing != null) {
            // A raid or a death waits only for the answer to the player's last message, not for the whole talk.
            boolean playerWaiting = now - existing.playerTick < ACTIVE_TICKS && existing.answeredTick < existing.playerTick;
            boolean talking = now - existing.lastLineTick < IDLE_TICKS;
            if (talking && (!urgent || playerWaiting) || !urgent && now - existing.lastEventTick < EVENT_GAP_TICKS) {
                return false;
            }
        }
        Airtime.Kind kind = urgent ? Airtime.Kind.URGENT : Airtime.Kind.NEWS;
        if (!talk.airtime().allows(kind, Airtime.colony(colony.getID()), now, TalkService.airtimeLevel())) {
            return false;
        }
        talk.airtime().spend(Airtime.colony(colony.getID()), now);
        Counters.add("said." + kind.key());
        ChatThread thread = threads.computeIfAbsent(colony.getID(), id -> new ChatThread());
        thread.version++;
        typing.stopColony(server, colony.getID());
        thread.startTime = colony.getWorld().getGameTime();
        thread.colonistTurns = 0;
        thread.addressed.clear();
        thread.askedByPlayer.clear();
        thread.participants.clear();
        thread.lastSpeaker = -1;
        thread.lastPlayer = null;
        thread.lastLineTick = now;
        thread.lastEventTick = now;
        thread.opening = what;
        thread.chatter = null;
        thread.shape = null;
        thread.ending = null;
        thread.lines.clear();
        // What matters to the colony is talked about longer: a raid or a death, not a new site (#65).
        thread.energy = 1 + ThreadPolicy.startEnergy(Config.level(Module.CONVERSATION), random) + ThreadPolicy.interestEnergy(interest);
        thread.language = Languages.ofLocale(Audience.language(audience));
        preferred.stream().limit(1).forEach(thread.addressed::add);
        ModLog.action("Colony {} talks about: {}", colony.getName(), what);
        nextTurn(server, colony, thread, thread.version);
        return true;
    }

    void onPlayerMessage(ServerPlayer player, IColony colony, String message) {
        if (talk.errands().answer(player, message)) {
            return;
        }
        MinecraftServer server = player.server;
        output.playerSaid(player, colony.getName(), message, Audience.colonyAudience(server, colony, player));
        memory.seen(player.getUUID(), Audience.playerName(player), player.getGameProfile().getName());
        memory.rememberColony(colony, Audience.playerName(player), message);
        ChatThread thread = threads.computeIfAbsent(colony.getID(), id -> {
            ChatThread fresh = new ChatThread();
            fresh.startTime = colony.getWorld().getGameTime();
            return fresh;
        });
        thread.version++;
        typing.stopColony(server, colony.getID());
        thread.colonistTurns = 0;
        // The player's message comes before a colony event about to be told: left set, the next colonist would
        // open the event and answer the player without the functions, so "everyone go home" did nothing (0.3.3).
        thread.opening = null;
        thread.silent = 0;
        thread.energy = ThreadPolicy.startEnergy(Config.level(Module.CONVERSATION), random);
        thread.lines.clear();
        thread.chatter = null;
        thread.addressed.clear();
        thread.askedByPlayer.clear();
        int previousSpeaker = thread.lastSpeaker;
        long previousLine = thread.lastLineTick;
        thread.lastSpeaker = -1;
        thread.lastPlayer = player.getUUID();
        thread.playerTick = server.getTickCount();
        thread.playerGameTime = colony.getWorld().getGameTime();
        thread.lastLineTick = server.getTickCount();
        long version = thread.version;

        List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
        if (citizens.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.nobody"));
            return;
        }
        String locale = player.clientInformation().language();
        List<String> names = citizens.stream().map(ICitizenData::getName).toList();
        thread.language = Languages.reply(message, locale, names, Optional.ofNullable(thread.language)).orElse(null);
        int justSpoke = -1;
        if (previousSpeaker >= 0 && server.getTickCount() - previousLine <= REPLY_TICKS) {
            for (int i = 0; i < citizens.size(); i++) {
                if (citizens.get(i).getId() == previousSpeaker) {
                    justSpoke = i;
                }
            }
        }
        Optional<List<Integer>> byCode = MessageRouting.byCode(message, names,
                citizens.stream().map(citizen -> jobNames.of(citizen, locale)).toList(), justSpoke, MAX_ADDRESSED, random);
        if (byCode.isPresent()) {
            List<Integer> picked = byCode.get();
            picked.forEach(index -> thread.addressed.add(citizens.get(index).getId()));
            thread.askedByPlayer.addAll(thread.addressed);
            ModLog.action("Colony {} message is for {}, found by code; reply in {}", colony.getName(),
                    picked.isEmpty() ? "everyone" : picked.stream().map(names::get).toList(), thread.language);
            nextTurn(server, colony, thread, version);
            return;
        }
        route(server, colony, thread, version, citizens, message);
    }

    /** Asks the model whom the message addresses, then lets the first of them answer. */
    private void route(MinecraftServer server, IColony colony, ChatThread thread, long version, List<ICitizenData> citizens,
                       String message) {
        var settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            return;
        }
        List<ColonyRouter.Member> roster = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (ICitizenData citizen : citizens) {
            Speaker speaker = Audience.speakerOf(citizen);
            roster.add(new ColonyRouter.Member(speaker.name(), speaker.job()));
            ids.add(citizen.getId());
        }
        talkClient().complete(settings.get().quick(), ColonyRouter.prompt(prompts.get(PromptLibrary.ROUTE_COLONY), roster, message))
                .thenAccept(result -> server.execute(() -> {
                    if (thread.version != version) {
                        return;
                    }
                    if (result instanceof ChatResult.Reply reply) {
                        List<Integer> picked = ColonyRouter.parse(reply.text(), ids.size()).stream().distinct()
                                .limit(MAX_ADDRESSED).toList();
                        // What matters to them is talked about longer (#65).
                        thread.energy += ThreadPolicy.interestEnergy(ColonyRouter.interest(reply.text()));
                        picked.forEach(index -> thread.addressed.add(ids.get(index)));
                        thread.askedByPlayer.addAll(thread.addressed);
                        if (picked.isEmpty() && !Addressing.toEveryone(message) && ids.contains(thread.lastAnswerer)) {
                            // "And the lime one?" goes on with whoever answered the last question.
                            thread.addressed.add(thread.lastAnswerer);
                        }
                        ModLog.action("Colony {} message is for {}, found by the model; reply in {}",
                                colony.getName(), picked.isEmpty() ? "everyone"
                                        : picked.stream().map(index -> roster.get(index).name()).toList(), thread.language);
                    } else if (result instanceof ChatResult.Failure failure) {
                        ModLog.warn("Could not work out whom a colony message addresses: {}", failure.reason());
                    }
                    nextTurn(server, colony, thread, version);
                }));
    }

    private LlmClient talkClient() {
        return talk.client();
    }

    /** Gives the floor to the next speaker: an addressed colonist, or one picked by the odds. */
    private void nextTurn(MinecraftServer server, IColony colony, ChatThread thread, long version) {
        Integer next = thread.addressed.poll();
        if (next == null) {
            next = speakers.pickSpeaker(colony, thread).orElse(null);
        }
        if (next == null) {
            return;
        }
        ICitizenData citizen = colony.getCitizenManager().getCivilian(next);
        if (citizen == null) {
            return;
        }
        String opening = thread.opening;
        thread.opening = null;
        String chatter = thread.chatter;
        thread.chatter = null;
        // Not only the first: "the unemployed, come to me" is for each of them, and each needs the functions to come.
        boolean answeringPlayer = opening == null && chatter == null
                && (thread.askedByPlayer.remove(next) | thread.colonistTurns == 0);
        // Catchphrases (#65): a set reply waiting for its turn, or now and then a set phrase, both without the model.
        if (!answeringPlayer && opening == null && chatter == null && setLine(server, colony, thread, version, citizen)) {
            return;
        }
        ServerPlayer player = thread.lastPlayer == null ? null : server.getPlayerList().getPlayer(thread.lastPlayer);
        Map<String, String> values = talk.promptValues(citizen, player);
        // A colonist starting a chat of their own starts fresh: lines of an old talk only pull them back to it.
        values.put("transcript", transcriptText(colony, thread, answeringPlayer || opening != null
                || chatter == null && namedLast(colony, citizen)));
        values.put("language", thread.language != null ? thread.language : "the language of the latest chat lines");
        // Who of the players is here: otherwise "hi, Max!" from one player to another gets "Max isn't here".
        List<String> here = Audience.colonyAudience(server, colony, null).stream().map(Audience::playerName).toList();
        if (!here.isEmpty()) {
            values.put("situation", values.get("situation") + "\nIn the colony now, besides colonists: "
                    + String.join(", ", here) + ". They may greet or call each other in the chat.");
        }
        // A talk about enemies gets where they are now: a minute later they may be dead (#62).
        String said = values.get("transcript") + " " + values.get("situation") + " " + (opening == null ? "" : opening);
        if (said.contains("Enemies in the colony") || said.contains("where_enemies") || said.contains("last raiders")) {
            values.put("situation", values.get("situation") + "\n" + me.bytefox.livingcolonists.perception.Enemies.now(colony));
        }
        // Remembered facts this talk touches: about whoever is in it, or sharing words with what was said.
        Set<String> present = new HashSet<>();
        present.add(Fact.aboutCitizen(citizen.getId()));
        thread.participants.forEach(id -> present.add(Fact.aboutCitizen(id)));
        if (player != null) {
            present.add(Fact.aboutPlayer(player.getUUID()));
        }
        String along = bonds.colonists().colonistBlock(citizen, chatPeople(colony, thread));
        if (!along.isEmpty()) {
            values.put("bonds", values.get("bonds").isEmpty() ? along : values.get("bonds") + "\n" + along);
        }
        values.put("turn", answeringPlayer
                ? values.get("player") + " just wrote to the colony chat, and you answer them: don't answer [silence]."
                : "Join the conversation: answer what was said last, or add something of your own. Don't repeat what "
                        + "others already said. People chat about more than their troubles: unless the talk is about the "
                        + "colony's problems, don't steer back to them; if you bring up something new, keep it light. "
                        + "Don't come back to a joke or a topic that has already come up several times: move on. "
                        + "What someone said about the colony's stock, needs, buildings or orders is a fact: you may "
                        + "feel something about it, but don't argue with it or correct it.");
        if (opening != null) {
            values.put("turn", "Something just happened in the colony: " + opening + " Bring it up in the colony chat "
                    + "in your own way, as someone who just heard of it. Write in " + thread.language + ".");
        }
        if (chatter != null) {
            values.put("turn", "You start a conversation in the colony chat on your own, " + chatter + " Keep it light "
                    + "and short; it's fine if nobody answers. Write in " + thread.language + "."
                    + (thread.shape == null ? "" : " " + thread.shape.opening()));
        }
        thread.disagreed = false;
        Optional<Fact> rumor = Optional.empty();
        if (!answeringPlayer && opening == null && chatter == null) {
            values.put("initiative", Prompts.chatInitiative(random));
            String stance = Prompts.stance(Bonds.contrarinessWith(contrariness(citizen),
                    bonds.colonists().rapport(colony, citizen.getId(), thread.lastSpeaker)), thread.colonistTurns, random, thread.topics);
            String topic = Prompts.topicOf(stance);
            if (topic != null) {
                thread.topics.add(topic);
            }
            if (stance.startsWith("Ask someone")) {
                // Code picks whom to turn to: a friend or a neighbour likelier, nobody ruled out.
                Optional<ICitizenData> other = pickAddressee(colony, citizen);
                if (other.isPresent()) {
                    stance = "Ask " + other.get().getName() + " something, by name.";
                    thread.asked = other.get().getId();
                }
            }
            thread.disagreed = stance.contains("don't agree");
            values.put("turn", values.get("turn") + " " + stance);
            if (thread.shape != null) {
                values.put("turn", values.get("turn") + " " + thread.shape.others());
            }
            // The few talks that come to an ending get it as they wind down (#65).
            if (thread.ending != null && thread.energy < ENDING_ENERGY && thread.colonistTurns >= 2) {
                values.put("turn", values.get("turn") + " " + thread.ending);
                thread.ending = null;
            }
            rumor = memory.rumor(citizen, null, random);
            rumor.ifPresent(fact -> values.put("initiative", values.get("initiative") + " " + Prompts.gossip(fact.text())));
        }
        // Nobody opens a talk with a bare reaction or a bare question: the dice shape only the lines after.
        if (opening == null && chatter == null) {
            String form = Prompts.form(random, answeringPlayer);
            if (!form.isEmpty()) {
                values.put("turn", values.get("turn") + " " + form);
            }
        }
        Optional<Fact> gossip = rumor;
        List<MemoryLine> lines = memory.colonyChat(colony);
        // A colonist starting a talk of their own answers nothing: the last line of an old talk would pull them back to it.
        String last = opening != null ? "[Colony news: " + opening + "]"
                : chatter != null ? "[You start a new conversation in the colony chat.]"
                : lines.isEmpty() ? "" : lines.get(lines.size() - 1).speaker() + ": " + lines.get(lines.size() - 1).text();
        Speaker speaker = Audience.speakerOf(citizen);
        // The colonist first reads what was said, then types: others see "typing" from the end of the reading pause.
        double pace = answeringPlayer ? THREAD_PACE : CHATTER_PACE;
        long readingEnd = thread.lastLineTick + Math.round(pace * LinePacing.gap(random));
        typing.start(server, speaker, Audience.colonyAudience(server, colony, player), readingEnd);
        // The facts the last line touches, by meaning when embeddings are set up; asked during the reading pause.
        memory.memoriesFor(citizen, present, chatter != null ? chatter : last, server, memories -> {
            if (thread.version != version) {
                return;
            }
            values.put("memories", memories);
            String template = prompts.get(PromptLibrary.REPLY_COLONY);
            String prompt = Templates.render(template, PromptBudget.fit(values, template));
            // Colonists' lines only: answering a player in their own words is no repeat.
            Set<String> colonists = colony.getCitizenManager().getCitizens().stream().map(ICitizenData::getName)
                    .collect(Collectors.toSet());
            List<String> recent = lines.stream().skip(Math.max(0, lines.size() - RECENT_LINES))
                    .filter(line -> colonists.contains(line.speaker())).map(MemoryLine::text).toList();
            boolean lookups = !answeringPlayer && thread.questionTo == citizen.getId();
            talk.replies().generateFresh(citizen, prompt, List.of(ChatMessage.user(last)), answeringPlayer ? thread.lastPlayer : null,
                    lookups, server, recent, result -> {
                if (thread.version != version) {
                    return;
                }
                Optional<String> text = Replies.lineOf(speaker, result).map(line -> Prompts.firstSentences(line, MAX_SENTENCES));
                if (text.isEmpty()) {
                    typing.stop(server, speaker);
                    if (answeringPlayer && result instanceof ChatResult.Reply && thread.silent++ < MAX_SILENT) {
                        // Nobody ignores a player: if the one asked has nothing to say, someone else answers.
                        ModLog.action("{} had nothing to say to the player, someone else answers", speaker.name());
                        thread.lastSpeaker = speaker.citizenId();
                        nextTurn(server, colony, thread, version);
                        return;
                    }
                    thread.addressed.clear();
                    thread.askedByPlayer.clear();
                    return;
                }
                gossip.ifPresent(fact -> talk.told(colony.getDimension(), speaker, fact));
                long now = server.getTickCount();
                long at = Math.max(now, readingEnd + Math.round(pace * LinePacing.typingTicks(text.get().length())));
                thread.lastLineTick = at;
                scheduled.add(new Scheduled(at, () -> say(server, colony, thread, version, speaker, text.get(), answeringPlayer)));
            });
        });
    }

    /**
     * Says a catchphrase's set reply, or now and then a catchphrase (#65), as written in the content file: never while the
     * colony panics or grieves. Returns whether the colonist said one, so the model isn't asked for a line.
     */
    private boolean setLine(MinecraftServer server, IColony colony, ChatThread thread, long version, ICitizenData citizen) {
        String text = thread.setReply;
        thread.setReply = null;
        if (text == null) {
            me.bytefox.livingcolonists.mood.Mood mood = talk.moods().leading(colony.getID(), server.getTickCount()).mood();
            if (mood == me.bytefox.livingcolonists.mood.Mood.PANIC || mood == me.bytefox.livingcolonists.mood.Mood.GRIEF) {
                return false;
            }
            String friend = null;
            String enemy = null;
            for (ICitizenData other : colony.getCitizenManager().getCitizens()) {
                if (other.getId() == citizen.getId() || other.isChild()) {
                    continue;
                }
                int rapport = bonds.colonists().rapport(colony, citizen.getId(), other.getId());
                friend = friend == null && rapport >= Storylines.STRONG ? other.getName() : friend;
                enemy = enemy == null && rapport <= -Storylines.STRONG ? other.getName() : enemy;
            }
            String language = thread.language == null ? "English" : thread.language;
            Optional<Catchphrases.Said> said = catchphrases.maybe(colony.getID(), colony.getID() + ":" + citizen.getId(),
                    language, friend, enemy, server.getTickCount(), colony.getWorld().getGameTime(), random);
            if (said.isEmpty()) {
                return false;
            }
            text = said.get().line();
            Counters.add("catchphrases.said");
            ModLog.action("{} says a catchphrase: {}", citizen.getName(), text);
            Optional<String> answer = Catchphrases.answer(said.get(), language, random);
            if (answer.isPresent()) {
                thread.setReply = answer.get();
                String replier = said.get().replier();
                colony.getCitizenManager().getCitizens().stream()
                        .filter(c -> c.getId() != citizen.getId() && !c.isChild() && (replier == null || c.getName().equals(replier)))
                        .map(ICitizenData::getId).findFirst().ifPresent(id -> thread.asked = id);
            }
        }
        Speaker speaker = Audience.speakerOf(citizen);
        String line = text;
        long at = server.getTickCount() + Math.round(CHATTER_PACE * LinePacing.typingTicks(line.length()));
        thread.lastLineTick = at;
        thread.disagreed = false;
        scheduled.add(new Scheduled(at, () -> say(server, colony, thread, version, speaker, line, false)));
        return true;
    }

    private void say(MinecraftServer server, IColony colony, ChatThread thread, long version, Speaker speaker, String text,
                     boolean answeringPlayer) {
        if (thread.version != version) {
            return;
        }
        if (answeringPlayer) {
            thread.lastAnswerer = speaker.citizenId();
            thread.answeredTick = server.getTickCount();
        }
        ServerPlayer author = thread.lastPlayer == null ? null : server.getPlayerList().getPlayer(thread.lastPlayer);
        typing.stop(server, speaker);
        output.colonistSaid(speaker, text, Audience.colonyAudience(server, colony, author));
        talk.errands().afterLine(server, speaker.colonyId(), speaker.citizenId());
        memory.rememberColony(colony, speaker.name(), text);
        thread.lastLineTick = server.getTickCount();
        thread.lastSpeaker = speaker.citizenId();
        thread.participants.add(speaker.citizenId());
        thread.colonistTurns++;
        thread.lines.merge(speaker.citizenId(), 1, Integer::sum);
        // Whoever was asked by name answers next.
        if (thread.asked >= 0 && thread.asked != speaker.citizenId() && !thread.addressed.contains(thread.asked)) {
            thread.addressed.addFirst(thread.asked);
        }
        thread.asked = -1;
        thread.questionTo = -1;
        if (text.contains("?")) {
            List<ICitizenData> named = colony.getCitizenManager().getCitizens().stream()
                    .filter(c -> c.getId() != speaker.citizenId()).toList();
            java.util.OptionalInt asked = Addressing.mentioned(text, named.stream().map(ICitizenData::getName).toList());
            if (asked.isPresent()) {
                int id = named.get(asked.getAsInt()).getId();
                thread.questionTo = id;
                if (!thread.addressed.contains(id)) {
                    thread.addressed.addFirst(id);
                }
            }
        }
        // Questions asked by name could chain forever: the safety stop holds for them too.
        if (thread.colonistTurns >= ThreadPolicy.hardCap(Config.level(Module.CONVERSATION))) {
            thread.addressed.clear();
            thread.askedByPlayer.clear();
            return;
        }
        List<String> others = colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getId() != speaker.citizenId()).map(ICitizenData::getName).toList();
        thread.energy = ThreadPolicy.afterLine(thread.energy, new ThreadPolicy.LineTraits(text.contains("?"),
                Addressing.mentioned(text, others).isPresent(), thread.disagreed, text.split("\\s+").length <= 4),
                thread.colonistTurns, random);
        if (thread.lastPlayer == null && thread.colonistTurns > FREE_LINES) {
            // A long talk among colonists takes from the colony's budget, so the next one comes later.
            talk.airtime().spend(Airtime.colony(colony.getID()), server.getTickCount(), LONG_TALK_COST);
        }
        if (!thread.addressed.isEmpty()) {
            nextTurn(server, colony, thread, version);
            return;
        }
        Level level = Config.level(Module.CONVERSATION);
        boolean playersAround = Audience.colonyAudience(server, colony, null).size() > 0;
        ThreadPolicy.Kind kind = server.getTickCount() - thread.playerTick >= ACTIVE_TICKS ? ThreadPolicy.Kind.CHATTER
                : talk.errands().errandSince(colony.getID(), thread.playerGameTime) ? ThreadPolicy.Kind.BUSINESS
                : ThreadPolicy.Kind.TALK;
        // Among themselves the talk runs on its energy: it may die at once or grow into a long discussion.
        boolean goesOn = kind == ThreadPolicy.Kind.CHATTER
                ? ThreadPolicy.goesOn(thread.energy, thread.colonistTurns, level)
                : random.nextDouble() < ThreadPolicy.continueChance(level, thread.colonistTurns, kind);
        if (!playersAround || !goesOn) {
            return;
        }
        if (kind == ThreadPolicy.Kind.CHATTER) {
            nextTurn(server, colony, thread, version);
            return;
        }
        // The player gets the first word: a message of theirs before the pause ends cancels this.
        scheduled.add(new Scheduled(server.getTickCount() + INTERJECT_TICKS, () -> {
            if (thread.version == version) {
                nextTurn(server, colony, thread, version);
            }
        }));
    }

    /** Colonists who spoke in the thread, and whoever spoke last. */
    private static List<ICitizenData> chatPeople(IColony colony, ChatThread thread) {
        Set<Integer> ids = new HashSet<>(thread.participants);
        ids.add(thread.lastSpeaker);
        return ids.stream().map(id -> colony.getCitizenManager().getCivilian(id)).filter(Objects::nonNull).toList();
    }

    /** Whether the colony chat is in the middle of a conversation: a line within the last minute, or one coming. */
    boolean isBusy(int colonyId, long now) {
        ChatThread thread = threads.get(colonyId);
        return thread != null && (now - thread.lastLineTick < 60 * 20 || !thread.addressed.isEmpty());
    }

    /** A newborn keeping quiet for a while. */
    boolean isHushed(int colonyId, int citizenId) {
        return hushed.getOrDefault(TalkService.key(colonyId, citizenId), Long.MIN_VALUE) > currentTick;
    }

    /**
     * The lines of this conversation, with a couple of earlier ones for context: with the whole
     * day's chat in view, a new conversation kept circling the topics of an old one.
     */
    /** Whether the last line of the chat names this colonist. */
    private boolean namedLast(IColony colony, ICitizenData citizen) {
        List<MemoryLine> lines = memory.colonyChat(colony);
        return !lines.isEmpty() && Addressing.mentioned(lines.get(lines.size() - 1).text(), List.of(citizen.getName())).isPresent();
    }

    /**
     * The chat for the prompt: whoever is spoken to (or answers a player, or opens a talk) gets the
     * whole conversation; others joining in get only its last lines, which is cheaper and keeps them
     * from going back over old ground.
     */
    private String transcriptText(IColony colony, ChatThread thread, boolean full) {
        List<MemoryLine> lines = memory.colonyChat(colony);
        List<MemoryLine> current = lines.stream().filter(line -> line.gameTime() >= thread.startTime).toList();
        List<MemoryLine> earlier = full ? lines.stream().filter(line -> line.gameTime() < thread.startTime).toList() : List.of();
        if (!full) {
            current = current.subList(Math.max(0, current.size() - JOINING_LINES), current.size());
        }
        StringBuilder text = new StringBuilder();
        if (!earlier.isEmpty()) {
            text.append("(earlier)\n");
            earlier.stream().skip(Math.max(0, earlier.size() - EARLIER_LINES))
                    .forEach(line -> text.append('<').append(line.speaker()).append("> ").append(line.text()).append('\n'));
            text.append("(this conversation)\n");
        }
        current.stream().skip(Math.max(0, current.size() - TRANSCRIPT_LINES))
                .forEach(line -> text.append('<').append(line.speaker()).append("> ").append(line.text()).append('\n'));
        // What was looked up lately is in every colonist's situation (#56), not repeated here.
        return text.toString().strip();
    }

    /**
     * Ends threads nobody has spoken in for a while. A line still being written (a request retrying
     * through a network outage can take minutes) becomes stale, or it would revive the ended thread
     * next to a new one.
     */
    private void expire(MinecraftServer server, long now) {
        threads.entrySet().removeIf(entry -> {
            ChatThread thread = entry.getValue();
            boolean over = now - thread.lastLineTick > IDLE_TICKS && thread.addressed.isEmpty();
            if (over) {
                thread.version++;
                typing.stopColony(server, entry.getKey());
            }
            return over;
        });
    }
}

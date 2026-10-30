package me.bytefox.livingcolonists.talk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
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
import java.util.UUID;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
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
import me.bytefox.livingcolonists.personality.Personality;
import me.bytefox.livingcolonists.personality.PersonalityService;
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
    /** Lines from before the conversation began, shown for context. */
    private static final int EARLIER_LINES = 2;
    /** An answer to a player in the colony chat goes twice as slowly as a private reply, so players can read along. */
    private static final double THREAD_PACE = 2;
    /** Colonists talking among themselves go slower still, so a player can get a word in. */
    private static final double CHATTER_PACE = 3;
    /** The most sentences a line in the colony chat keeps. */
    private static final int MAX_SENTENCES = 2;
    /** A player who wrote within this many ticks (a minute) is talking with the colony right now. */
    private static final long ACTIVE_TICKS = 60 * 20;
    /** While a player talks, others wait this long (12 seconds) after a line before joining in, so the player can answer first. */
    private static final long INTERJECT_TICKS = 12 * 20;
    /** How many colonists in a row may stay silent on a player's message before the colony lets it be. */
    private static final int MAX_SILENT = 2;
    /** A thread with no line for this long (one minute) is over. */
    private static final long IDLE_TICKS = 1200;
    /** A colony brings up an event at most once in this many ticks (five minutes). */
    private static final long EVENT_GAP_TICKS = 6000;
    /** At most this many colonists answer one message addressed to several. */
    private static final int MAX_ADDRESSED = 3;
    /** How much likelier a colonist named in the last line, or already talking, is to speak next. */
    private static final double NAMED_BONUS = 6;
    private static final double TALKING_BONUS = 2;
    private static final double FAMILY_BONUS = 1.5;

    private final TalkService talk;
    private final PromptLibrary prompts;
    private final MemoryService memory;
    private final PersonalityService personalities;
    private final SpeechOutput output;
    private final Typing typing;
    private final BondsService bonds;
    private final Random random = new Random();
    private final JobNames jobNames = new JobNames();
    private long currentTick;
    private final Map<Integer, Thread> threads = new HashMap<>();
    /** Colonists who don't join conversations unless named, until a server tick: newborns. Key as {@link TalkService#key}. */
    private final Map<Long, Long> hushed = new HashMap<>();
    private final PriorityQueue<Scheduled> scheduled = new PriorityQueue<>(Comparator.comparingLong(Scheduled::tick));

    private record Scheduled(long tick, Runnable action) {
    }

    /** One colony's conversation. */
    private static final class Thread {
        /** Bumped by every player message; a line written for an older version is stale. */
        long version;
        int colonistTurns;
        int lastSpeaker = -1;
        long lastLineTick;
        UUID lastPlayer;
        final Deque<Integer> addressed = new ArrayDeque<>();
        final Set<Integer> participants = new HashSet<>();
        /** Topics the dice already turned this thread to. */
        final Set<String> topics = new HashSet<>();
        /** A colony event the next line opens the thread with, if any. */
        String opening;
        /** The language the thread is in, like "Russian"; null leaves it to the model. */
        String language;
        long lastEventTick = Long.MIN_VALUE / 2;
        /** World game time the conversation began; earlier chat lines are only context. */
        long startTime;
        /** Who answered the player's last message: a follow-up for nobody in particular goes to them. */
        int lastAnswerer = -1;
        /** Colonists who stayed silent on the player's message so far. */
        int silent;
        /** Server tick and game time of the player's last message. */
        long playerTick = Long.MIN_VALUE / 2;
        long playerGameTime;
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
        Thread thread = threads.remove(colony.getID());
        if (thread == null) {
            return false;
        }
        thread.version++;
        thread.addressed.clear();
        typing.stopColony(colony.getWorld().getServer(), colony.getID());
        return true;
    }

    public String state(IColony colony) {
        Thread thread = threads.get(colony.getID());
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
    /** These colonists join conversations only when named, for {@code ticks}: a newborn doesn't chat. */
    public void hush(IColony colony, List<Integer> citizenIds, long untilTick) {
        citizenIds.forEach(id -> hushed.put(TalkService.key(colony.getID(), id), untilTick));
        hushed.values().removeIf(until -> until < currentTick);
    }

    public boolean startEvent(MinecraftServer server, IColony colony, String what, List<Integer> preferred) {
        Collection<ServerPlayer> audience = TalkService.colonyAudience(server, colony, null);
        if (Config.level(Module.CONVERSATION) == Level.OFF || audience.isEmpty()) {
            return false;
        }
        long now = server.getTickCount();
        Thread existing = threads.get(colony.getID());
        if (existing != null && (now - existing.lastLineTick < IDLE_TICKS || now - existing.lastEventTick < EVENT_GAP_TICKS)) {
            return false;
        }
        Thread thread = threads.computeIfAbsent(colony.getID(), id -> new Thread());
        thread.version++;
        typing.stopColony(server, colony.getID());
        thread.startTime = colony.getWorld().getGameTime();
        thread.colonistTurns = 0;
        thread.addressed.clear();
        thread.participants.clear();
        thread.lastSpeaker = -1;
        thread.lastPlayer = null;
        thread.lastLineTick = now;
        thread.lastEventTick = now;
        thread.opening = what;
        thread.language = Languages.ofLocale(TalkService.language(audience));
        preferred.stream().limit(1).forEach(thread.addressed::add);
        LivingColonists.LOGGER.info("Colony {} talks about: {}", colony.getName(), what);
        nextTurn(server, colony, thread, thread.version);
        return true;
    }

    void onPlayerMessage(ServerPlayer player, IColony colony, String message) {
        if (talk.errands().answer(player, message)) {
            return;
        }
        MinecraftServer server = player.server;
        output.playerSaid(player, colony.getName(), message, TalkService.colonyAudience(server, colony, player));
        memory.rememberColony(colony, TalkService.playerName(player), message);
        Thread thread = threads.computeIfAbsent(colony.getID(), id -> {
            Thread fresh = new Thread();
            fresh.startTime = colony.getWorld().getGameTime();
            return fresh;
        });
        thread.version++;
        typing.stopColony(server, colony.getID());
        thread.colonistTurns = 0;
        thread.silent = 0;
        thread.addressed.clear();
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
        List<Integer> byJob = new ArrayList<>(Addressing.byJob(message,
                citizens.stream().map(citizen -> jobNames.of(citizen, locale)).toList()));
        Collections.shuffle(byJob, random);
        List<Integer> chosen = new ArrayList<>(Addressing.allMentioned(message, names));
        byJob.stream().filter(index -> !chosen.contains(index)).forEach(chosen::add);
        // A question to everyone still goes to the router: it may be about one profession's work.
        if (!chosen.isEmpty() || Addressing.toEveryone(message) && !message.contains("?")) {
            List<Integer> picked = chosen.stream().limit(MAX_ADDRESSED).toList();
            picked.forEach(index -> thread.addressed.add(citizens.get(index).getId()));
            LivingColonists.LOGGER.info("Colony {} message is for {}, found by code; reply in {}", colony.getName(),
                    picked.isEmpty() ? "everyone" : picked.stream().map(names::get).toList(), thread.language);
            nextTurn(server, colony, thread, version);
            return;
        }
        route(server, colony, thread, version, citizens, message);
    }

    /** Asks the model whom the message addresses, then lets the first of them answer. */
    private void route(MinecraftServer server, IColony colony, Thread thread, long version, List<ICitizenData> citizens,
                       String message) {
        var settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            return;
        }
        List<ColonyRouter.Member> roster = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (ICitizenData citizen : citizens) {
            Speaker speaker = TalkService.speakerOf(citizen);
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
                        picked.forEach(index -> thread.addressed.add(ids.get(index)));
                        if (picked.isEmpty() && !Addressing.toEveryone(message) && ids.contains(thread.lastAnswerer)) {
                            // "And the lime one?" goes on with whoever answered the last question.
                            thread.addressed.add(thread.lastAnswerer);
                        }
                        LivingColonists.LOGGER.info("Colony {} message is for {}, found by the model; reply in {}",
                                colony.getName(), picked.isEmpty() ? "everyone"
                                        : picked.stream().map(index -> roster.get(index).name()).toList(), thread.language);
                    } else if (result instanceof ChatResult.Failure failure) {
                        LivingColonists.LOGGER.warn("Could not work out whom a colony message addresses: {}", failure.reason());
                    }
                    nextTurn(server, colony, thread, version);
                }));
    }

    private LlmClient talkClient() {
        return talk.client();
    }

    /** Gives the floor to the next speaker: an addressed colonist, or one picked by the odds. */
    private void nextTurn(MinecraftServer server, IColony colony, Thread thread, long version) {
        Integer next = thread.addressed.poll();
        if (next == null) {
            next = pickSpeaker(colony, thread).orElse(null);
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
        boolean answeringPlayer = thread.colonistTurns == 0 && opening == null;
        ServerPlayer player = thread.lastPlayer == null ? null : server.getPlayerList().getPlayer(thread.lastPlayer);
        Map<String, String> values = talk.promptValues(citizen, player);
        values.put("transcript", transcriptText(colony, thread));
        values.put("language", thread.language != null ? thread.language : "the language of the latest chat lines");
        String along = bonds.colonistBlock(citizen, chatPeople(colony, thread));
        if (!along.isEmpty()) {
            values.put("bonds", values.get("bonds").isEmpty() ? along : values.get("bonds") + "\n" + along);
        }
        values.put("turn", answeringPlayer
                ? values.get("player") + " just wrote to the colony chat, and you answer them: don't answer [silence]."
                : "Join the conversation: answer what was said last, or add something of your own. Don't repeat what "
                        + "others already said. People chat about more than their troubles: unless the talk is about the "
                        + "colony's problems, don't steer back to them; if you bring up something new, keep it light. "
                        + "Don't come back to a joke or a topic that has already come up several times: move on.");
        if (opening != null) {
            values.put("turn", "Something just happened in the colony: " + opening + " Bring it up in the colony chat "
                    + "in your own way, as someone who just heard of it. Write in " + thread.language + ".");
        }
        Optional<Fact> rumor = Optional.empty();
        if (!answeringPlayer && opening == null) {
            values.put("initiative", Prompts.chatInitiative(random));
            String stance = Prompts.stance(Bonds.contrarinessWith(contrariness(citizen),
                    bonds.rapport(colony, citizen.getId(), thread.lastSpeaker)), thread.colonistTurns, random, thread.topics);
            String topic = Prompts.topicOf(stance);
            if (topic != null) {
                thread.topics.add(topic);
            }
            values.put("turn", values.get("turn") + " " + stance);
            rumor = memory.rumor(citizen, null, random);
            rumor.ifPresent(fact -> values.put("initiative", values.get("initiative") + " " + Prompts.gossip(fact.text())));
        }
        if (opening == null) {
            String form = Prompts.form(random, answeringPlayer);
            if (!form.isEmpty()) {
                values.put("turn", values.get("turn") + " " + form);
            }
        }
        Optional<Fact> gossip = rumor;
        String prompt = Templates.render(prompts.get(PromptLibrary.REPLY_COLONY), values);
        List<MemoryLine> lines = memory.colonyChat(colony);
        String last = opening != null ? "[Colony news: " + opening + "]"
                : lines.isEmpty() ? "" : lines.get(lines.size() - 1).speaker() + ": " + lines.get(lines.size() - 1).text();
        Speaker speaker = TalkService.speakerOf(citizen);
        // The colonist first reads what was said, then types: others see "typing" from the end of the reading pause.
        double pace = answeringPlayer ? THREAD_PACE : CHATTER_PACE;
        long readingEnd = thread.lastLineTick + Math.round(pace * LinePacing.gap(random));
        typing.start(server, speaker, TalkService.colonyAudience(server, colony, player), readingEnd);
        talk.generate(citizen, prompt, List.of(ChatMessage.user(last)), answeringPlayer ? thread.lastPlayer : null,
                server, result -> {
            if (thread.version != version) {
                return;
            }
            Optional<String> text = TalkService.lineOf(speaker, result).map(line -> Prompts.firstSentences(line, MAX_SENTENCES));
            if (text.isEmpty()) {
                typing.stop(server, speaker);
                if (answeringPlayer && result instanceof ChatResult.Reply && thread.silent++ < MAX_SILENT) {
                    // Nobody ignores a player: if the one asked has nothing to say, someone else answers.
                    LivingColonists.LOGGER.info("{} had nothing to say to the player, someone else answers", speaker.name());
                    thread.lastSpeaker = speaker.citizenId();
                    nextTurn(server, colony, thread, version);
                    return;
                }
                thread.addressed.clear();
                return;
            }
            gossip.ifPresent(fact -> talk.told(colony.getDimension(), speaker, fact));
            long now = server.getTickCount();
            long at = Math.max(now, readingEnd + Math.round(pace * LinePacing.typingTicks(text.get().length())));
            thread.lastLineTick = at;
            scheduled.add(new Scheduled(at, () -> say(server, colony, thread, version, speaker, text.get(), answeringPlayer)));
        });
    }

    private void say(MinecraftServer server, IColony colony, Thread thread, long version, Speaker speaker, String text,
                     boolean answeringPlayer) {
        if (thread.version != version) {
            return;
        }
        if (answeringPlayer) {
            thread.lastAnswerer = speaker.citizenId();
        }
        ServerPlayer author = thread.lastPlayer == null ? null : server.getPlayerList().getPlayer(thread.lastPlayer);
        typing.stop(server, speaker);
        output.colonistSaid(speaker, text, TalkService.colonyAudience(server, colony, author));
        talk.errands().afterLine(server, speaker.colonyId(), speaker.citizenId());
        memory.rememberColony(colony, speaker.name(), text);
        thread.lastLineTick = server.getTickCount();
        thread.lastSpeaker = speaker.citizenId();
        thread.participants.add(speaker.citizenId());
        thread.colonistTurns++;
        if (!thread.addressed.isEmpty()) {
            nextTurn(server, colony, thread, version);
            return;
        }
        Level level = Config.level(Module.CONVERSATION);
        boolean playersAround = TalkService.colonyAudience(server, colony, null).size() > 0;
        ThreadPolicy.Kind kind = server.getTickCount() - thread.playerTick >= ACTIVE_TICKS ? ThreadPolicy.Kind.CHATTER
                : talk.errands().errandSince(colony.getID(), thread.playerGameTime) ? ThreadPolicy.Kind.BUSINESS
                : ThreadPolicy.Kind.TALK;
        if (!playersAround || random.nextDouble() >= ThreadPolicy.continueChance(level, thread.colonistTurns, kind)) {
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

    /**
     * Who speaks next, never the one who just did: talkative archetypes more often, much more so a
     * colonist named in the last line, and those already in the thread or family of the last speaker.
     */
    private Optional<Integer> pickSpeaker(IColony colony, Thread thread) {
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens();
        List<MemoryLine> lines = memory.colonyChat(colony);
        String lastText = lines.isEmpty() ? "" : lines.get(lines.size() - 1).text();
        OptionalInt named = Addressing.mentioned(lastText, citizens.stream().map(ICitizenData::getName).toList());
        Set<Integer> family = family(colony.getCitizenManager().getCivilian(thread.lastSpeaker));
        List<ThreadPolicy.Candidate<Integer>> candidates = new ArrayList<>();
        for (int i = 0; i < citizens.size(); i++) {
            ICitizenData citizen = citizens.get(i);
            double weight = talkativeness(citizen) * Bonds.replyWeight(bonds.rapport(colony, citizen.getId(), thread.lastSpeaker));
            if (hushed.getOrDefault(TalkService.key(colony.getID(), citizen.getId()), Long.MIN_VALUE) > currentTick
                    && !(named.isPresent() && named.getAsInt() == i)) {
                continue;
            }
            if (named.isPresent() && named.getAsInt() == i) {
                weight *= NAMED_BONUS;
            }
            if (thread.participants.contains(citizen.getId())) {
                weight *= TALKING_BONUS;
            }
            if (family.contains(citizen.getId())) {
                weight *= FAMILY_BONUS;
            }
            candidates.add(new ThreadPolicy.Candidate<>(citizen.getId(), weight));
        }
        return ThreadPolicy.pickSpeaker(candidates, thread.lastSpeaker, random);
    }

    /** Colonists who spoke in the thread, and whoever spoke last. */
    private static List<ICitizenData> chatPeople(IColony colony, Thread thread) {
        Set<Integer> ids = new HashSet<>(thread.participants);
        ids.add(thread.lastSpeaker);
        return ids.stream().map(id -> colony.getCitizenManager().getCivilian(id)).filter(Objects::nonNull).toList();
    }

    private double contrariness(ICitizenData citizen) {
        Personality personality = personalities.get(citizen);
        return personality == null ? 0.35 : personalities.archetypes().get(personality.archetype()).contrariness();
    }

    /** Whether the colony chat is in the middle of a conversation: a line within the last minute, or one coming. */
    boolean isBusy(int colonyId, long now) {
        Thread thread = threads.get(colonyId);
        return thread != null && (now - thread.lastLineTick < 60 * 20 || !thread.addressed.isEmpty());
    }

    /** A newborn keeping quiet for a while. */
    boolean isHushed(int colonyId, int citizenId) {
        return hushed.getOrDefault(TalkService.key(colonyId, citizenId), Long.MIN_VALUE) > currentTick;
    }

    double talkativeness(ICitizenData citizen) {
        Personality personality = personalities.get(citizen);
        double value = personality == null ? 0.5 : personalities.archetypes().get(personality.archetype()).talkativeness();
        return Math.max(0.05, value);
    }

    private static Set<Integer> family(ICitizenData citizen) {
        Set<Integer> family = new HashSet<>();
        if (citizen == null) {
            return family;
        }
        if (citizen.getPartner() != null) {
            family.add(citizen.getPartner().getId());
        }
        if (citizen.getChildren() != null) {
            family.addAll(citizen.getChildren());
        }
        if (citizen.getSiblings() != null) {
            family.addAll(citizen.getSiblings());
        }
        return family;
    }

    /**
     * The lines of this conversation, with a couple of earlier ones for context: with the whole
     * day's chat in view, a new conversation kept circling the topics of an old one.
     */
    private String transcriptText(IColony colony, Thread thread) {
        List<MemoryLine> lines = memory.colonyChat(colony);
        List<MemoryLine> current = lines.stream().filter(line -> line.gameTime() >= thread.startTime).toList();
        List<MemoryLine> earlier = lines.stream().filter(line -> line.gameTime() < thread.startTime).toList();
        StringBuilder text = new StringBuilder();
        if (!earlier.isEmpty()) {
            text.append("(earlier)\n");
            earlier.stream().skip(Math.max(0, earlier.size() - EARLIER_LINES))
                    .forEach(line -> text.append('<').append(line.speaker()).append("> ").append(line.text()).append('\n'));
            text.append("(this conversation)\n");
        }
        current.stream().skip(Math.max(0, current.size() - TRANSCRIPT_LINES))
                .forEach(line -> text.append('<').append(line.speaker()).append("> ").append(line.text()).append('\n'));
        List<String> records = talk.errands().lookups(colony.getID(), thread.startTime);
        if (!records.isEmpty()) {
            text.append("(colony records looked up in this conversation, true whatever anyone says)\n");
            records.forEach(record -> text.append("- ").append(record).append('\n'));
        }
        return text.toString().strip();
    }

    /**
     * Ends threads nobody has spoken in for a while. A line still being written (a request retrying
     * through a network outage can take minutes) becomes stale, or it would revive the ended thread
     * next to a new one.
     */
    private void expire(MinecraftServer server, long now) {
        threads.entrySet().removeIf(entry -> {
            Thread thread = entry.getValue();
            boolean over = now - thread.lastLineTick > IDLE_TICKS && thread.addressed.isEmpty();
            if (over) {
                thread.version++;
                typing.stopColony(server, entry.getKey());
            }
            return over;
        });
    }
}

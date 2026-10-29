package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
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
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.memory.Fact;
import me.bytefox.livingcolonists.memory.MemoryLine;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.errands.Errand;
import me.bytefox.livingcolonists.errands.Errands;
import me.bytefox.livingcolonists.perception.ColonyTools;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.prompt.Templates;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
    /** Rounds of colony function calls per reply before the model has to answer. */
    private static final int MAX_TOOL_ROUNDS = 2;
    /**
     * Token room for a reply: the line is short, but DeepSeek's reasoning sometimes took the whole
     * usual 2000 and cut the line off mid-word.
     */
    private static final int REPLY_TOKENS = 4000;
    /** How many of the player's latest lines an order is checked against. */
    private static final int RECENT_PLAYER_LINES = 6;
    /** How far away a colonist can be addressed with the command, in blocks. */
    private static final double TALK_RANGE = 8.0;
    /** How far from the line of sight a colonist still counts as looked at. */
    private static final double TARGET_RADIUS = 0.8;

    private final LlmClient client;
    private final PromptLibrary prompts;
    private final PersonalityService personalities;
    private final PerceptionService perception;
    private final MemoryService memory;
    private final BondsService bonds;
    private final ColonyTools tools;
    private final Errands errands;
    private final ColonyThreads threads;
    private final ColonyEvents events;
    private final SpeechOutput output = new ChatSpeechOutput();
    private final Typing typing = new Typing();
    private final Random random = new Random();

    /** The last reply prompt of each colonist, as {@link #key}, for {@code /lcdebug prompt}. Server thread only. */
    private final Map<Long, String> lastPrompts = new HashMap<>();
    /** Pause between one player's messages to colonists. Server thread only. */
    private final Cooldown cooldown = new Cooldown();
    /** Optional cap on one player's messages to colonists per hour. Server thread only. */
    private final HourlyLimit hourly = new HourlyLimit();
    /** Colonists waiting for a private reply, as {@link #key}. Server thread only. */
    private final Set<Long> thinking = new HashSet<>();
    /** Private replies waiting for their typing pause (see {@link LinePacing}). Server thread only. */
    private final PriorityQueue<PendingLine> pending = new PriorityQueue<>(Comparator.comparingLong(PendingLine::tick));

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
        this.errands = new Errands(perception);
        this.threads = new ColonyThreads(this, prompts, memory, personalities, output, typing, bonds);
        this.events = new ColonyEvents(threads);
    }

    public ColonyThreads threads() {
        return threads;
    }

    public Errands errands() {
        return errands;
    }

    LlmClient client() {
        return client;
    }

    /** Sends private replies whose turn has come and moves colony threads on. Server tick. */
    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        while (!pending.isEmpty() && pending.peek().tick() <= now) {
            PendingLine line = pending.poll();
            ICitizenData citizen = resolve(line.dimension(), line.speaker().colonyId(), line.speaker().citizenId());
            ServerPlayer player = server.getPlayerList().getPlayer(line.playerId());
            typing.stop(server, line.speaker());
            if (citizen != null && player != null) {
                output.colonistWhispered(line.speaker(), player, line.text());
                memory.rememberPrivate(citizen, player.getUUID(), line.speaker().name(), line.text());
            }
        }
        threads.tick(server);
        events.tick(server);
        typing.tick(server);
    }

    /** The last prompt sent for this colonist's reply, if any. */
    public Optional<String> lastPrompt(ICitizenData citizen) {
        return Optional.ofNullable(lastPrompts.get(key(citizen.getColony().getID(), citizen.getId())));
    }

    public void clear() {
        lastPrompts.clear();
        cooldown.clear();
        hourly.clear();
        thinking.clear();
        pending.clear();
        threads.clear();
        events.clear();
        typing.clear();
        errands.clear();
    }

    /** {@code /lc talk}: a private word with the colonist under the crosshair or the nearest one. */
    public int talk(ServerPlayer player, String message) {
        Optional<AbstractEntityCitizen> listener = findListener(player, TALK_RANGE, false);
        if (listener.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.nobody"));
            return 0;
        }
        sayPrivately(player, listener.get().getCitizenData(), message);
        return 1;
    }

    /** A player's private message to one colonist; the colonist remembers the talk. */
    public void sayPrivately(ServerPlayer player, ICitizenData citizen, String message) {
        if (errands.answer(player, message)) {
            return;
        }
        if (!admit(player, citizen.getColony()) || settings(player).isEmpty()) {
            return;
        }
        Speaker speaker = speakerOf(citizen);
        long key = key(speaker.colonyId(), speaker.citizenId());
        if (thinking.contains(key)) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.busy", speaker.name()));
            return;
        }
        output.playerWhispered(player, speaker, message);
        List<MemoryLine> history = memory.privateTalk(citizen, player.getUUID());
        String playerName = playerName(player);
        memory.rememberPrivate(citizen, player.getUUID(), playerName, message);

        List<ChatMessage> messages = new ArrayList<>();
        for (MemoryLine line : history) {
            messages.add(line.speaker().equals(speaker.name()) ? ChatMessage.assistant(line.text()) : ChatMessage.user(line.text()));
        }
        messages.add(ChatMessage.user(message));
        Map<String, String> values = promptValues(citizen, player);
        values.put("language", replyLanguage(citizen, player, speaker, history, message));
        Optional<Fact> rumor = memory.rumor(citizen, playerName, random);
        rumor.ifPresent(fact -> values.put("initiative", values.get("initiative") + " " + Prompts.gossip(fact.text())));
        String prompt = Templates.render(prompts.get(PromptLibrary.REPLY_PRIVATE), values);

        thinking.add(key);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = citizen.getColony().getDimension();
        long earliest = server.getTickCount() + LinePacing.gap(random);
        typing.start(server, speaker, List.of(player), earliest);
        generate(citizen, prompt, messages, playerId, server, result -> {
            thinking.remove(key);
            ServerPlayer author = server.getPlayerList().getPlayer(playerId);
            Optional<String> text = lineOf(speaker, result);
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
    }

    /**
     * A colonist writes first to remind a player of an overdue promise, in private, while the player
     * is in the colony. Returns whether the reminder went ahead.
     */
    public boolean remindPromise(ServerPlayer player, ICitizenData citizen, Promise promise) {
        IColony colony = citizen.getColony();
        Speaker speaker = speakerOf(citizen);
        long key = key(speaker.colonyId(), speaker.citizenId());
        if (Config.level(Module.CONVERSATION) == me.bytefox.livingcolonists.behaviour.Level.OFF
                || !isInside(player, colony) || thinking.contains(key) || client.overDailyLimit()
                || Config.llmSettings(ModelRole.CHAT).isEmpty()) {
            return false;
        }
        String playerName = playerName(player);
        List<ChatMessage> messages = new ArrayList<>();
        for (MemoryLine line : memory.privateTalk(citizen, player.getUUID())) {
            messages.add(line.speaker().equals(speaker.name()) ? ChatMessage.assistant(line.text()) : ChatMessage.user(line.text()));
        }
        messages.add(ChatMessage.user("[" + playerName + " is in the colony. You write to them first.]"));
        Map<String, String> values = promptValues(citizen, player);
        values.put("initiative", "You write to " + playerName + " first, unprompted, to remind them of a promise that is "
                + "overdue now: \"" + promise.text() + "\". Say it in your own way and mood, in one or two sentences. "
                + "Write in " + Languages.ofLocale(player.clientInformation().language()) + ".");
        values.put("language", Languages.ofLocale(player.clientInformation().language()));
        String prompt = Templates.render(prompts.get(PromptLibrary.REPLY_PRIVATE), values);
        thinking.add(key);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = colony.getDimension();
        long earliest = server.getTickCount() + LinePacing.gap(random);
        typing.start(server, speaker, List.of(player), earliest);
        generate(citizen, prompt, messages, null, server, result -> {
            thinking.remove(key);
            Optional<String> text = lineOf(speaker, result);
            if (text.isEmpty()) {
                typing.stop(server, speaker);
                return;
            }
            long at = LinePacing.slot(server.getTickCount(), earliest + LinePacing.typingTicks(text.get().length()), 0);
            pending.add(new PendingLine(at, dimension, speaker, playerId, text.get()));
        });
        return true;
    }

    /** The colonist passed a fact on as gossip, so they won't retell it. */
    void told(ResourceKey<Level> dimension, Speaker speaker, Fact fact) {
        ICitizenData citizen = resolve(dimension, speaker.colonyId(), speaker.citizenId());
        if (citizen != null) {
            memory.markTold(citizen, fact);
        }
    }

    /** A message to the whole colony goes into its conversation thread. */
    public void sayToColony(ServerPlayer player, IColony colony, String message) {
        if (admit(player, colony) && settings(player).isPresent()) {
            threads.onPlayerMessage(player, colony, message);
        }
    }

    /**
     * Values for reply templates: who the colonist is, what they know, and how to treat the player.
     *
     * @param player the player being answered; null when colonists talk among themselves
     */
    Map<String, String> promptValues(ICitizenData citizen, ServerPlayer player) {
        Speaker speaker = speakerOf(citizen);
        MinecraftServer server = citizen.getColony().getWorld().getServer();
        Map<String, String> values = new HashMap<>(personalities.promptValues(citizen, server));
        values.put("name", speaker.name());
        if (citizen.isChild()) {
            values.put("character", (values.getOrDefault("character", "") + "\nYou are still a child: talk like one, "
                    + "simply and in short sentences, about the things children care about.").strip());
        }
        values.put("job", speaker.job());
        values.put("colony", speaker.colony());
        values.put("time", Prompts.timeOfDay(citizen.getColony().getWorld().getDayTime()));
        values.put("mood", Prompts.mood(citizen.getId(), citizen.getColony().getWorld().getDayTime() / 24000,
                happiness(citizen)));
        values.put("situation", perception.situation(citizen));
        values.put("memories", memory.memories(citizen));
        values.put("initiative", Prompts.initiative(random));
        if (player != null) {
            Map<String, List<String>> names = Config.playerNames();
            values.put("player", PlayerNames.name(player.getGameProfile().getName(), names));
            values.put("addressing", PlayerNames.addressing(player.getGameProfile().getName(), names, random));
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

    /** The name colonists know this player by. */
    public static String playerName(ServerPlayer player) {
        return PlayerNames.name(player.getGameProfile().getName(), Config.playerNames());
    }

    /**
     * Asks the chat model for one colonist line. {@code done} gets the result on the server thread.
     *
     * @param withTools offer the colony functions, if they are enabled
     */
    /** @param asker the player the colonist answers, who may get look-ups and orders; null for none */
    void generate(ICitizenData citizen, String prompt, List<ChatMessage> conversation, UUID asker,
                  MinecraftServer server, Consumer<ChatResult> done) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            done.accept(new ChatResult.Failure("no API key", false));
            return;
        }
        Speaker speaker = speakerOf(citizen);
        lastPrompts.put(key(speaker.colonyId(), speaker.citizenId()), prompt);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        messages.addAll(conversation);
        List<ToolSpec> offered = new ArrayList<>();
        if (asker != null && Config.toolsEnabled()) {
            offered.addAll(tools.specs());
            offered.addAll(errands.specs());
        }
        converse(settings.get().withMinTokens(REPLY_TOKENS), messages, offered, 0, server, citizen.getColony().getDimension(), speaker,
                asker, new StringBuilder(), done);
    }

    /** The cleaned line of a successful reply; empty for failures, empty replies and silence. */
    static Optional<String> lineOf(Speaker speaker, ChatResult result) {
        return switch (result) {
            case ChatResult.Reply reply -> {
                String text = Prompts.cleanReply(reply.text(), speaker.name());
                yield text.isEmpty() || Prompts.isSilence(text) ? Optional.empty() : Optional.of(text);
            }
            case ChatResult.Failure failure -> {
                LivingColonists.LOGGER.warn("{} could not answer: {}", speaker.name(), failure.reason());
                yield Optional.empty();
            }
        };
    }

    /**
     * One round with the model. When it asks for colony functions, they run on the server thread and
     * the conversation goes back with their results, at most {@link #MAX_TOOL_ROUNDS} times; the last
     * round offers no functions, so the model has to answer. {@code done} runs on the server thread.
     */
    private void converse(LlmSettings settings, List<ChatMessage> messages, List<ToolSpec> offered, int round,
                          MinecraftServer server, ResourceKey<Level> dimension, Speaker speaker, UUID asker,
                          StringBuilder trace, Consumer<ChatResult> done) {
        List<ToolSpec> now = round < MAX_TOOL_ROUNDS ? offered : List.of();
        client.complete(settings, messages, now).thenAccept(result -> server.execute(() -> {
            if (!(result instanceof ChatResult.Reply reply) || !reply.wantsTools()) {
                done.accept(result);
                return;
            }
            ICitizenData citizen = resolve(dimension, speaker.colonyId(), speaker.citizenId());
            if (citizen == null || now.isEmpty()) {
                done.accept(new ChatResult.Failure("the model asked for functions it wasn't offered", false));
                return;
            }
            List<ChatMessage> next = new ArrayList<>(messages);
            next.add(ChatMessage.assistantCalling(reply.toolCalls()));
            for (ToolCall call : reply.toolCalls()) {
                String output = errands.handles(call.name())
                        ? errands.run(call, errandContext(citizen, server.getPlayerList().getPlayer(asker)))
                        : tools.run(call, citizen);
                trace.append("\n[function] ").append(call.name()).append(call.arguments()).append(" -> ").append(output);
                next.add(ChatMessage.toolResult(call.id(), output));
            }
            lastPrompts.computeIfPresent(key(speaker.colonyId(), speaker.citizenId()), (k, v) -> v + trace);
            trace.setLength(0);
            converse(settings, next, offered, round + 1, server, dimension, speaker, asker, trace, done);
        }));
    }

    /** What an errand needs to know: the colonist, the player and what the player wrote lately. */
    private Errand.Context errandContext(ICitizenData citizen, ServerPlayer player) {
        List<String> lines = new ArrayList<>();
        if (player != null) {
            String colonist = citizen.getName();
            String playerName = playerName(player);
            memory.privateTalk(citizen, player.getUUID()).stream().filter(line -> !line.speaker().equals(colonist))
                    .forEach(line -> lines.add(line.text()));
            memory.colonyChat(citizen.getColony()).stream().filter(line -> line.speaker().equals(playerName))
                    .forEach(line -> lines.add(line.text()));
        }
        return new Errand.Context(citizen, citizen.getColony(), player,
                lines.subList(Math.max(0, lines.size() - RECENT_PLAYER_LINES), lines.size()));
    }

    /** Whether the player may talk to this colony's colonists right now; tells them if not. */
    private boolean admit(ServerPlayer player, IColony colony) {
        if (!mayTalk(player, colony)) {
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

    /** Whether the player's rank lets them talk to the colony's colonists; tells them if not. */
    public static boolean mayTalk(ServerPlayer player, IColony colony) {
        if (ColonyAccess.canTalk(role(player, colony), Config.MEMBERS_ONLY.get())) {
            return true;
        }
        player.sendSystemMessage(Component.translatable("livingcolonists.colony.not_member", colony.getName()));
        return false;
    }

    public static ColonyAccess.Role role(ServerPlayer player, IColony colony) {
        Rank rank = colony.getPermissions().getRank(player);
        if (rank == null) {
            return ColonyAccess.Role.OTHER;
        }
        return switch (rank.getId()) {
            case IPermissions.OWNER_RANK_ID -> ColonyAccess.Role.OWNER;
            case IPermissions.OFFICER_RANK_ID -> ColonyAccess.Role.OFFICER;
            case IPermissions.FRIEND_RANK_ID -> ColonyAccess.Role.FRIEND;
            default -> ColonyAccess.Role.OTHER;
        };
    }

    private static Optional<LlmSettings> settings(ServerPlayer player) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        if (settings.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.no_key"));
        }
        return settings;
    }

    /**
     * Players inside the colony's borders, plus the author wherever they are; with
     * {@code colonyChatAnywhere}, also the colony's members wherever they are.
     */
    static Collection<ServerPlayer> colonyAudience(MinecraftServer server, IColony colony, ServerPlayer author) {
        Set<ServerPlayer> players = new LinkedHashSet<>();
        if (author != null) {
            players.add(author);
        }
        boolean anywhere = Config.COLONY_CHAT_ANYWHERE.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInside(player, colony) || anywhere && role(player, colony) != ColonyAccess.Role.OTHER) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * The colony whose chat the player writes to: the one they stand in; with
     * {@code colonyChatAnywhere}, outside any colony, the one they own, else lead, else are a friend of.
     */
    public static IColony chatColony(ServerPlayer player) {
        IColony here = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (here != null || !Config.COLONY_CHAT_ANYWHERE.get()) {
            return here;
        }
        IColony best = null;
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            ColonyAccess.Role role = role(player, colony);
            if (role != ColonyAccess.Role.OTHER && (best == null || role.ordinal() < role(player, best).ordinal())) {
                best = colony;
            }
        }
        return best;
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

    /** The game locale most of these players use, like {@code ru_ru}; English when there is nobody. */
    static String language(Collection<ServerPlayer> players) {
        return players.stream().map(player -> player.clientInformation().language())
                .collect(Collectors.groupingBy(code -> code, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("en_us");
    }

    public static boolean isInside(ServerPlayer player, IColony colony) {
        return colony.getDimension().equals(player.level().dimension())
                && colony.isCoordInColony(player.level(), player.blockPosition());
    }

    /** The colonist's data, or null if the colony or the colonist is gone. */
    public static ICitizenData resolve(ResourceKey<Level> dimension, int colonyId, int citizenId) {
        IColony colony = IColonyManager.getInstance().getColonyByDimension(colonyId, dimension);
        return colony == null ? null : colony.getCitizenManager().getCivilian(citizenId);
    }

    public static Speaker speakerOf(ICitizenData citizen) {
        IJob<?> job = citizen.getJob();
        return new Speaker(citizen.getName(),
                Prompts.jobName(job == null ? null : job.getJobRegistryEntry().getKey().getPath()),
                job == null ? null : job.getJobRegistryEntry().getTranslationKey(),
                citizen.getColony().getName(), citizen.getColony().getID(), citizen.getId(),
                citizen.getColony().getTeamColonyColor());
    }

    /**
     * @param lookedAtOnly only the colonist under the crosshair, without falling back to the nearest
     */
    public static Optional<AbstractEntityCitizen> findListener(ServerPlayer player, double range, boolean lookedAtOnly) {
        Vec3 eyes = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        List<ListenerPicker.Candidate<AbstractEntityCitizen>> candidates = player.serverLevel()
                .getEntitiesOfClass(AbstractEntityCitizen.class, player.getBoundingBox().inflate(range),
                        citizen -> citizen.isAlive() && citizen.getCitizenData() != null)
                .stream()
                .map(citizen -> {
                    Vec3 offset = citizen.getBoundingBox().getCenter().subtract(eyes);
                    return new ListenerPicker.Candidate<>(citizen, offset.x, offset.y, offset.z);
                })
                .toList();
        return lookedAtOnly
                ? ListenerPicker.lookedAt(candidates, look.x, look.y, look.z, range, TARGET_RADIUS)
                : ListenerPicker.pick(candidates, look.x, look.y, look.z, range, TARGET_RADIUS);
    }

    static long key(int colonyId, int citizenId) {
        return ((long) colonyId << 32) | (citizenId & 0xFFFFFFFFL);
    }
}

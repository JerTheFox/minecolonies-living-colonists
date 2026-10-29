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
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import me.bytefox.livingcolonists.behaviour.Cooldown;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
import me.bytefox.livingcolonists.llm.ToolCall;
import me.bytefox.livingcolonists.llm.ToolSpec;
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
 * Players talk to colonists, privately or to the whole colony, and the language model answers.
 *
 * <p>Threading: requests start on the server thread, which copies everything the prompt needs
 * into strings and ids. HTTP runs on the HTTP client's threads. Results come back to the server
 * thread through {@link MinecraftServer#execute}, where colonists and players are looked up again
 * by id, because either may be gone by then. Colonists are addressed through their colony data,
 * so they can answer even when their entity is in an unloaded chunk.</p>
 */
public final class TalkService {
    public enum Audience {
        /** Only the player who wrote sees the conversation. */
        PRIVATE,
        /** Every player inside the colony's borders sees it. */
        COLONY
    }

    /** Rounds of colony function calls per reply before the model has to answer. */
    private static final int MAX_TOOL_ROUNDS = 2;
    /** How far away a colonist can be addressed with the command, in blocks. */
    private static final double TALK_RANGE = 8.0;
    /** How far from the line of sight a colonist still counts as looked at. */
    private static final double TARGET_RADIUS = 0.8;

    private final LlmClient client;
    private final PromptLibrary prompts;
    private final PersonalityService personalities;
    private final PerceptionService perception;
    private final ColonyTools tools;
    private final SpeechOutput output = new ChatSpeechOutput();
    private final Random random = new Random();
    public TalkService(LlmClient client, PromptLibrary prompts, PersonalityService personalities,
                       PerceptionService perception) {
        this.client = client;
        this.prompts = prompts;
        this.personalities = personalities;
        this.perception = perception;
        this.tools = new ColonyTools(perception);
    }

    /** The last reply prompt of each colonist, as {@link #key}, for {@code /lcdebug prompt}. Server thread only. */
    private final Map<Long, String> lastPrompts = new HashMap<>();
    /** Pause between one player's messages to colonists. Server thread only. */
    private final Cooldown cooldown = new Cooldown();
    /** Colonists waiting for a reply, as {@link #key}. Server thread only. */
    private final Set<Long> thinking = new HashSet<>();
    /** Colonists' lines waiting for their turn (see {@link LinePacing}). Server thread only. */
    private final PriorityQueue<PendingLine> pending = new PriorityQueue<>(Comparator.comparingLong(PendingLine::tick));
    /** Per colony id, the tick when the next colony-wide line may go out. Server thread only. */
    private final Map<Integer, Long> colonyFreeAt = new HashMap<>();

    private record PendingLine(long tick, ResourceKey<Level> dimension, Speaker speaker, UUID playerId, Audience audience,
                               String text) {
    }

    /** Sends lines whose turn has come. Server tick. */
    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        while (!pending.isEmpty() && pending.peek().tick() <= now) {
            PendingLine line = pending.poll();
            ICitizenData citizen = resolve(line.dimension(), line.speaker().colonyId(), line.speaker().citizenId());
            ServerPlayer author = server.getPlayerList().getPlayer(line.playerId());
            if (citizen == null) {
                continue;
            }
            if (line.audience() == Audience.COLONY) {
                output.colonistSaid(line.speaker(), line.text(), colonyAudience(server, citizen.getColony(), author));
            } else if (author != null) {
                output.colonistSaid(line.speaker(), line.text(), List.of(author));
            }
        }
    }

    /** The last prompt sent for this colonist's reply, if any. */
    public Optional<String> lastPrompt(ICitizenData citizen) {
        return Optional.ofNullable(lastPrompts.get(key(citizen.getColony().getID(), citizen.getId())));
    }

    public void clear() {
        lastPrompts.clear();
        cooldown.clear();
        thinking.clear();
        pending.clear();
        colonyFreeAt.clear();
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

    public void sayPrivately(ServerPlayer player, ICitizenData citizen, String message) {
        if (!admit(player, citizen.getColony()) || settings(player).isEmpty()) {
            return;
        }
        output.playerSaid(player, message, List.of(player));
        ask(player, citizen, message, Audience.PRIVATE, player.server.getTickCount());
    }

    /**
     * A message to the whole colony: a colonist named in it answers; otherwise a short request asks
     * the model whom it addresses (a profession, several names), and with nobody in particular two
     * or three random colonists answer.
     */
    public void sayToColony(ServerPlayer player, IColony colony, String message) {
        if (!admit(player, colony)) {
            return;
        }
        Optional<LlmSettings> settings = settings(player);
        if (settings.isEmpty()) {
            return;
        }
        long sentAt = player.server.getTickCount();
        List<ICitizenData> citizens = List.copyOf(colony.getCitizenManager().getCitizens());
        output.playerSaid(player, message, colonyAudience(player.server, colony, player));
        if (citizens.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.nobody"));
            return;
        }
        OptionalInt named = Addressing.mentioned(message, citizens.stream().map(ICitizenData::getName).toList());
        if (named.isPresent()) {
            ask(player, citizens.get(named.getAsInt()), message, Audience.COLONY, sentAt);
            return;
        }

        List<ColonyRouter.Member> roster = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (ICitizenData citizen : citizens) {
            Speaker speaker = speakerOf(citizen);
            roster.add(new ColonyRouter.Member(speaker.name(), speaker.job()));
            ids.add(citizen.getId());
        }
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = colony.getDimension();
        int colonyId = colony.getID();
        client.complete(settings.get(), ColonyRouter.prompt(prompts.get(PromptLibrary.ROUTE_COLONY), roster, message)).thenAccept(result -> server.execute(() -> {
            List<Integer> addressed = List.of();
            if (result instanceof ChatResult.Reply reply) {
                addressed = ColonyRouter.parse(reply.text(), ids.size());
            } else if (result instanceof ChatResult.Failure failure) {
                LivingColonists.LOGGER.warn("Could not work out whom a colony message addresses: {}", failure.reason());
            }
            ServerPlayer author = server.getPlayerList().getPlayer(playerId);
            for (int index : ColonyRouter.choose(addressed, ids.size(), random)) {
                ICitizenData citizen = resolve(dimension, colonyId, ids.get(index));
                if (citizen != null) {
                    ask(author, citizen, message, Audience.COLONY, sentAt);
                }
            }
        }));
    }

    /**
     * One colonist answers one message. {@code player} may be null if they left meanwhile.
     *
     * @param sentAt server tick when the player sent the message
     */
    private void ask(ServerPlayer player, ICitizenData citizen, String message, Audience audience, long sentAt) {
        Optional<LlmSettings> settings = Config.llmSettings(ModelRole.CHAT);
        if (player == null || settings.isEmpty()) {
            return;
        }
        Speaker speaker = speakerOf(citizen);
        long key = key(speaker.colonyId(), speaker.citizenId());
        if (!thinking.add(key)) {
            if (audience == Audience.PRIVATE) {
                player.sendSystemMessage(Component.translatable("livingcolonists.talk.busy", speaker.name()));
            }
            return;
        }
        IColony colony = citizen.getColony();
        Map<String, String> values = new HashMap<>(personalities.promptValues(citizen, player.server));
        values.put("name", speaker.name());
        values.put("job", speaker.job());
        values.put("colony", speaker.colony());
        values.put("time", Prompts.timeOfDay(colony.getWorld().getDayTime()));
        Map<String, List<String>> names = Config.playerNames();
        values.put("player", PlayerNames.name(player.getGameProfile().getName(), names));
        values.put("addressing", PlayerNames.addressing(player.getGameProfile().getName(), names, random));
        values.put("initiative", Prompts.initiative(random));
        values.put("situation", perception.situation(citizen));
        String prompt = Templates.render(prompts.get(audience == Audience.COLONY
                ? PromptLibrary.REPLY_COLONY : PromptLibrary.REPLY_PRIVATE), values);
        lastPrompts.put(key, prompt);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = colony.getDimension();
        long earliest = sentAt + LinePacing.gap(random);
        List<ToolSpec> offered = Config.toolsEnabled() ? tools.specs() : List.of();
        converse(settings.get(), List.of(ChatMessage.system(prompt), ChatMessage.user(message)), offered, 0, server,
                dimension, speaker, new StringBuilder(), result -> deliver(server, dimension, speaker, playerId,
                        audience, earliest, result));
    }

    /**
     * One round with the model. When it asks for colony functions, they run on the server thread and
     * the conversation goes back with their results, at most {@link #MAX_TOOL_ROUNDS} times; the last
     * round offers no functions, so the model has to answer. {@code done} runs on the server thread.
     */
    private void converse(LlmSettings settings, List<ChatMessage> messages, List<ToolSpec> offered, int round,
                          MinecraftServer server, ResourceKey<Level> dimension, Speaker speaker, StringBuilder trace,
                          Consumer<ChatResult> done) {
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
                String output = tools.run(call, citizen);
                trace.append("\n[function] ").append(call.name()).append(call.arguments()).append(" -> ").append(output);
                next.add(ChatMessage.toolResult(call.id(), output));
            }
            lastPrompts.computeIfPresent(key(speaker.colonyId(), speaker.citizenId()), (k, v) -> v + trace);
            trace.setLength(0);
            converse(settings, next, offered, round + 1, server, dimension, speaker, trace, done);
        }));
    }

    /** @param earliest the reply waits at least until this tick, as if the colonist were typing */
    private void deliver(MinecraftServer server, ResourceKey<Level> dimension, Speaker speaker, UUID playerId,
                         Audience audience, long earliest, ChatResult result) {
        thinking.remove(key(speaker.colonyId(), speaker.citizenId()));
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        ICitizenData citizen = resolve(dimension, speaker.colonyId(), speaker.citizenId());
        if (citizen == null) {
            return;
        }
        switch (result) {
            case ChatResult.Reply reply -> {
                String text = Prompts.cleanReply(reply.text(), speaker.name());
                if (text.isEmpty()) {
                    LivingColonists.LOGGER.warn("{} gave an empty reply", speaker.name());
                    return;
                }
                long now = server.getTickCount();
                long at;
                if (audience == Audience.COLONY) {
                    at = LinePacing.slot(now, earliest, colonyFreeAt.getOrDefault(speaker.colonyId(), 0L));
                    colonyFreeAt.put(speaker.colonyId(), at + LinePacing.gap(random));
                } else {
                    at = LinePacing.slot(now, earliest, 0L);
                }
                pending.add(new PendingLine(at, dimension, speaker, playerId, audience, text));
            }
            case ChatResult.Failure failure -> {
                LivingColonists.LOGGER.warn("{} could not answer: {}", speaker.name(), failure.reason());
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("livingcolonists.talk.failed", speaker.name()));
                }
            }
        }
    }

    /** Whether the player may talk to this colony's colonists right now; tells them if not. */
    private boolean admit(ServerPlayer player, IColony colony) {
        if (!mayTalk(player, colony)) {
            return false;
        }
        Optional<Long> wait = cooldown.tryUse(player.getUUID(), player.server.getTickCount(),
                Config.COOLDOWN_SECONDS.get() * 20L);
        if (wait.isPresent()) {
            player.displayClientMessage(Component.translatable("livingcolonists.talk.cooldown",
                    (wait.get() + 19) / 20), true);
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

    /** Players inside the colony's borders, plus the author wherever they are. */
    private static Collection<ServerPlayer> colonyAudience(MinecraftServer server, IColony colony, ServerPlayer author) {
        Set<ServerPlayer> players = new LinkedHashSet<>();
        if (author != null) {
            players.add(author);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInside(player, colony)) {
                players.add(player);
            }
        }
        return players;
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
                citizen.getColony().getName(), citizen.getColony().getID(), citizen.getId());
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

    private static long key(int colonyId, int citizenId) {
        return ((long) colonyId << 32) | (citizenId & 0xFFFFFFFFL);
    }
}

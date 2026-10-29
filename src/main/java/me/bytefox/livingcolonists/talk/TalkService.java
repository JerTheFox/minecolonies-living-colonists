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

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import me.bytefox.livingcolonists.llm.ModelRole;
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

    /** How far away a colonist can be addressed with the command, in blocks. */
    private static final double TALK_RANGE = 8.0;
    /** How far from the line of sight a colonist still counts as looked at. */
    private static final double TARGET_RADIUS = 0.8;

    private final LlmClient client;
    private final SpeechOutput output = new ChatSpeechOutput();
    private final Random random = new Random();
    public TalkService(LlmClient client) {
        this.client = client;
    }

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

    public void clear() {
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
        if (settings(player).isEmpty()) {
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
        client.complete(settings.get(), ColonyRouter.prompt(roster, message)).thenAccept(result -> server.execute(() -> {
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
        String prompt = Prompts.system(speaker.name(), speaker.job(), speaker.colony(),
                Prompts.timeOfDay(colony.getWorld().getDayTime()), player.getGameProfile().getName(),
                audience == Audience.COLONY);
        MinecraftServer server = player.server;
        UUID playerId = player.getUUID();
        ResourceKey<Level> dimension = colony.getDimension();
        long earliest = sentAt + LinePacing.gap(random);
        client.complete(settings.get(), List.of(ChatMessage.system(prompt), ChatMessage.user(message)))
                .thenAccept(result -> server.execute(
                        () -> deliver(server, dimension, speaker, playerId, audience, earliest, result)));
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

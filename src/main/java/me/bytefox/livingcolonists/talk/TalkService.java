package me.bytefox.livingcolonists.talk;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.llm.ChatMessage;
import me.bytefox.livingcolonists.llm.ChatResult;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.LlmSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A player says something to a colonist and gets an answer from the language model.
 *
 * <p>Threading: {@link #talk} runs on the server thread and copies everything the prompt needs
 * into strings. The HTTP request runs on the HTTP client's threads. The answer comes back to the
 * server thread through {@link MinecraftServer#execute}, where the colonist and the player are
 * looked up again by UUID, because either may be gone by then.</p>
 */
public final class TalkService {
    /** How far away a colonist can be addressed, in blocks. */
    private static final double TALK_RANGE = 8.0;
    /** How far from the line of sight a colonist still counts as looked at. */
    private static final double TARGET_RADIUS = 0.8;

    private final LlmClient client = new LlmClient(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    private final SpeechOutput output = new ChatSpeechOutput();
    /** Colonists waiting for a reply. Server thread only. */
    private final Set<UUID> thinking = new HashSet<>();

    /** @return 1 if a request was sent, 0 otherwise (command result) */
    public int talk(ServerPlayer player, String message) {
        Optional<AbstractEntityCitizen> found = pickListener(player);
        if (found.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.nobody"));
            return 0;
        }
        AbstractEntityCitizen citizen = found.get();
        ICitizenData data = citizen.getCitizenData();
        IJob<?> job = data.getJob();
        Speaker speaker = new Speaker(data.getName(),
                Prompts.jobName(job == null ? null : job.getJobRegistryEntry().getKey().getPath()),
                job == null ? null : job.getJobRegistryEntry().getTranslationKey(),
                data.getColony().getName());

        Optional<LlmSettings> settings = Config.llmSettings();
        if (settings.isEmpty()) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.no_key", Config.API_KEY_ENV.get()));
            return 0;
        }
        UUID citizenId = citizen.getUUID();
        if (thinking.contains(citizenId)) {
            player.sendSystemMessage(Component.translatable("livingcolonists.talk.busy", speaker.name()));
            return 0;
        }

        output.playerSaid(player, speaker, message);
        String prompt = Prompts.system(speaker.name(), speaker.job(), speaker.colony(),
                Prompts.timeOfDay(player.serverLevel().getDayTime()), player.getGameProfile().getName());
        List<ChatMessage> messages = List.of(ChatMessage.system(prompt), ChatMessage.user(message));

        thinking.add(citizenId);
        MinecraftServer server = player.server;
        ServerLevel level = player.serverLevel();
        UUID playerId = player.getUUID();
        client.complete(settings.get(), messages)
                .thenAccept(result -> server.execute(() -> deliver(level, citizenId, playerId, speaker, result)));
        return 1;
    }

    private void deliver(ServerLevel level, UUID citizenId, UUID playerId, Speaker speaker, ChatResult result) {
        thinking.remove(citizenId);
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (!(level.getEntity(citizenId) instanceof AbstractEntityCitizen citizen) || !citizen.isAlive()) {
            return;
        }
        switch (result) {
            case ChatResult.Reply reply -> output.colonistSaid(citizen, speaker, Prompts.cleanReply(reply.text(), speaker.name()));
            case ChatResult.Failure failure -> {
                LivingColonists.LOGGER.warn("{} could not answer: {}", speaker.name(), failure.reason());
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("livingcolonists.talk.failed", speaker.name()));
                }
            }
        }
    }

    private static Optional<AbstractEntityCitizen> pickListener(ServerPlayer player) {
        Vec3 eyes = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        List<ListenerPicker.Candidate<AbstractEntityCitizen>> candidates = player.serverLevel()
                .getEntitiesOfClass(AbstractEntityCitizen.class, player.getBoundingBox().inflate(TALK_RANGE),
                        citizen -> citizen.isAlive() && citizen.getCitizenData() != null)
                .stream()
                .map(citizen -> {
                    Vec3 offset = citizen.getBoundingBox().getCenter().subtract(eyes);
                    return new ListenerPicker.Candidate<>(citizen, offset.x, offset.y, offset.z);
                })
                .toList();
        return ListenerPicker.pick(candidates, look.x, look.y, look.z, TALK_RANGE, TARGET_RADIUS);
    }

}

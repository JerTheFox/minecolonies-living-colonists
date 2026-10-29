package me.bytefox.livingcolonists;

import java.net.http.HttpClient;
import java.time.Duration;

import com.mojang.logging.LogUtils;
import me.bytefox.livingcolonists.debug.DebugCommand;
import me.bytefox.livingcolonists.debug.UsageData;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.UsageStats;
import me.bytefox.livingcolonists.personality.ArchetypeLibrary;
import me.bytefox.livingcolonists.personality.PersonalityData;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import me.bytefox.livingcolonists.talk.Conversations;
import me.bytefox.livingcolonists.talk.TalkCommand;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(LivingColonists.MODID)
public final class LivingColonists {
    public static final String MODID = "livingcolonists";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** How often usage statistics are marked for saving, in ticks (1 minute). */
    private static final int USAGE_SAVE_INTERVAL = 1200;

    private final UsageStats usage = new UsageStats();
    private final LlmClient client = new LlmClient(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), usage, LlmClient.DEFAULT_BACKOFF);
    private final PromptLibrary prompts = new PromptLibrary(FMLPaths.CONFIGDIR.get());
    private final PersonalityService personalities = new PersonalityService(prompts,
            new ArchetypeLibrary(FMLPaths.CONFIGDIR.get()), client);
    private final TalkService talk = new TalkService(client, prompts, personalities);
    private UsageData usageData;
    private final Conversations conversations = new Conversations(talk);

    public LivingColonists(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            TalkCommand.register(event.getDispatcher(), talk, conversations);
            DebugCommand.register(event.getDispatcher(), usage, personalities, prompts, talk, conversations);
        });
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            conversations.tick(event.getServer());
            talk.tick(event.getServer());
            if (event.getServer().getTickCount() % USAGE_SAVE_INTERVAL == 0) {
                markUsageDirty();
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> {
            prompts.reload();
            personalities.archetypes().reload();
        });
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            personalities.attach(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(PersonalityData.factory(), PersonalityData.NAME));
            usage.clearSession();
            usageData = event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(UsageData.factory(usage), UsageData.NAME);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> markUsageDirty());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) ->
                conversations.forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            conversations.clear();
            talk.clear();
            usageData = null;
            personalities.detach();
        });
    }

    private void markUsageDirty() {
        if (usageData != null && usage.takeDirty()) {
            usageData.setDirty();
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Living Colonists loaded, model {} at {}, API key {}", Config.CHAT_MODEL.get(), Config.BASE_URL.get(),
                Config.apiKey().isPresent() ? "found" : "missing: set apiKey in the config or " + Config.API_KEY_ENV.get());
    }

    /**
     * Optional channels: a client without the mod can still join and use the commands. The
     * client-bound handler is a lambda so the client class is only loaded on the client.
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(ConversationAction.TYPE, ConversationAction.STREAM_CODEC,
                (payload, context) -> conversations.onAction((ServerPlayer) context.player(), payload.action()));
        registrar.playToClient(ConversationState.TYPE, ConversationState.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.ClientConversation.apply(payload));
    }

    /**
     * Messages for colonists never reach the server-wide chat. The event may arrive off the server
     * thread, so the message is handed over with {@code execute}.
     */
    private void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!conversations.wantsChat(player)) {
            return;
        }
        event.setCanceled(true);
        String text = event.getRawText();
        player.server.execute(() -> conversations.onChat(player, text));
    }
}

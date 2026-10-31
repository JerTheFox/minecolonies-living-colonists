package me.bytefox.livingcolonists;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Predicate;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.eventbus.events.colony.ColonyDeletedModEvent;
import com.mojang.logging.LogUtils;
import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.talk.Prompts;
import me.bytefox.livingcolonists.talk.Rumours;
import me.bytefox.livingcolonists.errands.Handbook;
import me.bytefox.livingcolonists.events.ModEvents;
import me.bytefox.livingcolonists.events.GameEvents;
import me.bytefox.livingcolonists.debug.DebugCommand;
import me.bytefox.livingcolonists.debug.UsageData;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.EmbeddingClient;
import me.bytefox.livingcolonists.llm.UsageStats;
import me.bytefox.livingcolonists.bonds.BondsData;
import me.bytefox.livingcolonists.errands.GuardTally;
import me.bytefox.livingcolonists.errands.NotesData;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.RequestResponses;
import me.bytefox.livingcolonists.memory.MemoryData;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.personality.ArchetypeLibrary;
import me.bytefox.livingcolonists.personality.PersonalityData;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import me.bytefox.livingcolonists.network.SettingsRequest;
import me.bytefox.livingcolonists.network.SettingsState;
import me.bytefox.livingcolonists.network.ChatTyping;
import me.bytefox.livingcolonists.network.ItemNamesReply;
import me.bytefox.livingcolonists.network.ItemNamesRequest;
import me.bytefox.livingcolonists.perception.ItemNames;
import me.bytefox.livingcolonists.network.SpeechBubble;
import me.bytefox.livingcolonists.network.TypingState;
import me.bytefox.livingcolonists.settings.SettingsHandler;
import me.bytefox.livingcolonists.settings.SettingsService;
import me.bytefox.livingcolonists.talk.Bubbles;
import me.bytefox.livingcolonists.talk.Conversations;
import me.bytefox.livingcolonists.talk.InitiativeData;
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
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
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
    /**
     * How often data of colonists who are gone is dropped, in ticks (ten seconds). MineColonies
     * reuses the id of a dead colonist for the next newcomer, so this has to happen soon.
     */
    private static final int GONE_CHECK_INTERVAL = 200;

    private final UsageStats usage = new UsageStats();
    private final LlmClient client = new LlmClient(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build(),
            usage, LlmClient.DEFAULT_BACKOFF, message -> ModLog.info("{}", message));
    private final PromptLibrary prompts = new PromptLibrary(FMLPaths.CONFIGDIR.get());
    private final PersonalityService personalities = new PersonalityService(prompts,
            new ArchetypeLibrary(FMLPaths.CONFIGDIR.get()), client);
    private final PerceptionService perception = new PerceptionService();
    private final BondsService bonds = new BondsService();
    private final MemoryService memory = new MemoryService(client, new EmbeddingClient(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build(), usage),
            prompts, bonds);
    private final TalkService talk = new TalkService(client, prompts, personalities, perception, memory, bonds);
    private UsageData usageData;
    private final Conversations conversations = new Conversations(talk);
    private final SettingsHandler settings = new SettingsHandler(new SettingsService(client, usage));

    public LivingColonists(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        prompts.onReload(() -> {
            try {
                Handbook.use(prompts.content(PromptLibrary.HANDBOOK));
                Rumours.use(prompts.content(PromptLibrary.RUMOURS));
                Prompts.useTopics(prompts.content(PromptLibrary.TOPICS));
            } catch (RuntimeException e) {
                ModLog.warn("A content file in config/livingcolonists/content is broken, fix or delete it", e);
            }
        });
        client.limitDailyTokens(() -> Config.SPEC.isLoaded() ? Config.TOKENS_PER_DAY.get() : 0);
        bonds.setReminder(talk::remindPromise);
        RequestResponses.listen(new RequestResponses.Listener() {
            @Override
            public void onRequest(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player) {
                bonds.onRequestResponse(citizen, request, button, player, TalkService.playerName(player));
            }

            @Override
            public void onDialog(ICitizenData citizen, String inquiry, String button, ServerPlayer player) {
                bonds.onDialogResponse(citizen, inquiry, button, player, TalkService.playerName(player));
            }
        });
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            TalkCommand.register(event.getDispatcher(), talk, conversations);
            DebugCommand.register(event.getDispatcher(), usage, personalities, prompts, talk, conversations, perception,
                    memory, bonds);
        });
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            talk.errands().tally().onDeath(event.getEntity(), event.getSource().getEntity());
            if (event.getEntity() instanceof ServerPlayer player) {
                ModEvents.BUS.post(new GameEvents.PlayerDied(player));
            }
            if (event.getSource().getEntity() instanceof ServerPlayer killer) {
                talk.playerKilled(killer, event.getEntity());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            conversations.tick(event.getServer());
            if (!TalkService.enabled()) {
                return;
            }
            talk.tick(event.getServer());
            memory.tick(event.getServer());
            ItemNames.tick(event.getServer());
            perception.tick(event.getServer());
            bonds.tick(event.getServer());
            if (event.getServer().getTickCount() % GONE_CHECK_INTERVAL == 0) {
                forget(Citizens::isGone);
            }
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
            memory.attach(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(MemoryData.factory(), MemoryData.NAME));
            bonds.attach(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(BondsData.factory(), BondsData.NAME));
            talk.errands().notes().attach(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(NotesData.factory(), NotesData.NAME));
            talk.errands().warmUp();
            talk.attachInitiative(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(InitiativeData.factory(), InitiativeData.NAME));
            talk.errands().tally().attach(event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(GuardTally.Data.factory(), GuardTally.Data.NAME));
            usage.clearSession();
            usageData = event.getServer().overworld().getDataStorage()
                    .computeIfAbsent(UsageData.factory(usage), UsageData.NAME);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            talk.errands().beforeStop();
            markUsageDirty();
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            conversations.forget(event.getEntity().getUUID());
            if (event.getEntity() instanceof ServerPlayer player) {
                ModEvents.BUS.post(new GameEvents.PlayerLeft(player));
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ModEvents.BUS.post(new GameEvents.PlayerJoined(player));
            }
        });
        NeoForge.EVENT_BUS.addListener((AdvancementEvent.AdvancementEarnEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                var holder = event.getAdvancement();
                ModEvents.BUS.post(new GameEvents.AdvancementEarned(player, holder.id().toString(), holder.value().display()
                        .map(display -> display.getTitle().getString()).orElse(holder.id().getPath())));
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Open event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                talk.gifts().opened(player, event.getContainer());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Close event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                talk.gifts().closed(player, event.getContainer());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ModEvents.BUS.post(new GameEvents.PlayerChangedWorld(player, event.getTo()));
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            Counters.clear();
            conversations.clear();
            talk.clear();
            usageData = null;
            personalities.detach();
            memory.detach();
            ItemNames.clear();
            perception.clear();
            bonds.detach();
            talk.errands().notes().detach();
            talk.errands().tally().detach();
            talk.detachInitiative();
        });
    }

    /** Drops everything the mod keeps about the colonists whose key matches. */
    private void forget(Predicate<String> key) {
        personalities.forget(key);
        talk.errands().notes().forget(key);
        talk.errands().tally().forget(key);
        talk.forgetInitiative(key);
        memory.forget(key);
        bonds.forget(key);
        perception.forget(key);
    }

    /** A deleted colony's id may go to a new colony: nothing of the old one may carry over. */
    private void onColonyDeleted(ColonyDeletedModEvent event) {
        int id = event.getColony().getID();
        forget(key -> key.startsWith(id + ":"));
        memory.forgetColonyChat(id);
    }

    private void markUsageDirty() {
        if (usageData != null && usage.takeDirty()) {
            usageData.setDirty();
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        IMinecoloniesAPI.getInstance().getEventBus().subscribe(ColonyDeletedModEvent.class, this::onColonyDeleted);
        ModLog.info("Living Colonists loaded, model {} at {}, API key {}", Config.CHAT_MODEL.get(), Config.BASE_URL.get(),
                Config.apiKey().isPresent() ? "found" : "missing: set apiKey in the config or " + Config.API_KEY_ENV.get());
        ModLog.info("Embeddings: {}", Config.embeddings().map(endpoint -> "model " + endpoint.model() + " at "
                + endpoint.baseUrl()).orElse("off (no key), remembered facts are picked by words"));
    }

    /**
     * The mod is needed on both sides: a client without it can't join, and NeoForge tells the player
     * what is missing. The client-bound handlers are lambdas so client classes load only on the client.
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToServer(ConversationAction.TYPE, ConversationAction.STREAM_CODEC,
                (payload, context) -> conversations.onAction((ServerPlayer) context.player(), payload.action()));
        registrar.playToClient(ConversationState.TYPE, ConversationState.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.ClientConversation.apply(payload));
        registrar.playToServer(SettingsRequest.TYPE, SettingsRequest.STREAM_CODEC,
                (payload, context) -> settings.handle((ServerPlayer) context.player(), payload));
        registrar.playToClient(SettingsState.TYPE, SettingsState.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.settings.ClientSettings.apply(payload));
        registrar.playToClient(TypingState.TYPE, TypingState.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.TypingIndicator.apply(payload));
        registrar.playToClient(SpeechBubble.TYPE, SpeechBubble.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.SpeechBubbles.apply(payload));
        registrar.playToClient(ItemNamesRequest.TYPE, ItemNamesRequest.STREAM_CODEC,
                (payload, context) -> me.bytefox.livingcolonists.client.ItemNamesClient.apply(payload));
        registrar.playToServer(ItemNamesReply.TYPE, ItemNamesReply.STREAM_CODEC,
                (payload, context) -> ItemNames.received((ServerPlayer) context.player(), payload));
        registrar.playToServer(ChatTyping.TYPE, ChatTyping.STREAM_CODEC, (payload, context) -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (Config.PLAYER_BUBBLES.get()) {
                Bubbles.typing(player, payload.typing(), player.server.getPlayerList().getPlayers());
            }
        });
    }

    /**
     * Messages for colonists never reach the server-wide chat. The event may arrive off the server
     * thread, so the message is handed over with {@code execute}.
     */
    private void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (!TalkService.enabled() || !conversations.wantsChat(player)) {
            if (Config.PLAYER_BUBBLES.get()) {
                String said = event.getRawText();
                player.server.execute(() -> Bubbles.line(player, said, player.server.getPlayerList().getPlayers()));
            }
            return;
        }
        event.setCanceled(true);
        String text = event.getRawText();
        player.server.execute(() -> conversations.onChat(player, text));
    }
}

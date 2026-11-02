package me.bytefox.livingcolonists.scenarios;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import com.mojang.authlib.GameProfile;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.bonds.BondsData;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.llm.EmbeddingClient;
import me.bytefox.livingcolonists.llm.LlmClient;
import me.bytefox.livingcolonists.llm.UsageStats;
import me.bytefox.livingcolonists.memory.MemoryData;
import me.bytefox.livingcolonists.memory.MemoryService;
import me.bytefox.livingcolonists.perception.PerceptionService;
import me.bytefox.livingcolonists.personality.ArchetypeLibrary;
import me.bytefox.livingcolonists.personality.PersonalityData;
import me.bytefox.livingcolonists.personality.PersonalityService;
import me.bytefox.livingcolonists.prompt.PromptLibrary;
import me.bytefox.livingcolonists.talk.InitiativeData;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.Level;
import net.neoforged.fml.config.IConfigSpec;
import org.mockito.MockedStatic;

/**
 * A colony on a server for scenario tests: the mod's real talk, memory and bonds services, MineColonies, the
 * server and the player stood in by Mockito, the model by {@link FakeModel}. Ticks are advanced by the test;
 * what the background threads hand to the server thread runs on the next tick, as in the game.
 */
final class Stage implements AutoCloseable {
    private static final int COLONY_ID = 1;

    final FakeModel model;
    final TalkService talk;
    final BondsService bonds;
    final MemoryService memory;
    final PersonalityService personalities;
    final IColony colony;
    final List<ICitizenData> citizens = new ArrayList<>();
    final ServerPlayer player;
    /** What the player saw in chat, as plain text. */
    final List<String> chat = new ArrayList<>();

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ConcurrentLinkedQueue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private final MockedStatic<IColonyManager> manager;
    private final Path directory;
    private int tick;
    /** MineColonies' event log of the colony, oldest first. */
    final List<com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription> eventLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean raided;
    /** Warnings and errors the mod logged. */
    final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private org.apache.logging.log4j.core.Appender appender;
    /** The colony's buildings by position, and its builders' work orders by id. */
    final Map<BlockPos, com.minecolonies.api.colony.buildings.IBuilding> buildings = new java.util.HashMap<>();
    final Map<Integer, com.minecolonies.api.colony.workorders.IServerWorkOrder> workOrders = new java.util.HashMap<>();
    /** Whether the player is out of the colony. */
    private volatile boolean away;
    private long gameTime = 6000;

    Stage(String... names) throws Exception {
        model = new FakeModel();
        configure(model.baseUrl());
        directory = Files.createTempDirectory("livingcolonists-stage");

        level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameTime()).thenAnswer(invocation -> gameTime);
        when(level.getDayTime()).thenAnswer(invocation -> gameTime);

        server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        when(server.getTickCount()).thenAnswer(invocation -> tick);
        when(server.overworld()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        doAnswer(invocation -> mainThread.add(invocation.getArgument(0))).when(server).execute(any(Runnable.class));

        colony = mock(IColony.class, RETURNS_DEEP_STUBS);
        when(colony.getID()).thenReturn(COLONY_ID);
        when(colony.getName()).thenReturn("Blackwood");
        when(colony.getDimension()).thenReturn(Level.OVERWORLD);
        when(colony.getWorld()).thenReturn(level);
        when(colony.isCoordInColony(any(), any())).thenAnswer(invocation -> !away);
        when(colony.getCitizenManager().getCitizens()).thenAnswer(invocation -> List.copyOf(citizens));
        when(colony.getCitizenManager().getCivilian(anyInt())).thenAnswer(invocation -> citizens.stream()
                .filter(c -> c.getId() == (int) invocation.getArgument(0)).findFirst().orElse(null));
        when(colony.getServerBuildingManager().getBuildings()).thenAnswer(invocation -> Map.copyOf(buildings));
        when(colony.getWorkManager().getWorkOrders()).thenAnswer(invocation -> Map.copyOf(workOrders));
        when(colony.getServerBuildingManager().getBuilding(any(BlockPos.class))).thenAnswer(invocation -> buildings.get(invocation.getArgument(0)));
        when(colony.getRaiderManager().isRaided()).thenAnswer(invocation -> raided);
        when(colony.getEventDescriptionManager().getEventDescriptions()).thenAnswer(invocation -> List.copyOf(eventLog));
        watchLog();
        for (int i = 0; i < names.length; i++) {
            citizens.add(citizen(i + 1, names[i]));
        }

        player = mock(ServerPlayer.class, RETURNS_DEEP_STUBS);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(player.getUUID()).thenReturn(id);
        when(player.getGameProfile()).thenReturn(new GameProfile(id, "Alex"));
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        when(player.clientInformation()).thenReturn(new ClientInformation("ru_ru", 8, ChatVisiblity.FULL, true, 0,
                HumanoidArm.RIGHT, false, true));
        when(player.getDisplayName()).thenReturn(Component.literal("Alex"));
        doAnswer(invocation -> chat.add(((Component) invocation.getArgument(0)).getString()))
                .when(player).sendSystemMessage(any(Component.class));
        set(player, "server", server);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(colony.getPermissions().getRank(player)).thenReturn(new Rank(IPermissions.OWNER_RANK_ID, "Owner", false));

        PlayerList players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.of(player));
        when(players.getPlayer(id)).thenReturn(player);
        when(server.getPlayerList()).thenReturn(players);

        manager = mockStatic(IColonyManager.class);
        IColonyManager colonies = mock(IColonyManager.class);
        manager.when(IColonyManager::getInstance).thenReturn(colonies);
        when(colonies.getAllColonies()).thenReturn(List.of(colony));
        when(colonies.getColonyByPosFromWorld(any(), any())).thenAnswer(invocation -> away ? null : colony);
        when(colonies.getColonyByDimension(anyInt(), any())).thenReturn(colony);

        LlmClient client = new LlmClient(HttpClient.newHttpClient(), new UsageStats(), List.of(Duration.ofMillis(5)), note -> { });
        // As the mod wires it: the day's token limit from the settings.
        client.limitDailyTokens(() -> Config.TOKENS_PER_DAY.get());
        PromptLibrary prompts = new PromptLibrary(directory);
        // The content files, as the mod loads them on start.
        prompts.onReload(() -> {
            me.bytefox.livingcolonists.errands.Handbook.use(prompts.content(PromptLibrary.HANDBOOK));
            me.bytefox.livingcolonists.talk.Rumours.use(prompts.content(PromptLibrary.RUMOURS));
            me.bytefox.livingcolonists.talk.Prompts.useTopics(prompts.content(PromptLibrary.TOPICS));
            me.bytefox.livingcolonists.mood.MoodTable.use(prompts.content(PromptLibrary.MOODS));
            me.bytefox.livingcolonists.talk.TalkShapes.use(prompts.content(PromptLibrary.TALK));
            me.bytefox.livingcolonists.talk.Catchphrases.use(prompts.content(PromptLibrary.CATCHPHRASES));
            me.bytefox.livingcolonists.friendship.GiftTastes.use(prompts.content(PromptLibrary.GIFTS));
            me.bytefox.livingcolonists.friendship.CalendarTable.use(prompts.content(PromptLibrary.CALENDAR));
            me.bytefox.livingcolonists.friendship.FriendshipLevels.use(prompts.content(PromptLibrary.FRIENDSHIP));
            me.bytefox.livingcolonists.friendship.LetterTable.use(prompts.content(PromptLibrary.LETTERS));
            me.bytefox.livingcolonists.friendship.HelpTable.use(prompts.content(PromptLibrary.HELP));
        });
        prompts.reload();
        ArchetypeLibrary archetypes = new ArchetypeLibrary(directory);
        archetypes.reload();
        personalities = new PersonalityService(prompts, archetypes, client);
        personalities.attach(PersonalityData.factory().constructor().get());
        bonds = new BondsService();
        bonds.attach(BondsData.factory().constructor().get());
        memory = new MemoryService(client, new EmbeddingClient(HttpClient.newHttpClient(), new UsageStats()), prompts, bonds);
        memory.attach(MemoryData.factory().constructor().get());
        talk = new TalkService(client, prompts, personalities, new PerceptionService(), memory, bonds);
        talk.attachInitiative(InitiativeData.factory().constructor().get());
        // Wired as LivingColonists does.
        bonds.setReminder(talk::remindPromise);
        talk.calendar().attach(me.bytefox.livingcolonists.friendship.CalendarData.factory().constructor().get());
        talk.letters().attach(me.bytefox.livingcolonists.friendship.LettersData.factory().constructor().get());
    }

    /** A colonist who just came to live in the colony, with the id given (a dead colonist's id goes to the next one). */
    ICitizenData newcomer(int id, String name) {
        ICitizenData citizen = citizen(id, name);
        citizens.add(citizen);
        return citizen;
    }

    private ICitizenData citizen(int id, String name) {
        ICitizenData citizen = mock(ICitizenData.class, RETURNS_DEEP_STUBS);
        when(citizen.getId()).thenReturn(id);
        when(citizen.getName()).thenReturn(name);
        when(citizen.getColony()).thenReturn(colony);
        when(citizen.getJob()).thenReturn(null);
        when(citizen.getEntity()).thenReturn(Optional.empty());
        when(citizen.isChild()).thenReturn(false);
        when(citizen.getHomeBuilding()).thenReturn(null);
        when(citizen.getWorkBuilding()).thenReturn(null);
        when(citizen.getParents()).thenReturn(new com.minecolonies.api.util.Tuple<>("", ""));
        when(citizen.isFemale()).thenReturn(id % 2 == 1);
        // As in MineColonies: no modifier of an id until someone adds one.
        when(citizen.getCitizenHappinessHandler().getModifier(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        // Fed and content, unless a test says otherwise.
        when(citizen.getSaturation()).thenReturn(20.0);
        when(citizen.getCitizenHappinessHandler().getHappiness(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(8.0);
        return citizen;
    }

    /** The mod's settings with their defaults, the model on this machine and nothing read from the environment. */
    private static void configure(String baseUrl) {
        CommentedConfig config = CommentedConfig.inMemory();
        Config.SPEC.correct(config);
        try {
            // FML's own loaded config, made by hand: the interface is sealed to it.
            var made = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class,
                    Path.class, net.neoforged.fml.config.ModConfig.class);
            made.setAccessible(true);
            Config.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) made.newInstance(config, null, null));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("can't load the mod's settings for the test", e);
        }
        Config.BASE_URL.set(baseUrl);
        Config.API_KEY.set("test-key");
        Config.API_KEY_ENV.set("LIVINGCOLONISTS_TEST_NO_KEY");
        Config.EMBEDDINGS_BASE_URL.set(baseUrl);
        Config.EMBEDDINGS_API_KEY.set("");
        Config.EMBEDDINGS_API_KEY_ENV.set("LIVINGCOLONISTS_TEST_NO_KEY");
        Config.SPEECH_BUBBLES.set(false);
        Config.COOLDOWN_SECONDS.set(0);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** The colonist is up and about in the world, at {@code pos}, awake: they can see and be seen. */
    com.minecolonies.api.entity.citizen.AbstractEntityCitizen body(ICitizenData citizen, BlockPos pos) {
        var entity = mock(com.minecolonies.api.entity.citizen.AbstractEntityCitizen.class, RETURNS_DEEP_STUBS);
        when(entity.isAlive()).thenReturn(true);
        when(entity.isSleeping()).thenReturn(false);
        when(entity.level()).thenReturn(level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(pos));
        UUID id = UUID.nameUUIDFromBytes(citizen.getName().getBytes());
        when(entity.getUUID()).thenReturn(id);
        when(citizen.getEntity()).thenReturn(Optional.of(entity));
        when(citizen.isAsleep()).thenReturn(false);
        return entity;
    }

    /** A hostile mob of {@code type} at {@code pos}; attacking the player if {@code attacking}. */
    <T extends net.minecraft.world.entity.Mob> T mob(Class<T> type, int id, BlockPos pos, boolean attacking) {
        T mob = mock(type, RETURNS_DEEP_STUBS);
        when(mob.isAlive()).thenReturn(true);
        when(mob.getId()).thenReturn(id);
        when(mob.level()).thenReturn(level);
        when(mob.blockPosition()).thenReturn(pos);
        when(mob.getBlockX()).thenReturn(pos.getX());
        when(mob.getBlockZ()).thenReturn(pos.getZ());
        when(mob.getType().getDescription()).thenReturn(Component.literal(type.getSimpleName()));
        when(mob.getTarget()).thenReturn(attacking ? player : null);
        return mob;
    }

    /** What colonists' eyes find around them in the world. */
    void enemiesAround(List<? extends net.minecraft.world.entity.Mob> mobs) {
        when(level.getEntitiesOfClass(org.mockito.ArgumentMatchers.<Class<net.minecraft.world.entity.Mob>>any(),
                any(net.minecraft.world.phys.AABB.class), any())).thenAnswer(invocation -> List.copyOf(mobs));
    }

    /**
     * A MineColonies building of type {@code path} ("bakery") at {@code level}. Asking it to upgrade places a work order
     * at its position, as MineColonies does; removing the order takes it away.
     */
    com.minecolonies.api.colony.buildings.IBuilding building(String path, int level, BlockPos pos) {
        var type = mock(com.minecolonies.api.colony.buildings.registry.BuildingEntry.class);
        when(type.getTranslationKey()).thenReturn("com.minecolonies.building." + path);
        when(type.getRegistryName()).thenReturn(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecolonies", path));
        var building = mock(com.minecolonies.api.colony.buildings.IBuilding.class, RETURNS_DEEP_STUBS);
        when(building.getBuildingType()).thenReturn(type);
        when(building.getBuildingLevel()).thenReturn(level);
        when(building.getMaxBuildingLevel()).thenReturn(5);
        when(building.getPosition()).thenReturn(pos);
        when(building.getCustomName()).thenReturn("");
        int id = 100 + buildings.size();
        doAnswer(invocation -> {
            var order = mock(com.minecolonies.api.colony.workorders.IServerWorkOrder.class, RETURNS_DEEP_STUBS);
            when(order.getLocation()).thenReturn(pos);
            workOrders.put(id, order);
            return null;
        }).when(building).requestUpgrade(any(), any());
        doAnswer(invocation -> workOrders.remove(id)).when(building).removeWorkOrder();
        buildings.put(pos, building);
        return building;
    }

    /** A raid begins, or ends. */
    void raid(boolean on) {
        raided = on;
    }

    /** A colonist event in MineColonies' log: {@code type} like "citizen_died", "citizen_born", "citizen_grown_up". */
    void citizenEvent(String type, String name) {
        var entry = mock(com.minecolonies.api.colony.colonyEvents.descriptions.ICitizenEventDescription.class, RETURNS_DEEP_STUBS);
        when(entry.getEventTypeId()).thenReturn(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecolonies", type));
        when(entry.getCitizenName()).thenReturn(name);
        eventLog.add(entry);
    }

    /** A building event in MineColonies' log: {@code type} "building_built" or "building_upgraded". */
    void buildingEvent(String type, String path) {
        var entry = mock(com.minecolonies.api.colony.colonyEvents.descriptions.IBuildingEventDescription.class, RETURNS_DEEP_STUBS);
        when(entry.getEventTypeId()).thenReturn(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecolonies", type));
        when(entry.getBuildingName()).thenReturn("com.minecolonies.building." + path);
        when(entry.getLevel()).thenReturn(1);
        eventLog.add(entry);
    }

    /** Collects what the mod logs as warnings or errors while the stage runs. */
    private void watchLog() {
        var context = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        appender = new org.apache.logging.log4j.core.appender.AbstractAppender("stage", null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)
                        && event.getLoggerName().toLowerCase().contains("livingcolonists")) {
                    warnings.add(event.getLevel() + " " + event.getMessage().getFormattedMessage()
                            + (event.getThrown() == null ? "" : " " + event.getThrown()));
                }
            }
        };
        appender.start();
        context.getConfiguration().getRootLogger().addAppender(appender, null, null);
        context.updateLoggers();
    }

    /** The player leaves the colony, or comes back into it. */
    void playerAway(boolean away) {
        this.away = away;
    }

    /** The player earns an advancement, as the game would report it. */
    void earns(String id, String title) {
        me.bytefox.livingcolonists.events.ModEvents.BUS.post(
                new me.bytefox.livingcolonists.events.GameEvents.AdvancementEarned(player, id, title));
    }

    /** The player's rank in the colony, as {@link IPermissions} numbers it. */
    void rank(int id) {
        when(colony.getPermissions().getRank(player)).thenReturn(new Rank(id, "Rank " + id, false));
    }

    /** The player writes privately to the colonist named. */
    void playerWritesTo(String name, String message) {
        talk.sayPrivately(player, citizens.stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow(), message);
    }

    /** Requests for colonists' lines so far. */
    List<com.google.gson.JsonObject> replies() {
        return model.requests.stream().filter(FakeModel::isReply).toList();
    }

    /** The player writes to the colony chat. */
    void playerWritesToColony(String message) {
        talk.sayToColony(player, colony, message);
    }

    /** Advances the server tick by tick until {@code done}, running what the background handed over; false on timeout. */
    boolean runUntil(BooleanSupplier done, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            Runnable task;
            while ((task = mainThread.poll()) != null) {
                task.run();
            }
            if (done.getAsBoolean()) {
                return true;
            }
            tick++;
            gameTime++;
            talk.tick(server);
            memory.tick(server);
            bonds.tick(server);
            tickPause();
        }
        return false;
    }

    /**
     * One millisecond between ticks, whatever the system timer: on Windows {@code Thread.sleep(1)} lasts 1 to 16 ms
     * depending on what else runs, so a minute of game time took from 1 to 19 seconds and tests failed by chance.
     */
    /** Real time per tick: 1 ms for tests; a probe on the real model needs about real speed, or replies come too late. */
    long tickNanos = 1_000_000;

    private void tickPause() throws InterruptedException {
        long until = System.nanoTime() + tickNanos;
        while (System.nanoTime() < until) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            Thread.onSpinWait();
        }
    }

    /** Every roll of the mod's dice comes out as {@code roll} (0 to 1): 0 makes every "now and then" happen. */
    void dice(double roll) {
        me.bytefox.livingcolonists.Dice.use(new java.util.Random() {
            @Override
            public double nextDouble() {
                return roll;
            }

            @Override
            public int nextInt(int bound) {
                return (int) Math.min(bound - 1, Math.floor(roll * bound));
            }
        });
    }

    /** Runs {@code ticks} server ticks, whatever happens. */
    void runTicks(int ticks) throws InterruptedException {
        int end = tick + ticks;
        runUntil(() -> tick >= end, Duration.ofMinutes(5));
    }

    /** Game time jumps ahead by {@code ticks} at once, as if the colony had been playing meanwhile. */
    void skipTime(long ticks) {
        gameTime += ticks;
    }

    /** Game days go by, one server tick each: what happens once a game day happens once per day. */
    void passDays(int days) {
        for (int day = 0; day < days; day++) {
            gameTime += 24000;
            tick++;
            talk.tick(server);
        }
    }

    MinecraftServer server() {
        return server;
    }

    @Override
    public void close() {
        if (appender != null) {
            var context = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            context.getConfiguration().getRootLogger().removeAppender("stage");
            context.updateLoggers();
        }
        me.bytefox.livingcolonists.Dice.use(null);
        manager.close();
        model.close();
        // The mod's bus is static: this stage's services leave it, or every stage stays in memory with its mocks.
        try {
            Field handlers = me.bytefox.livingcolonists.events.ModEvents.class.getDeclaredField("handlers");
            handlers.setAccessible(true);
            ((Map<?, ?>) handlers.get(me.bytefox.livingcolonists.events.ModEvents.BUS)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

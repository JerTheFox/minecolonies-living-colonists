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
        when(colony.isCoordInColony(any(), any())).thenReturn(true);
        when(colony.getCitizenManager().getCitizens()).thenAnswer(invocation -> List.copyOf(citizens));
        when(colony.getCitizenManager().getCivilian(anyInt())).thenAnswer(invocation -> citizens.stream()
                .filter(c -> c.getId() == (int) invocation.getArgument(0)).findFirst().orElse(null));
        when(colony.getServerBuildingManager().getBuildings()).thenReturn(Map.of());
        when(colony.getRaiderManager().isRaided()).thenReturn(false);
        when(colony.getEventDescriptionManager().getEventDescriptions()).thenReturn(List.of());
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
        when(colonies.getColonyByPosFromWorld(any(), any())).thenReturn(colony);
        when(colonies.getColonyByDimension(anyInt(), any())).thenReturn(colony);

        LlmClient client = new LlmClient(HttpClient.newHttpClient(), new UsageStats(), List.of(Duration.ofMillis(5)), note -> { });
        PromptLibrary prompts = new PromptLibrary(directory);
        prompts.reload();
        ArchetypeLibrary archetypes = new ArchetypeLibrary(directory);
        archetypes.reload();
        PersonalityService personalities = new PersonalityService(prompts, archetypes, client);
        personalities.attach(PersonalityData.factory().constructor().get());
        bonds = new BondsService();
        bonds.attach(BondsData.factory().constructor().get());
        memory = new MemoryService(client, new EmbeddingClient(HttpClient.newHttpClient(), new UsageStats()), prompts, bonds);
        memory.attach(MemoryData.factory().constructor().get());
        talk = new TalkService(client, prompts, personalities, new PerceptionService(), memory, bonds);
        talk.attachInitiative(InitiativeData.factory().constructor().get());
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
            Thread.sleep(1);
        }
        return false;
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
        manager.close();
        model.close();
    }
}

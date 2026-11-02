package me.bytefox.livingcolonists.dev;

import java.util.Comparator;
import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.colony.Colonies;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Live runs in a real world (#64), server side: with {@code -Dlivingcolonists.liveRun=<name>}, takes the first player
 * who joins to the colony and puts a colonist in front of them; the client side ({@link LiveRunsClient}) plays the
 * part of the player and reports the result. Dev only: this source set is loaded by runClient and runServer and never
 * goes into the built jar.
 */
@EventBusSubscriber(modid = "livingcolonists")
public final class LiveRuns {
    private static final int SETTLE_TICKS = 200;
    /** The chat line that tells the client the colonist is in place, followed by their name. */
    static final String READY = "[live run] ready: ";

    private static int waited;
    private static boolean ready;
    private static ErrandChecks errandChecks;

    private LiveRuns() {
    }

    static String run() {
        return System.getProperty("livingcolonists.liveRun", "");
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        ModLog.info("Live run: dev hooks loaded{}", run().isEmpty() ? "" : ", run " + run());
        if (run().equals("nomodel")) {
            // The colony without a language model (#68).
            me.bytefox.livingcolonists.Config.USE_MODEL.set(false);
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (errandChecks != null) {
            errandChecks.beforeStop();
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (errandChecks != null) {
            errandChecks.tick();
        }
        // After a restart: no player needed, only the colony (#64).
        if (run().equals("errands-restart") && !ready && server.getTickCount() > SETTLE_TICKS) {
            Colonies.all().stream().findFirst().ifPresent(colony -> ErrandChecks.afterRestart(server, colony));
            ready = true;
            return;
        }
        if (run().isEmpty() || ready || server.getTickCount() % 20 != 0 || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if ((waited += 20) < SETTLE_TICKS) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        IColony colony = Colonies.at(player);
        if (colony == null) {
            Colonies.all().stream().findFirst().ifPresent(first -> {
                var center = first.getCenter();
                player.teleportTo(center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5);
            });
            return;
        }
        if (run().equals("errands")) {
            ModLog.info("Live run errands: starting with {} in {}", player.getGameProfile().getName(), colony.getName());
            errandChecks = new ErrandChecks(server, player, colony);
            ready = true;
            return;
        }
        if (run().equals("happiness")) {
            happiness(player, colony);
            ready = true;
            return;
        }
        if (run().equals("inventory")) {
            inventory(colony);
            ready = true;
            return;
        }
        // A colonist without a job stands still in front of the player instead of walking off to work.
        List<ICitizenData> adults = colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getEntity().isPresent() && !c.isChild())
                .sorted(Comparator.comparing(c -> c.getJob() != null)).toList();
        if (adults.isEmpty()) {
            ModLog.warn("Live run {}: FAIL, no colonists loaded near the player", run());
            ready = true;
            return;
        }
        ICitizenData colonist = adults.get(0);
        Vec3 front = player.position().add(player.getLookAngle().multiply(2.5, 0, 2.5));
        colonist.getEntity().get().teleportTo(front.x, player.getY(), front.z);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, colonist.getEntity().get().getEyePosition());
        ModLog.info("Live run {}: ready, {} stands in front of {}", run(), colonist.getName(),
                player.getGameProfile().getName());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(READY + colonist.getName()));
        ready = true;
    }

    /**
     * #61 on real MineColonies: a quarrel at home put on a colonist through our modifier lowers their happiness as
     * MineColonies counts it. (MineColonies drops modifiers it doesn't know when the world loads; putting them back is
     * covered by HappinessScenariosTest.)
     */
    private static void happiness(ServerPlayer player, IColony colony) {
        ICitizenData citizen = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).findFirst().orElse(null);
        if (citizen == null) {
            ModLog.info("Live run happiness: FAIL, no colonist");
            return;
        }
        var handler = citizen.getCitizenHappinessHandler();
        double before = handler.getHappiness(colony, citizen);
        me.bytefox.livingcolonists.bonds.Moods.neighbours(citizen, 0, 3);
        double after = handler.getHappiness(colony, citizen);
        boolean listed = handler.getModifiers().contains(me.bytefox.livingcolonists.bonds.Moods.NEIGHBOURS);
        ModLog.info("Live run happiness: {} {} -> {}, modifiers {}", citizen.getName(), before, after, handler.getModifiers());
        ModLog.info("Live run happiness (server): {}", listed && after < before ? "PASS, happiness " + before + " -> " + after
                : "FAIL, " + (listed ? "happiness didn't drop" : "the modifier isn't there"));
        // The client looks at the citizen's happiness window (LiveRunsClient).
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(READY + citizen.getName()));
    }

    /** What the test world has, to know which checks it can carry. */
    private static void inventory(IColony colony) {
        ModLog.info("Live run inventory: colony {} '{}', center {}", colony.getID(), colony.getName(), colony.getCenter());
        colony.getServerBuildingManager().getBuildings().values().forEach(b -> ModLog.info("Live run inventory: building {} level {} at {}",
                b.getBuildingType().getRegistryName().getPath(), b.getBuildingLevel(), b.getPosition()));
        colony.getCitizenManager().getCitizens().forEach(c -> ModLog.info("Live run inventory: citizen {} id {} job {} child {} loaded {}",
                c.getName(), c.getId(), c.getJob() == null ? "-" : c.getJob().getJobRegistryEntry().getKey().getPath(), c.isChild(),
                c.getEntity().isPresent()));
        colony.getPermissions().getPlayers().values().forEach(p -> ModLog.info("Live run inventory: member {} rank {}", p.getName(),
                p.getRank().getName()));
        ModLog.info("Live run inventory: PASS");
    }
}

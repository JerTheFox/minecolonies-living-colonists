/*
 * Idea and the walking loop from Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/ErrandManager.java. Changed: the colonist's own AI is paused through
 * MineColonies' public state machine (a blocking event to INACTIVE) instead of Talking Colonists'
 * reservations, every walk has a time limit, and only the kinds below are kept.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.AIEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIBlockingEventType;
import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import com.minecolonies.core.entity.pathfinding.navigation.EntityNavigationUtils;
import me.bytefox.livingcolonists.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Colonists walking on the player's word: coming over, following, waiting, going somewhere,
 * gathering, leading the way. While on a walk, a colonist's own routine is paused; when the walk
 * ends or runs out of time, the routine takes over again.
 */
public final class Walks {
    public enum Kind {
        /** Walk to the player, then wait by them. */
        TO_PLAYER,
        /** Keep close to the player. */
        FOLLOW,
        /** Stand still. */
        WAIT,
        /** Walk to a place, then wait there or go back to the routine. */
        TO_POS,
        /** Walk to a place ahead of the player, waiting when they fall behind. */
        LEAD
    }

    /** How often walks are steered: twice a second. */
    private static final int STEP_TICKS = 10;
    /** Close enough to the target, in blocks squared. */
    private static final double ARRIVED = 3 * 3;
    /** A follower or a guide stays this near the player, in blocks squared. */
    private static final double NEAR_PLAYER = 4 * 4;
    /** A guide waits when the player is further behind than this, in blocks squared. */
    private static final double FALLEN_BEHIND = 12 * 12;
    public static final long MINUTE = 60 * 20;

    private static final class Walk {
        final ICitizenData citizen;
        final UUID player;
        Kind kind;
        BlockPos target;
        long until;
        /** After arriving: wait this long there, or 0 to go back to the routine. */
        final long holdAfter;
        final String what;
        /** Runs once on arrival, for chained walks (a courier at the warehouse, then at the player). */
        Runnable arrived;

        Walk(ICitizenData citizen, UUID player, Kind kind, BlockPos target, long until, long holdAfter, String what) {
            this.citizen = citizen;
            this.player = player;
            this.kind = kind;
            this.target = target;
            this.until = until;
            this.holdAfter = holdAfter;
            this.what = what;
        }
    }

    /** What one step of a walk came to. */
    private enum Step { GOING, ARRIVED, OVER }

    /** By entity UUID; server thread only. */
    private final Map<UUID, Walk> walks = new HashMap<>();
    /** Entities whose AI already has our pause; a respawned entity gets it again. */
    private final Set<AbstractEntityCitizen> paused = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Starts a walk, replacing one the colonist is on.
     *
     * @param holdAfter for {@link Kind#TO_POS} and {@link Kind#TO_PLAYER}: how long to wait on arrival
     * @return false if the colonist isn't around to walk (too far, not loaded)
     */
    public boolean start(MinecraftServer server, ICitizenData citizen, UUID player, Kind kind, BlockPos target,
                         long duration, long holdAfter, String what) {
        return start(server, citizen, player, kind, target, duration, holdAfter, what, null);
    }

    /** The same, running {@code arrived} once the colonist gets there. */
    public boolean start(MinecraftServer server, ICitizenData citizen, UUID player, Kind kind, BlockPos target,
                         long duration, long holdAfter, String what, Runnable arrived) {
        var entity = citizen.getEntity();
        if (entity.isEmpty()) {
            return false;
        }
        pause(entity.get());
        Walk walk = new Walk(citizen, player, kind, target, server.getTickCount() + duration, holdAfter, what);
        walk.arrived = arrived;
        walks.put(entity.get().getUUID(), walk);
        ModLog.action("{} walks: {} {}", citizen.getName(), kind, what);
        return true;
    }

    /** Ends the colonist's walk: their routine takes over. */
    public boolean stop(ICitizenData citizen) {
        var entity = citizen.getEntity();
        if (entity.isEmpty() || walks.remove(entity.get().getUUID()) == null) {
            return false;
        }
        entity.get().getNavigation().stop();
        return true;
    }

    /** Ends every walk the player started; how many there were. */
    public int stopAll(UUID player) {
        return stopIf(walk -> player.equals(walk.player));
    }

    public boolean isWalking(ICitizenData citizen) {
        return citizen.getEntity().map(entity -> walks.containsKey(entity.getUUID())).orElse(false);
    }

    private int stopIf(Predicate<Walk> which) {
        int stopped = 0;
        for (Iterator<Map.Entry<UUID, Walk>> it = walks.entrySet().iterator(); it.hasNext(); ) {
            Walk walk = it.next().getValue();
            if (which.test(walk)) {
                it.remove();
                walk.citizen.getEntity().ifPresent(entity -> entity.getNavigation().stop());
                stopped++;
            }
        }
        return stopped;
    }

    private void pause(AbstractEntityCitizen entity) {
        if (entity instanceof EntityCitizen citizen && paused.add(entity)) {
            UUID id = entity.getUUID();
            citizen.getCitizenAI().addTransition(new AIEventTarget<IState>(AIBlockingEventType.AI_BLOCKING,
                    () -> walks.containsKey(id), CitizenAIState.INACTIVE, STEP_TICKS));
        }
    }

    public void tick(MinecraftServer server) {
        if (walks.isEmpty() || server.getTickCount() % STEP_TICKS != 0) {
            return;
        }
        long now = server.getTickCount();
        List<Runnable> arrivals = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Walk>> it = walks.entrySet().iterator(); it.hasNext(); ) {
            Walk walk = it.next().getValue();
            var found = walk.citizen.getEntity();
            // A raid ends every walk, so colonists can flee and guards fight as their own AI says.
            if (found.isEmpty() || !found.get().isAlive() || now > walk.until || walk.citizen.getColony().getRaiderManager().isRaided()) {
                it.remove();
                found.ifPresent(entity -> entity.getNavigation().stop());
                continue;
            }
            AbstractEntityCitizen entity = found.get();
            ServerPlayer player = walk.player == null ? null : server.getPlayerList().getPlayer(walk.player);
            boolean playerHere = player != null && player.level() == entity.level();
            Step step = step(walk, entity, player, playerHere, now);
            if (step == Step.ARRIVED && walk.kind != Kind.WAIT || step == Step.OVER) {
                it.remove();
                entity.getNavigation().stop();
            }
            // Only a real arrival goes on with a chain: a walk given up (the player left) never hands anything over.
            if (step == Step.ARRIVED && walk.arrived != null) {
                arrivals.add(walk.arrived);
                walk.arrived = null;
            }
        }
        // After the loop: an arrival may start the next walk of the same colonist.
        arrivals.forEach(Runnable::run);
    }

    /** One step of a walk. */
    private static Step step(Walk walk, AbstractEntityCitizen entity, ServerPlayer player, boolean playerHere, long now) {
        switch (walk.kind) {
            case WAIT -> {
                entity.getNavigation().stop();
                if (playerHere) {
                    entity.getLookControl().setLookAt(player);
                }
                return Step.GOING;
            }
            case FOLLOW -> {
                if (!playerHere) {
                    return Step.OVER;
                }
                if (entity.distanceToSqr(player) > NEAR_PLAYER) {
                    EntityNavigationUtils.walkToPos(entity, player.blockPosition(), 2, true);
                } else {
                    entity.getNavigation().stop();
                    entity.getLookControl().setLookAt(player);
                }
                return Step.GOING;
            }
            case TO_PLAYER -> {
                if (!playerHere) {
                    return Step.OVER;
                }
                if (entity.distanceToSqr(player) <= NEAR_PLAYER) {
                    arrive(walk, now);
                    return Step.ARRIVED;
                }
                EntityNavigationUtils.walkToPos(entity, player.blockPosition(), 2, true);
                return Step.GOING;
            }
            case TO_POS, LEAD -> {
                if (entity.blockPosition().distSqr(walk.target) <= ARRIVED) {
                    arrive(walk, now);
                    return Step.ARRIVED;
                }
                if (walk.kind == Kind.LEAD && playerHere && entity.distanceToSqr(player) > FALLEN_BEHIND) {
                    entity.getNavigation().stop();
                    entity.getLookControl().setLookAt(player);
                    return Step.GOING;
                }
                EntityNavigationUtils.walkToPos(entity, walk.target, 2, true);
                return Step.GOING;
            }
        }
        return Step.GOING;
    }

    /** On arrival the colonist waits there a while, or goes back to the routine at once. */
    private static void arrive(Walk walk, long now) {
        if (walk.holdAfter > 0) {
            walk.kind = Kind.WAIT;
            walk.until = Math.min(walk.until, now + walk.holdAfter);
        }
    }

    /** The AI pauses stay on the entities (they only act while a walk is on), so they aren't added twice. */
    public void clear() {
        walks.clear();
    }
}

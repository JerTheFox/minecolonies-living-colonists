/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3, src/me/lovkar/errands/
 * GuardSettings.java, ErrandManager.java (guard follow, defence, kickGuardAI) and tools/GuardMeAction,
 * DefendHereAction, SummonGuardsAction, RedAlertAction, PatrolHereAction. Changed: one place keeps
 * the towers' old settings and puts them back (stand down, undo or after a while); the defence line
 * along the colony's border is left out, guards hold the player's spot instead.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.statemachine.AIOneTimeEventTarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.settings.GuardFollowModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardPatrolModeSetting;
import com.minecolonies.core.colony.buildings.modules.settings.GuardTaskSetting;
import com.minecolonies.core.colony.buildings.modules.settings.StringSetting;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.llm.ToolSpec;
import me.bytefox.livingcolonists.talk.TalkService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Military orders to the colony's guard towers. */
public final class Guards {
    /** Following the player or holding a spot ends by itself after this long. */
    private static final long DUTY_TICKS = 20 * Walks.MINUTE;

    /** A tower's settings before we changed them. */
    private record Before(AbstractBuildingGuards tower, String task, String follow, UUID player, long until) {
    }

    private final Map<BlockPos, Before> changed = new HashMap<>();
    private final GuardTally tally;

    public Guards(GuardTally tally) {
        this.tally = tally;
    }

    public List<Errand> errands() {
        return List.of(
                order("guard_me", "Guards escort and protect the player (\"guard me\"): your own tower if you are a "
                        + "guard, or every tower.", Map.of("who", "me (your tower), or all"),
                        (c, a) -> towers(c, Errand.string(a, "who")), "livingcolonists.order.guard.follow",
                        (tower, player) -> follow(tower, player)),
                order("defend_here", "Guards come and hold the player's spot (\"guards, to me\", \"defend here\").",
                        Map.of("who", "me (your tower), or all"), (c, a) -> towers(c, Errand.string(a, "who")),
                        "livingcolonists.order.guard.defend", (tower, player) -> hold(tower, player.blockPosition())),
                order("red_alert", "Every guard to their tower, on alert (\"red alert\").", Map.of(),
                        (c, a) -> towers(c, "all"), "livingcolonists.order.guard.alert",
                        (tower, player) -> hold(tower, tower.getPosition())),
                new Patrol(),
                new StandDown(),
                new Leaderboard());
    }

    private interface Pick {
        List<AbstractBuildingGuards> towers(Errand.Context context, JsonObject arguments);
    }

    private interface Change {
        boolean apply(AbstractBuildingGuards tower, ServerPlayer player);
    }

    /** The towers an order is for: the asker's own, or all of them. */
    private static List<AbstractBuildingGuards> towers(Errand.Context context, String who) {
        List<AbstractBuildingGuards> towers = new ArrayList<>();
        IBuilding own = context.citizen().getWorkBuilding();
        if (!who.toLowerCase().startsWith("all") && own instanceof AbstractBuildingGuards tower) {
            towers.add(tower);
            return towers;
        }
        for (IBuilding building : context.colony().getServerBuildingManager().getBuildings().values()) {
            if (building instanceof AbstractBuildingGuards tower && building.getBuildingLevel() > 0) {
                towers.add(tower);
            }
        }
        return towers;
    }

    private Errand order(String name, String description, Map<String, String> parameters, Pick pick, String question,
                         Change change) {
        ToolSpec spec = Errands.orderSpec(name, description, parameters);
        return new Errand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolSpec spec() {
                return spec;
            }

            @Override
            public boolean isOrder() {
                return true;
            }

            @Override
            public Result run(Context context, JsonObject arguments) {
                if (!name.equals("guard_me") && !TalkService.isInside(context.player(), context.colony())) {
                    return new Result.Answer(Errand.error("The guards hold spots inside the colony only: the player has "
                            + "to be within its borders."));
                }
                List<AbstractBuildingGuards> towers = pick.towers(context, arguments);
                if (towers.isEmpty()) {
                    return new Result.Answer(Errand.error("The colony has no guard tower with guards."));
                }
                return new Result.Proposal(new Order(name, name + " for " + towers.size() + " towers",
                        Component.translatable(question, towers.size()), player -> {
                    List<AbstractBuildingGuards> done = new ArrayList<>();
                    long until = player.server.getTickCount() + DUTY_TICKS;
                    for (AbstractBuildingGuards tower : towers) {
                        remember(tower, player.getUUID(), until);
                        if (change.apply(tower, player)) {
                            kick(tower);
                            done.add(tower);
                        } else {
                            restore(tower.getPosition());
                        }
                    }
                    if (done.isEmpty()) {
                        return new Outcome(false, Component.translatable("livingcolonists.order.guard.unsupported"), null);
                    }
                    return new Outcome(true, Component.translatable("livingcolonists.order.guard.done", done.size()),
                            () -> done.forEach(tower -> restore(tower.getPosition())));
                }));
            }
        };
    }

    private void remember(AbstractBuildingGuards tower, UUID player, long until) {
        changed.computeIfAbsent(tower.getPosition(), pos -> new Before(tower, value(tower.getSetting(AbstractBuildingGuards.GUARD_TASK)),
                value(tower.getSetting(AbstractBuildingGuards.FOLLOW_MODE)), player, until));
    }

    private static boolean follow(AbstractBuildingGuards tower, ServerPlayer player) {
        set(tower.getSetting(AbstractBuildingGuards.FOLLOW_MODE), GuardFollowModeSetting.TIGHT);
        if (!set(tower.getSetting(AbstractBuildingGuards.GUARD_TASK), GuardTaskSetting.FOLLOW)) {
            return false;
        }
        tower.setPlayerToFollow(player);
        tower.markDirty();
        return true;
    }

    private static boolean hold(AbstractBuildingGuards tower, BlockPos spot) {
        if (!set(tower.getSetting(AbstractBuildingGuards.GUARD_TASK), GuardTaskSetting.GUARD)) {
            return false;
        }
        tower.setGuardPos(spot);
        tower.markDirty();
        return true;
    }

    /** Puts a tower back the way it was before our orders. */
    private boolean restore(BlockPos pos) {
        Before before = changed.remove(pos);
        if (before == null) {
            return false;
        }
        AbstractBuildingGuards tower = before.tower();
        String task = before.task() == null || before.task().equals(GuardTaskSetting.FOLLOW) ? GuardTaskSetting.PATROL : before.task();
        set(tower.getSetting(AbstractBuildingGuards.GUARD_TASK), task);
        if (before.follow() != null) {
            set(tower.getSetting(AbstractBuildingGuards.FOLLOW_MODE), before.follow());
        }
        if (GuardTaskSetting.GUARD.equals(task)) {
            tower.setGuardPos(tower.getPosition());
        }
        tower.markDirty();
        kick(tower);
        return true;
    }

    /** Duties end by themselves after a while, or when the player leaves. */
    public void tick(MinecraftServer server) {
        if (changed.isEmpty() || server.getTickCount() % 100 != 0) {
            return;
        }
        long now = server.getTickCount();
        List<BlockPos> over = changed.entrySet().stream()
                .filter(e -> now > e.getValue().until() || server.getPlayerList().getPlayer(e.getValue().player()) == null)
                .map(Map.Entry::getKey).toList();
        for (BlockPos pos : over) {
            restore(pos);
            LivingColonists.LOGGER.info("Guard tower at {} is back to its own duty", pos.toShortString());
        }
    }

    public void clear() {
        new ArrayList<>(changed.keySet()).forEach(this::restore);
        changed.clear();
    }

    /** Makes the tower's guards think again at once, as MineColonies' own follow switch does. */
    private static void kick(AbstractBuildingGuards tower) {
        for (ICitizenData guard : tower.getAllAssignedCitizen()) {
            AbstractJobGuard<?> job = guard.getJob(AbstractJobGuard.class);
            if (job != null && job.getWorkerAI() instanceof AbstractEntityAIGuard<?, ?> ai) {
                ai.registerTarget(new AIOneTimeEventTarget(AIWorkerState.PREPARING));
            }
        }
    }

    private static String value(StringSetting setting) {
        if (setting == null) {
            return null;
        }
        int index = setting.getCurrentIndex();
        List<String> options = setting.getSettings();
        return index >= 0 && index < options.size() ? options.get(index) : null;
    }

    /** Sets only an option this tower offers: setting another one breaks the setting for good. */
    private static boolean set(StringSetting setting, String value) {
        if (setting == null || value == null || !setting.getSettings().contains(value)) {
            return false;
        }
        setting.set(value);
        return true;
    }

    /** A guard sets the player's spot as a patrol point of their tower; undo goes back to automatic patrols. */
    private static final class Patrol implements Errand {
        @Override
        public String name() {
            return "patrol_here";
        }

        @Override
        public ToolSpec spec() {
            return Errands.orderSpec(name(), "If you are a guard: add the player's spot to your tower's patrol route "
                    + "(\"patrol here\"); with new, start a new route from it.", Map.of("how", "add, or new"));
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            if (!(context.citizen().getWorkBuilding() instanceof AbstractBuildingGuards tower)) {
                return new Result.Answer(Errand.error("You aren't a guard: only guards patrol."));
            }
            if (!TalkService.isInside(context.player(), context.colony())) {
                return new Result.Answer(Errand.error("Patrol points are inside the colony only."));
            }
            boolean fresh = Errand.string(arguments, "how").toLowerCase().startsWith("new");
            BlockPos spot = context.player().blockPosition();
            return new Result.Proposal(new Order(name(), "patrol point at " + spot.toShortString(),
                    Component.translatable("livingcolonists.order.patrol.ask"), player -> {
                if (fresh) {
                    tower.resetPatrolTargets();
                }
                tower.addPatrolTarget(spot);
                set(tower.getSetting(AbstractBuildingGuards.PATROL_MODE), GuardPatrolModeSetting.MANUAL);
                set(tower.getSetting(AbstractBuildingGuards.GUARD_TASK), GuardTaskSetting.PATROL);
                tower.markDirty();
                kick(tower);
                return new Outcome(true, Component.translatable("livingcolonists.order.patrol.done"), () -> {
                    tower.resetPatrolTargets();
                    set(tower.getSetting(AbstractBuildingGuards.PATROL_MODE), GuardPatrolModeSetting.AUTO);
                    tower.markDirty();
                    kick(tower);
                });
            }));
        }
    }

    /** "Stand down": every tower the player sent on duty goes back to its own. */
    private final class StandDown implements Errand {
        @Override
        public String name() {
            return "stand_down";
        }

        @Override
        public ToolSpec spec() {
            return Errands.commandSpec(name(), "Guards the player sent to escort, defend or alert go back to their "
                    + "own duty (\"stand down\", \"dismissed\").", Map.of());
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            UUID player = context.player().getUUID();
            List<BlockPos> mine = changed.values().stream().filter(before -> player.equals(before.player()))
                    .map(before -> before.tower().getPosition()).toList();
            mine.forEach(Guards.this::restore);
            JsonObject reply = new JsonObject();
            reply.addProperty("success", true);
            reply.addProperty("info", mine.isEmpty() ? "No tower was on a duty from the player." : mine.size()
                    + " towers are back to their own duty.");
            return new Result.Answer(reply);
        }
    }

    /** Which guards have fought best, by the colony's tally of kills. */
    private final class Leaderboard implements Errand {
        @Override
        public String name() {
            return "guard_leaderboard";
        }

        @Override
        public ToolSpec spec() {
            return Errands.spec(name(), "Which guards have fought best: how many enemies each has killed.", null, null);
        }

        @Override
        public boolean isOrder() {
            return false;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            return new Result.Answer(tally.board(context.colony()));
        }
    }
}

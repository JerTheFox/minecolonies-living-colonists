package me.bytefox.livingcolonists.friendship;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.Dice;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.debug.Counters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Help in trouble (#67): when a player is badly hurt in their colony, a friend nearby helps by their job, from
 * {@link HelpTable}: a healer heals, a cook hands over food from their own stash, a guard goes for the attacker, a builder
 * puts up a short wall, a smith patches the armour, anyone else calls a guard or a healer. What they hand over is made
 * from nothing. No model here. Server thread only.
 */
public final class Help {
    /** How much a healer heals, in half-hearts; a healer someone called heals half as much. */
    private static final float HEAL = 8;
    /** How far a guard comes from when called, in blocks. */
    private static final int CALL_RADIUS = 32;
    /** Below this light a miner's torches are welcome. */
    private static final int DARK = 7;

    private record Wall(ServerLevel level, List<BlockPos> blocks, BlockState state, long until) {
    }

    private final BondsService bonds;
    private final Map<String, Long> helped = new HashMap<>();
    private final Map<UUID, Long> playerHelped = new HashMap<>();
    private final Deque<Wall> walls = new ArrayDeque<>();

    public Help(BondsService bonds) {
        this.bonds = bonds;
    }

    /** The player was hurt; {@code attacker} may be null. */
    public void hurt(ServerPlayer player, LivingEntity attacker) {
        if (!Config.HELP_IN_TROUBLE.get() || player.isDeadOrDying()
                || player.getHealth() > player.getMaxHealth() * HelpTable.healthShare()) {
            return;
        }
        IColony colony = Colonies.at(player);
        long now = player.server.getTickCount();
        if (colony == null || now - playerHelped.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < HelpTable.playerCooldownTicks()) {
            return;
        }
        LivingEntity enemy = attacker instanceof Player || attacker instanceof AbstractEntityCitizen ? null : attacker;
        int friend = FriendshipLevels.byId("friend").map(FriendshipLevels.Level::rank).orElse(Integer.MAX_VALUE);
        ICitizenData best = null;
        HelpTable.Action bestAction = null;
        double bestDistance = Double.MAX_VALUE;
        for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
            if (citizen.isChild() || citizen.getEntity().isEmpty() || !citizen.getEntity().get().isAlive()
                    || FriendshipLevels.level(bonds.playerRapport(citizen, player.getUUID())).rank() < friend
                    || now - helped.getOrDefault(key(citizen, player), Long.MIN_VALUE / 2) < HelpTable.cooldownTicks()) {
                continue;
            }
            double distance = citizen.getEntity().get().blockPosition().distSqr(player.blockPosition());
            if (distance > (double) HelpTable.radius() * HelpTable.radius()) {
                continue;
            }
            HelpTable.Action action = possible(HelpTable.action(job(citizen)), enemy, player);
            if (best == null || HelpTable.rank(action.kind()) < HelpTable.rank(bestAction.kind())
                    || HelpTable.rank(action.kind()) == HelpTable.rank(bestAction.kind()) && distance < bestDistance) {
                best = citizen;
                bestAction = action;
                bestDistance = distance;
            }
        }
        if (best == null) {
            return;
        }
        boolean done = help(best, bestAction, player, enemy, colony);
        if (done) {
            helped.put(key(best, player), now);
            playerHelped.put(player.getUUID(), now);
            Counters.add("help." + bestAction.kind());
            ModLog.action("{} helped {} in trouble: {}", best.getName(), player.getGameProfile().getName(), bestAction.kind());
        }
    }

    /** What the colonist can do here: a guard with nobody to fight, or torches in daylight, come down to a call. */
    private static HelpTable.Action possible(HelpTable.Action action, LivingEntity enemy, ServerPlayer player) {
        boolean dark = player.level().getMaxLocalRawBrightness(player.blockPosition()) < DARK;
        if ((action.kind().equals("fight") || action.kind().equals("wall")) && enemy == null
                || action.kind().equals("torches") && !dark) {
            return new HelpTable.Action("call", List.of());
        }
        return action;
    }

    private boolean help(ICitizenData citizen, HelpTable.Action action, ServerPlayer player, LivingEntity enemy, IColony colony) {
        String name = citizen.getName();
        switch (action.kind()) {
            case "heal" -> heal(player, HEAL);
            case "give", "torches" -> {
                List<ItemStack> items = action.items(Dice.random());
                if (items.isEmpty()) {
                    return false;
                }
                items.forEach(stack -> give(player, stack));
            }
            case "repair" -> {
                boolean any = false;
                for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    ItemStack armour = player.getItemBySlot(slot);
                    if (armour.isDamageableItem() && armour.isDamaged()) {
                        armour.setDamageValue(Math.max(0, armour.getDamageValue() - armour.getMaxDamage() / 4));
                        any = true;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            case "fight" -> attack(citizen.getEntity().get(), enemy);
            case "wall" -> wall(player, enemy);
            default -> {
                return call(player, enemy, colony, name);
            }
        }
        player.displayClientMessage(Component.translatable("livingcolonists.help." + action.kind(), name), true);
        return true;
    }

    /** Anyone else calls for help: the nearest guard goes for the attacker, else the nearest healer heals a little. */
    private boolean call(ServerPlayer player, LivingEntity enemy, IColony colony, String caller) {
        AbstractEntityCitizen nearest = null;
        double distance = (double) CALL_RADIUS * CALL_RADIUS;
        boolean guard = enemy != null;
        for (ICitizenData other : colony.getCitizenManager().getCitizens()) {
            if (other.getJob() == null || other.getEntity().isEmpty() || !other.getEntity().get().isAlive()) {
                continue;
            }
            boolean fits = guard ? other.getJob().isGuard() : job(other).equals("healer");
            double d = other.getEntity().get().blockPosition().distSqr(player.blockPosition());
            if (fits && d < distance) {
                nearest = other.getEntity().get();
                distance = d;
            }
        }
        if (nearest == null) {
            return false;
        }
        if (guard) {
            attack(nearest, enemy);
        } else {
            heal(player, HEAL / 2);
        }
        String who = nearest.getCitizenData() == null ? "" : nearest.getCitizenData().getName();
        player.displayClientMessage(Component.translatable(guard ? "livingcolonists.help.call.guard" : "livingcolonists.help.call.healer",
                caller, who), true);
        return true;
    }

    private static void heal(ServerPlayer player, float amount) {
        player.heal(amount);
        for (MobEffectInstance effect : new ArrayList<>(player.getActiveEffects())) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                player.removeEffect(effect.getEffect());
            }
        }
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            player.drop(stack, false);
        }
    }

    /** A guard puts the attacker at the top of its threat table, as MineColonies' guards do for an attacked colonist. */
    private static void attack(AbstractEntityCitizen guard, LivingEntity enemy) {
        if (guard instanceof IThreatTableEntity threats) {
            threats.getThreatTable().addThreat(enemy, 100);
        }
    }

    /** A short wall, three wide and two high, between the player and the attacker, on air only; taken down later. */
    private void wall(ServerPlayer player, LivingEntity enemy) {
        ServerLevel level = player.serverLevel();
        Direction toward = Direction.getNearest(enemy.getX() - player.getX(), 0, enemy.getZ() - player.getZ());
        BlockPos middle = player.blockPosition().relative(toward, 2);
        Direction side = toward.getClockWise();
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(HelpTable.wallBlock()));
        BlockState state = (block == Blocks.AIR ? Blocks.COBBLESTONE : block).defaultBlockState();
        List<BlockPos> placed = new ArrayList<>();
        for (int across = -1; across <= 1; across++) {
            for (int up = 0; up <= 1; up++) {
                BlockPos pos = middle.relative(side, across).above(up);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlockAndUpdate(pos, state);
                    placed.add(pos);
                }
            }
        }
        walls.addLast(new Wall(level, placed, state, player.server.getTickCount() + HelpTable.wallTicks()));
    }

    /** Takes down the walls whose time is up, block by block where they still stand. */
    public void tick(MinecraftServer server) {
        while (!walls.isEmpty() && walls.peekFirst().until() <= server.getTickCount()) {
            Wall wall = walls.removeFirst();
            for (BlockPos pos : wall.blocks()) {
                if (wall.level().getBlockState(pos) == wall.state()) {
                    wall.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Before the world is saved: walls come down now, so none stays behind. */
    public void beforeStop(MinecraftServer server) {
        walls.forEach(wall -> wall.blocks().forEach(pos -> {
            if (wall.level().getBlockState(pos) == wall.state()) {
                wall.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }));
        walls.clear();
    }

    private static String key(ICitizenData citizen, ServerPlayer player) {
        return citizen.getColony().getID() + ":" + citizen.getId() + ":" + player.getUUID();
    }

    private static String job(ICitizenData citizen) {
        return citizen.getJob() == null ? "" : citizen.getJob().getJobRegistryEntry().getKey().getPath();
    }
}

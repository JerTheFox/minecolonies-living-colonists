/*
 * Based on Colonist Errands by Lovkar, GPL-3.0-or-later:
 * https://github.com/Lovkar-Squid/colonist-errands, tag v3.0.0-beta.3,
 * src/me/lovkar/errands/GuardGearCheck.java, tools/GuardGearAction.java and tools/ArmGuardsAction.java.
 * Changed: answers are JSON, arming is an order the player confirms, and undo cancels what it asked for.
 */
package me.bytefox.livingcolonists.errands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.jobs.guard.JobCavalry;
import com.minecolonies.core.colony.jobs.guard.JobHuscarl;
import com.minecolonies.core.colony.jobs.guard.JobKnight;
import com.minecolonies.core.colony.jobs.guard.JobMarksman;
import com.minecolonies.core.colony.jobs.guard.JobRanger;
import me.bytefox.livingcolonists.llm.ToolSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * Whether the guards are armed, and an order to request whatever they lack. The checks mirror the
 * game's own: armour from the citizen's inventory (the guard AI clears the entity's vanilla slots),
 * the weapon by the equipment-level test the guard AI uses, capped by the hut's level.
 */
public final class GuardGear {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int MAX_GUARDS = 8;
    /** The best equipment level, for huts that don't cap it. */
    private static final int BEST = 5;

    private GuardGear() {
    }

    public static List<Errand> all() {
        return List.of(new Check(), new Arm());
    }

    /** What one guard lacks, in words, and the requests that would fill it. */
    private record Lack(ICitizenData guard, IBuilding hut, List<String> missing, List<EquipmentTypeEntry> types) {
    }

    private static List<Lack> lacks(IColony colony) {
        List<Lack> lacks = new ArrayList<>();
        for (ICitizenData guard : colony.getCitizenManager().getCitizens()) {
            IJob<?> job = guard.getJob();
            IBuilding hut = guard.getWorkBuilding();
            InventoryCitizen inventory = guard.getInventory();
            if (job == null || !job.isGuard() || hut == null || inventory == null) {
                continue;
            }
            int maxLevel = hut.getMaxEquipmentLevel();
            List<String> missing = new ArrayList<>();
            List<EquipmentTypeEntry> types = new ArrayList<>();
            for (EquipmentSlot slot : ARMOR) {
                if (ItemStackUtils.isEmpty(inventory.getArmorInSlot(slot))) {
                    missing.add(slot.getName());
                    types.add(armor(slot));
                }
            }
            EquipmentTypeEntry weapon = weapon(job);
            if (weapon != null && !InventoryUtils.hasItemHandlerEquipmentWithLevel(inventory, weapon, 0, maxLevel)) {
                missing.add(weapon.getDisplayName().getString().toLowerCase());
                types.add(weapon);
            }
            if ((job instanceof JobKnight || job instanceof JobCavalry)
                    && InventoryUtils.findFirstSlotInItemHandlerWith(inventory, Items.SHIELD) == -1) {
                missing.add("shield");
            }
            lacks.add(new Lack(guard, hut, missing, types));
        }
        return lacks;
    }

    private static EquipmentTypeEntry weapon(IJob<?> job) {
        if (job instanceof JobHuscarl) {
            return ModEquipmentTypes.axe.get();
        }
        if (job instanceof JobKnight) {
            return ModEquipmentTypes.sword.get();
        }
        if (job instanceof JobMarksman) {
            return ModEquipmentTypes.crossbow.get();
        }
        if (job instanceof JobRanger) {
            return ModEquipmentTypes.bow.get();
        }
        if (job instanceof JobCavalry) {
            return ModEquipmentTypes.spear.get();
        }
        return null;
    }

    private static EquipmentTypeEntry armor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> ModEquipmentTypes.helmet.get();
            case CHEST -> ModEquipmentTypes.chestplate.get();
            case LEGS -> ModEquipmentTypes.leggings.get();
            default -> ModEquipmentTypes.boots.get();
        };
    }

    private static final class Check implements Errand {
        @Override
        public String name() {
            return "guard_gear";
        }

        @Override
        public ToolSpec spec() {
            return Errands.spec(name(), "Whether the colony's guards are armed: who lacks armour, a weapon or a shield.",
                    null, null);
        }

        @Override
        public boolean isOrder() {
            return false;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            List<Lack> lacks = lacks(context.colony());
            JsonObject reply = new JsonObject();
            reply.addProperty("success", true);
            reply.addProperty("guards", lacks.size());
            JsonArray lacking = new JsonArray();
            lacks.stream().filter(lack -> !lack.missing().isEmpty()).limit(MAX_GUARDS).forEach(lack -> lacking.add(
                    lack.guard().getName() + " (hut level " + lack.hut().getBuildingLevel() + ") lacks "
                            + String.join(", ", lack.missing())));
            reply.add("missing_gear", lacking);
            if (!lacking.isEmpty()) {
                reply.addProperty("info", "Guards ask for their gear through the colony's requests: usually nobody "
                        + "crafted it or a courier hasn't brought it yet, and a hut only takes gear up to its own level.");
            }
            return new Result.Answer(reply);
        }
    }

    private static final class Arm implements Errand {
        @Override
        public String name() {
            return "arm_guards";
        }

        @Override
        public ToolSpec spec() {
            return Errands.orderSpec(name(), "Ask the colony for every piece of armour and weapon its guards lack, "
                    + "through its requests (\"arm the guards\").", Map.of());
        }

        @Override
        public boolean isOrder() {
            return true;
        }

        @Override
        public Result run(Context context, JsonObject arguments) {
            IColony colony = context.colony();
            List<Lack> lacks = lacks(colony).stream().filter(lack -> !lack.types().isEmpty()).toList();
            if (lacks.isEmpty()) {
                return new Result.Answer(Errand.error("Every guard has armour and a weapon: nothing to ask for."));
            }
            int pieces = lacks.stream().mapToInt(lack -> lack.types().size()).sum();
            return new Result.Proposal(new Order(name(), "ask for " + pieces + " missing pieces of guard gear",
                    Component.translatable("livingcolonists.order.arm.ask", pieces, lacks.size()), player -> {
                List<IToken<?>> asked = new ArrayList<>();
                int already = 0;
                for (Lack lack : lacks) {
                    int maxLevel = Math.min(BEST, lack.hut().getMaxEquipmentLevel());
                    for (EquipmentTypeEntry type : lack.types()) {
                        if (requested(lack, type)) {
                            already++;
                        } else {
                            asked.add(lack.hut().createRequest(lack.guard(), new Tool(type, 0, maxLevel), false));
                        }
                    }
                }
                if (asked.isEmpty()) {
                    return new Outcome(false, Component.translatable("livingcolonists.order.arm.already", already), null);
                }
                return new Outcome(true, Component.translatable("livingcolonists.order.arm.done", asked.size(), already),
                        () -> asked.forEach(token -> colony.getRequestManager().updateRequestState(token, RequestState.CANCELLED)));
            }));
        }

        private static boolean requested(Lack lack, EquipmentTypeEntry type) {
            for (IRequest<?> request : lack.hut().getOpenRequests(lack.guard().getId())) {
                if (request.getRequest() instanceof Tool tool && tool.getEquipmentType() == type) {
                    return true;
                }
            }
            return false;
        }
    }
}

package me.bytefox.livingcolonists.friendship;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.colony.Colonies;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Friendship in a colonist's work (#67), without a mixin: friends of the players walk faster and learn their main skill
 * faster, colonists who hold a grudge walk slower and learn slower ({@link WorkBonus}, from the average rapport of the
 * players who know them). The walking speed is a transient attribute modifier, gone with the mod; the extra experience
 * is added (or taken) as MineColonies' own experience comes in. Server thread only.
 */
public final class WorkBoost {
    /** How often colonists are looked at, in server ticks (ten seconds). */
    private static final int CHECK = 200;
    static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath("livingcolonists", "friendship");

    private final BondsService bonds;
    /** The main skill's experience as last seen, by colonist; not saved. */
    private final Map<String, Double> seen = new HashMap<>();
    private boolean warned;

    public WorkBoost(BondsService bonds) {
        this.bonds = bonds;
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % CHECK != 0) {
            return;
        }
        for (IColony colony : Colonies.all()) {
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                try {
                    apply(citizen);
                } catch (RuntimeException | LinkageError e) {
                    if (!warned) {
                        warned = true;
                        ModLog.warn("Couldn't apply friendship to {}'s work", citizen.getName(), e);
                    }
                }
            }
        }
    }

    /** The share of the bonus the colonist gets now: -1 to 1; 0 with nobody who knows them. */
    public double fraction(ICitizenData citizen) {
        List<Integer> rapport = bonds.rapportWithPlayers(citizen);
        if (rapport.isEmpty()) {
            return 0;
        }
        return WorkBonus.fraction((int) Math.round(rapport.stream().mapToInt(Integer::intValue).average().orElse(0)));
    }

    private void apply(ICitizenData citizen) {
        double fraction = citizen.isChild() ? 0 : fraction(citizen);
        citizen.getEntity().ifPresent(entity -> speed(entity, fraction * Config.FRIENDSHIP_WALK_PERCENT.get() / 100.0));
        experience(citizen, fraction * Config.FRIENDSHIP_XP_PERCENT.get() / 100.0);
    }

    private static void speed(AbstractEntityCitizen entity, double amount) {
        AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        AttributeModifier now = speed.getModifier(SPEED);
        if (now != null && now.amount() == amount) {
            return;
        }
        speed.removeModifier(SPEED);
        if (amount != 0) {
            speed.addTransientModifier(new AttributeModifier(SPEED, amount, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** Adds (or takes) a share of the experience the colonist earned in their main skill since the last look. */
    private void experience(ICitizenData citizen, double share) {
        if (citizen.getJob() == null || !(citizen.getJob().getWorkModule() instanceof WorkerBuildingModule module)) {
            return;
        }
        Skill skill = module.getPrimarySkill();
        var data = citizen.getCitizenSkillHandler().getSkills().get(skill);
        if (data == null) {
            return;
        }
        String key = citizen.getColony().getID() + ":" + citizen.getId() + ":" + skill;
        double now = data.getExperience();
        Double before = seen.put(key, now);
        // A level-up starts the count again: nothing to add this time.
        if (before == null || now <= before || share == 0) {
            return;
        }
        double extra = (now - before) * Math.abs(share);
        if (share > 0) {
            citizen.getCitizenSkillHandler().addXpToSkill(skill, extra, citizen);
        } else {
            citizen.getCitizenSkillHandler().removeXpFromSkill(skill, extra, citizen);
        }
        seen.put(key, citizen.getCitizenSkillHandler().getSkills().get(skill).getExperience());
    }

    public void clear() {
        seen.clear();
    }
}

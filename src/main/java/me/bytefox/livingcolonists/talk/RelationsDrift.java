package me.bytefox.livingcolonists.talk;

import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.bytefox.livingcolonists.ModLog;
import me.bytefox.livingcolonists.bonds.BondsService;
import me.bytefox.livingcolonists.bonds.Drift;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.memory.MemoryService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Once a game day, relations between colonists who work or live together drift from real life (#59), as
 * {@link Drift} decides; crossing into friends or at odds is a fact both remember. No requests to the model.
 * Server thread only.
 */
final class RelationsDrift {
    private final TalkService talk;
    private final ColonyThreads threads;
    private final BondsService bonds;
    private final MemoryService memory;

    RelationsDrift(TalkService talk, ColonyThreads threads, BondsService bonds, MemoryService memory) {
        this.talk = talk;
        this.threads = threads;
        this.bonds = bonds;
        this.memory = memory;
    }

    /** The game day relations last drifted (#59). */
    private long driftDay = -1;

    /**
     * Once a game day, relations between colonists drift from real life (#59): coworkers grow closer,
     * housemates closer or apart by their characters. Crossing into friends or at odds is a fact for both.
     */
    void tick(MinecraftServer server) {
        long day = server.overworld().getGameTime() / 24000L;
        if (day == driftDay) {
            return;
        }
        boolean first = driftDay < 0;
        driftDay = day;
        if (first) {
            // After a start MineColonies has dropped our happiness modifiers: friends and quarrels count again at once.
            Colonies.all().stream().filter(talk::active).forEach(this::neighbourMoods);
            return;
        }
        for (IColony colony : Colonies.all()) {
            if (!talk.active(colony)) {
                continue;
            }
            List<ICitizenData> citizens = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).toList();
            for (int i = 0; i < citizens.size(); i++) {
                for (int j = i + 1; j < citizens.size(); j++) {
                    ICitizenData a = citizens.get(i);
                    ICitizenData b = citizens.get(j);
                    boolean coworkers = a.getWorkBuilding() != null && a.getWorkBuilding().equals(b.getWorkBuilding());
                    boolean housemates = a.getHomeBuilding() != null && a.getHomeBuilding().equals(b.getHomeBuilding());
                    boolean compatible = threads.contrariness(a) < Drift.CONTRARY
                            || threads.contrariness(b) < Drift.CONTRARY;
                    double chemistry = Drift.chemistry(colony.getID() + ":"
                            + Math.min(a.getId(), b.getId()) + ":" + Math.max(a.getId(), b.getId()));
                    int[] change = bonds.colonists().nudge(colony, a.getId(), b.getId(), Drift.daily(
                            coworkers, housemates, compatible, chemistry, bonds.colonists().rapport(colony, a.getId(), b.getId())));
                    Drift.crossed(change[0], change[1]).ifPresent(turn -> {
                        String where = coworkers && a.getWorkBuilding().getBuildingType() != null
                                ? " working together at the " + Component.translatable(
                                a.getWorkBuilding().getBuildingType().getTranslationKey()).getString()
                                : housemates ? " living under one roof" : "";
                        String fact = turn == Drift.Turn.FRIENDS
                                ? a.getName() + " and " + b.getName() + " became friends" + where + "."
                                : a.getName() + " and " + b.getName() + " got on each other's nerves" + where + ".";
                        memory.rememberSeen(colony, List.of(a.getId(), b.getId()), a.getName(), fact, 3);
                        ModLog.action("{}", fact);
                    });
                }
            }
            neighbourMoods(colony);
        }
    }


    /** Friends and quarrels at home and at work show in happiness (#61). */
    private void neighbourMoods(IColony colony) {
        List<ICitizenData> citizens = colony.getCitizenManager().getCitizens().stream().filter(c -> !c.isChild()).toList();
        for (ICitizenData citizen : citizens) {
            int friends = 0;
            int foes = 0;
            for (ICitizenData other : citizens) {
                boolean beside = other != citizen
                        && (citizen.getWorkBuilding() != null && citizen.getWorkBuilding().equals(other.getWorkBuilding())
                        || citizen.getHomeBuilding() != null && citizen.getHomeBuilding().equals(other.getHomeBuilding()));
                if (!beside) {
                    continue;
                }
                int rapport = bonds.colonists().rapport(colony, citizen.getId(), other.getId());
                friends += rapport >= Drift.FRIENDS ? 1 : 0;
                foes += rapport <= -Drift.FRIENDS ? 1 : 0;
            }
            me.bytefox.livingcolonists.bonds.Moods.neighbours(citizen, friends, foes);
        }
    }
}

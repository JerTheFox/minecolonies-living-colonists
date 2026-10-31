package me.bytefox.livingcolonists.colony;

import java.util.List;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.colonyEvents.descriptions.IColonyEventDescription;
import com.minecolonies.api.colony.permissions.IPermissions;
import com.minecolonies.api.colony.permissions.Rank;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The one place the conversation code asks MineColonies about colonies (#40): which colony a player
 * is in, their rank, who is awake, raids, the event log. When MineColonies changes, this is what to
 * fix for the talk code; perception and errands talk to MineColonies directly, being its work by nature.
 */
public final class Colonies {
    private Colonies() {
    }

    /** The colony whose land the player stands on, or null. */
    public static IColony at(ServerPlayer player) {
        return IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
    }

    public static IColony byId(int colonyId, ResourceKey<Level> dimension) {
        return IColonyManager.getInstance().getColonyByDimension(colonyId, dimension);
    }

    /** The colony the player owns, or null. */
    public static IColony ownedBy(ServerPlayer player) {
        return IColonyManager.getInstance().getIColonyByOwner(player.level(), player);
    }

    public static List<IColony> all() {
        return IColonyManager.getInstance().getAllColonies();
    }

    public static boolean isInside(ServerPlayer player, IColony colony) {
        return colony.getDimension().equals(player.level().dimension())
                && colony.isCoordInColony(player.level(), player.blockPosition());
    }

    public static ColonyAccess.Role role(ServerPlayer player, IColony colony) {
        Rank rank = colony.getPermissions().getRank(player);
        if (rank == null) {
            return ColonyAccess.Role.OTHER;
        }
        return switch (rank.getId()) {
            case IPermissions.OWNER_RANK_ID -> ColonyAccess.Role.OWNER;
            case IPermissions.OFFICER_RANK_ID -> ColonyAccess.Role.OFFICER;
            case IPermissions.FRIEND_RANK_ID -> ColonyAccess.Role.FRIEND;
            default -> ColonyAccess.Role.OTHER;
        };
    }

    public static boolean isRaided(IColony colony) {
        return colony.getRaiderManager().isRaided();
    }

    /** Whether the colonist is up and about: not asleep, alive and loaded. */
    public static boolean awake(ICitizenData citizen) {
        var entity = citizen.getEntity();
        return !citizen.isAsleep() && entity.isPresent() && entity.get().isAlive() && !entity.get().isSleeping();
    }

    /** MineColonies' log of what happened in the colony, oldest first. */
    public static List<IColonyEventDescription> eventLog(IColony colony) {
        return colony.getEventDescriptionManager().getEventDescriptions();
    }
}

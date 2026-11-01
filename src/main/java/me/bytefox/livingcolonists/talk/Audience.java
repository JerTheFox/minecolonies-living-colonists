package me.bytefox.livingcolonists.talk;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.jobs.IJob;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.bytefox.livingcolonists.colony.Colonies;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.behaviour.ColonyAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Who takes part in colonists' talk: who hears the colony chat, who may talk to a colony's colonists, which colony
 * a player writes to, which colonist a player looks at, and how a colonist or a player is named. Server thread only.
 */
public final class Audience {
    /** How far from the line of sight a colonist still counts as looked at. */
    private static final double TARGET_RADIUS = 0.8;

    private Audience() {
    }

    /** The name colonists know this player by. */
    public static String playerName(ServerPlayer player) {
        return PlayerNames.name(player.getGameProfile().getName(), Config.playerNames());
    }

    /** Whether the player's rank lets them talk to the colony's colonists; tells them if not. */
    public static boolean mayTalk(ServerPlayer player, IColony colony) {
        if (ColonyAccess.canTalk(role(player, colony), Config.MEMBERS_ONLY.get())) {
            return true;
        }
        player.sendSystemMessage(Component.translatable("livingcolonists.colony.not_member", colony.getName()));
        return false;
    }

    public static ColonyAccess.Role role(ServerPlayer player, IColony colony) {
        return Colonies.role(player, colony);
    }

    /**
     * Players inside the colony's borders, plus the author wherever they are; with
     * {@code colonyChatAnywhere}, also the colony's members wherever they are.
     */
    public static Collection<ServerPlayer> colonyAudience(MinecraftServer server, IColony colony, ServerPlayer author) {
        Set<ServerPlayer> players = new LinkedHashSet<>();
        if (author != null) {
            players.add(author);
        }
        boolean anywhere = Config.COLONY_CHAT_ANYWHERE.get();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInside(player, colony) || anywhere && role(player, colony) != ColonyAccess.Role.OTHER) {
                players.add(player);
            }
        }
        return players;
    }

    /**
     * The colony whose chat the player writes to: the one they stand in; with
     * {@code colonyChatAnywhere}, outside any colony, the one they own, else lead, else are a friend of.
     */
    public static IColony chatColony(ServerPlayer player) {
        IColony here = Colonies.at(player);
        if (here != null || !Config.COLONY_CHAT_ANYWHERE.get()) {
            return here;
        }
        IColony best = null;
        for (IColony colony : Colonies.all()) {
            ColonyAccess.Role role = role(player, colony);
            if (role != ColonyAccess.Role.OTHER && (best == null || role.ordinal() < role(player, best).ordinal())) {
                best = colony;
            }
        }
        return best;
    }

    /** The game locale most of these players use, like {@code ru_ru}; English when there is nobody. */
    public static String language(Collection<ServerPlayer> players) {
        return players.stream().map(player -> player.clientInformation().language())
                .collect(Collectors.groupingBy(code -> code, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("en_us");
    }

    public static boolean isInside(ServerPlayer player, IColony colony) {
        return Colonies.isInside(player, colony);
    }

    /** The colonist's data, or null if the colony or the colonist is gone. */
    public static ICitizenData resolve(ResourceKey<Level> dimension, int colonyId, int citizenId) {
        IColony colony = Colonies.byId(colonyId, dimension);
        return colony == null ? null : colony.getCitizenManager().getCivilian(citizenId);
    }

    public static Speaker speakerOf(ICitizenData citizen) {
        IJob<?> job = citizen.getJob();
        return new Speaker(citizen.getName(),
                Prompts.jobName(job == null ? null : job.getJobRegistryEntry().getKey().getPath()),
                job == null ? null : job.getJobRegistryEntry().getTranslationKey(),
                citizen.getColony().getName(), citizen.getColony().getID(), citizen.getId(),
                citizen.getColony().getTeamColonyColor());
    }

    /**
     * @param lookedAtOnly only the colonist under the crosshair, without falling back to the nearest
     */
    public static Optional<AbstractEntityCitizen> findListener(ServerPlayer player, double range, boolean lookedAtOnly) {
        Vec3 eyes = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        List<ListenerPicker.Candidate<AbstractEntityCitizen>> candidates = player.serverLevel()
                .getEntitiesOfClass(AbstractEntityCitizen.class, player.getBoundingBox().inflate(range),
                        citizen -> citizen.isAlive() && citizen.getCitizenData() != null)
                .stream()
                .map(citizen -> {
                    Vec3 offset = citizen.getBoundingBox().getCenter().subtract(eyes);
                    return new ListenerPicker.Candidate<>(citizen, offset.x, offset.y, offset.z);
                })
                .toList();
        return lookedAtOnly
                ? ListenerPicker.lookedAt(candidates, look.x, look.y, look.z, range, TARGET_RADIUS)
                : ListenerPicker.pick(candidates, look.x, look.y, look.z, range, TARGET_RADIUS);
    }
}

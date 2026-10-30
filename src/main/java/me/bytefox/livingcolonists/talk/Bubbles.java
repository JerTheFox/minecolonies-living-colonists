package me.bytefox.livingcolonists.talk;

import java.util.Collection;
import java.util.Optional;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import me.bytefox.livingcolonists.Config;
import me.bytefox.livingcolonists.network.SpeechBubble;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends speech bubbles to the players who can see them: those with the mod, in the same world and
 * within the bubble range of the speaker. Everyone else keeps the plain chat line.
 */
public final class Bubbles {
    private Bubbles() {
    }

    /** A line over the speaker, for these recipients (a private line: only its player). */
    public static void line(Entity speaker, String text, Collection<ServerPlayer> recipients) {
        send(speaker, new SpeechBubble(speaker.getId(), text, SpeechBubble.LINE), recipients);
    }

    /** Typing dots over the speaker, or their end. */
    public static void typing(Entity speaker, boolean on, Collection<ServerPlayer> recipients) {
        send(speaker, new SpeechBubble(speaker.getId(), "", on ? SpeechBubble.TYPING : SpeechBubble.STOPPED), recipients);
    }

    private static void send(Entity speaker, SpeechBubble bubble, Collection<ServerPlayer> recipients) {
        double range = Config.BUBBLE_RANGE.get();
        for (ServerPlayer player : recipients) {
            if (player.level() == speaker.level() && player.distanceToSqr(speaker) <= range * range) {
                PacketDistributor.sendToPlayer(player, bubble);
            }
        }
    }

    /** A colonist's entity, if it is loaded. */
    static Optional<Entity> colonist(int colonyId, int citizenId) {
        for (IColony colony : IColonyManager.getInstance().getAllColonies()) {
            if (colony.getID() == colonyId) {
                ICitizenData citizen = colony.getCitizenManager().getCivilian(citizenId);
                return citizen == null ? Optional.empty() : citizen.getEntity().map(entity -> entity);
            }
        }
        return Optional.empty();
    }
}

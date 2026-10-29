package me.bytefox.livingcolonists.talk;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Writes conversation lines into chat exactly like player chat ({@code <Name> text}), but only
 * for players within earshot. Hovering a colonist's name shows the profession and colony.
 */
public final class ChatSpeechOutput implements SpeechOutput {
    /** How far a line can be heard, in blocks. */
    private static final double HEARING_RANGE = 32.0;

    @Override
    public void playerSaid(ServerPlayer player, Speaker listener, String text) {
        sendNearby(player.serverLevel(), player, Component.translatable("chat.type.text", player.getDisplayName(), text));
    }

    @Override
    public void colonistSaid(AbstractEntityCitizen citizen, Speaker speaker, String text) {
        Component job = speaker.jobTranslationKey() == null
                ? Component.translatable("livingcolonists.job.unemployed")
                : Component.translatable(speaker.jobTranslationKey());
        Component hover = Component.empty().append(job).append("\n" + speaker.colony());
        Component name = Component.literal(speaker.name())
                .withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
        sendNearby((ServerLevel) citizen.level(), citizen, Component.translatable("chat.type.text", name, text));
    }

    private static void sendNearby(ServerLevel level, Entity source, Component line) {
        double rangeSqr = HEARING_RANGE * HEARING_RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(source) <= rangeSqr) {
                player.sendSystemMessage(line);
            }
        }
    }
}

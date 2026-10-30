package me.bytefox.livingcolonists.talk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import me.bytefox.livingcolonists.Config;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chat lines, and speech bubbles on top of them for players who have the mod: over colonists, and
 * over players in the colony chat when player bubbles are on. Private lines bubble only for their
 * player; a player's private message never bubbles, others would see it.
 */
final class BubbleSpeechOutput implements SpeechOutput {
    private final SpeechOutput chat;

    BubbleSpeechOutput(SpeechOutput chat) {
        this.chat = chat;
    }

    @Override
    public void playerSaid(ServerPlayer player, String colony, String text, Collection<ServerPlayer> recipients) {
        chat.playerSaid(player, colony, text, recipients);
        if (Config.PLAYER_BUBBLES.get()) {
            List<ServerPlayer> seeing = new ArrayList<>(recipients);
            if (!seeing.contains(player)) {
                seeing.add(player);
            }
            Bubbles.line(player, text, seeing);
        }
    }

    @Override
    public void colonistSaid(Speaker speaker, String text, Collection<ServerPlayer> recipients) {
        chat.colonistSaid(speaker, text, recipients);
        if (Config.SPEECH_BUBBLES.get()) {
            Bubbles.colonist(speaker.colonyId(), speaker.citizenId()).ifPresent(entity -> Bubbles.line(entity, text, recipients));
        }
    }

    @Override
    public void playerWhispered(ServerPlayer player, Speaker colonist, String text) {
        chat.playerWhispered(player, colonist, text);
    }

    @Override
    public void colonistWhispered(Speaker colonist, ServerPlayer player, String text) {
        chat.colonistWhispered(colonist, player, text);
        if (Config.SPEECH_BUBBLES.get()) {
            Bubbles.colonist(colonist.colonyId(), colonist.citizenId()).ifPresent(entity -> Bubbles.line(entity, text, List.of(player)));
        }
    }
}

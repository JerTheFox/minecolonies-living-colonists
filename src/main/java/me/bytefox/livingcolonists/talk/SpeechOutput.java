package me.bytefox.livingcolonists.talk;

import java.util.Collection;

import net.minecraft.server.level.ServerPlayer;

/**
 * Where conversation lines go: the chat, with speech bubbles on top ({@link BubbleSpeechOutput}).
 * Called on the server thread only.
 */
public interface SpeechOutput {
    /** A player said something in a colony's chat. */
    void playerSaid(ServerPlayer player, String colony, String text, Collection<ServerPlayer> recipients);

    /** A colonist said something. */
    void colonistSaid(Speaker speaker, String text, Collection<ServerPlayer> recipients);

    /** A player wrote to a colonist privately; only that player sees it. */
    void playerWhispered(ServerPlayer player, Speaker colonist, String text);

    /** A colonist answered a player privately; only that player sees it. */
    void colonistWhispered(Speaker colonist, ServerPlayer player, String text);
}

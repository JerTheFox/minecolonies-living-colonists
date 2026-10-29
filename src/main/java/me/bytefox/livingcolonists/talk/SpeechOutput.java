package me.bytefox.livingcolonists.talk;

import java.util.Collection;

import net.minecraft.server.level.ServerPlayer;

/**
 * Where conversation lines go. Plain chat now; speech bubbles (Comics Bubbles Chat) can be
 * another implementation later. Called on the server thread only.
 */
public interface SpeechOutput {
    /** A player said something to colonists. */
    void playerSaid(ServerPlayer player, String text, Collection<ServerPlayer> recipients);

    /** A colonist said something. */
    void colonistSaid(Speaker speaker, String text, Collection<ServerPlayer> recipients);
}

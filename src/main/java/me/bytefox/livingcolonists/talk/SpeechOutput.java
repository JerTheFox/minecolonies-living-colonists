package me.bytefox.livingcolonists.talk;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where conversation lines go. Plain chat now; speech bubbles (Comics Bubbles Chat) can be
 * another implementation later. Called on the server thread only.
 */
public interface SpeechOutput {
    /** A player addressed a colonist. */
    void playerSaid(ServerPlayer player, Speaker listener, String text);

    /** A colonist said something. */
    void colonistSaid(AbstractEntityCitizen citizen, Speaker speaker, String text);
}

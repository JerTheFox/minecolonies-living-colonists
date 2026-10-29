package me.bytefox.livingcolonists.bonds;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Where the mixin into MineColonies' citizen data reports a button pressed in a colonist's request
 * dialog ("I will work on it!", "Cancel"…). MineColonies sends no event for it. Server thread.
 */
public final class RequestResponses {
    /** Gets the button's translation key, like {@code com.minecolonies.coremod.gui.chat.okay}. */
    public interface Listener {
        void onResponse(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player);
    }

    private static volatile Listener listener;

    private RequestResponses() {
    }

    public static void listen(Listener newListener) {
        listener = newListener;
    }

    /** Called by the mixin; never lets a problem of ours reach MineColonies. */
    public static void fire(ICitizenData citizen, IToken<?> request, String button, Player player) {
        Listener current = listener;
        if (current == null || request == null || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        try {
            current.onResponse(citizen, request, button, serverPlayer);
        } catch (RuntimeException e) {
            LivingColonists.LOGGER.warn("Could not note the answer to {}'s request", citizen.getName(), e);
        }
    }
}

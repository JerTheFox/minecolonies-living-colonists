package me.bytefox.livingcolonists.bonds;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Where the mixin into MineColonies' citizen data reports a button pressed in a colonist's dialog
 * ("I will work on it!", "Cancel"…). MineColonies sends no event for it. Server thread.
 */
public final class RequestResponses {
    /** Button keys are translation keys, like {@code com.minecolonies.coremod.gui.chat.okay}. */
    public interface Listener {
        /** A dialog about a colony request: the colonist asks for an item the colony couldn't get. */
        void onRequest(ICitizenData citizen, IToken<?> request, String button, ServerPlayer player);

        /** A plain dialog, known by the translation key of what the colonist says. */
        void onDialog(ICitizenData citizen, String inquiry, String button, ServerPlayer player);
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
        if (current != null && request != null && player instanceof ServerPlayer serverPlayer) {
            safely(citizen, () -> current.onRequest(citizen, request, button, serverPlayer));
        }
    }

    /** Called by the mixin; never lets a problem of ours reach MineColonies. */
    public static void fireDialog(ICitizenData citizen, String inquiry, String button, Player player) {
        Listener current = listener;
        if (current != null && player instanceof ServerPlayer serverPlayer) {
            safely(citizen, () -> current.onDialog(citizen, inquiry, button, serverPlayer));
        }
    }

    private static void safely(ICitizenData citizen, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LivingColonists.LOGGER.warn("Could not note the answer to {}'s dialog", citizen.getName(), e);
        }
    }
}

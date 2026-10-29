package me.bytefox.livingcolonists.mixin;

import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.core.colony.CitizenData;
import me.bytefox.livingcolonists.bonds.RequestResponses;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes which button a player pressed in a colonist's request dialog, so "I will work on it!" can
 * become a promise. Only reads; MineColonies handles the answer as before. MineColonies has no
 * event for this, and the answer can't be told from the outside afterwards.
 */
@Mixin(value = CitizenData.class, remap = false)
public abstract class CitizenDataMixin {
    @Shadow(remap = false)
    @Final
    protected Map<Component, IInteractionResponseHandler> citizenChatOptions;

    @Inject(method = "onResponseTriggered", at = @At("HEAD"), remap = false)
    private void livingcolonists$noteResponse(Component key, int responseId, Player player, CallbackInfo ci) {
        IInteractionResponseHandler handler = citizenChatOptions.get(key);
        if (!(handler instanceof RequestInteractionAccessor request)) {
            return;
        }
        List<Component> responses = handler.getPossibleResponses();
        if (responseId < 0 || responseId >= responses.size()
                || !(responses.get(responseId).getContents() instanceof TranslatableContents button)) {
            return;
        }
        RequestResponses.fire((ICitizenData) (Object) this, request.livingcolonists$token(), button.getKey(), player);
    }
}

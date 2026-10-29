package me.bytefox.livingcolonists.mixin;

import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.interactionhandling.RequestBasedInteraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads which colony request a request dialog is about; MineColonies keeps it private. Read only. */
@Mixin(value = RequestBasedInteraction.class, remap = false)
public interface RequestInteractionAccessor {
    @Accessor(value = "token", remap = false)
    IToken<?> livingcolonists$token();
}

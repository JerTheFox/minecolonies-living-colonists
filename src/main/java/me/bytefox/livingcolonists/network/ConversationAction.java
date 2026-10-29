package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: a key or the chat indicator was used. */
public record ConversationAction(Action action) implements CustomPacketPayload {
    public enum Action {
        /** The "Talk to colonist" key. */
        TALK,
        /** The "Focus on colonist" key: pick a partner without opening the chat. */
        FOCUS,
        /** The "Chat mode" key. */
        CYCLE_MODE,
        /** The ✕ next to the private talk above the chat input. */
        RELEASE,
        /** The "Players" switch above the chat input. */
        MODE_PLAYERS,
        /** The "Colony" switch above the chat input. */
        MODE_COLONY
    }

    public static final Type<ConversationAction> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "conversation_action"));

    public static final StreamCodec<ByteBuf, ConversationAction> STREAM_CODEC = ByteBufCodecs.VAR_INT
            .map(i -> new ConversationAction(Action.values()[Math.floorMod(i, Action.values().length)]),
                    payload -> payload.action().ordinal());

    @Override
    public Type<ConversationAction> type() {
        return TYPE;
    }
}

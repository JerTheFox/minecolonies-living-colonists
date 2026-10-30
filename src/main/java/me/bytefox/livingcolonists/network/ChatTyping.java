package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: the player started or stopped typing a chat message, for the bubble over them. */
public record ChatTyping(boolean typing) implements CustomPacketPayload {
    public static final Type<ChatTyping> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "chat_typing"));

    public static final StreamCodec<ByteBuf, ChatTyping> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, ChatTyping::typing, ChatTyping::new);

    @Override
    public Type<ChatTyping> type() {
        return TYPE;
    }
}

package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: a colonist started or stopped typing a line the player will see.
 *
 * @param id     the colonist, as colony and citizen id packed into one number
 * @param color  the colony's color as a {@code ChatFormatting} id, or -1 for none
 * @param typing true when they start, false when the line went out or was dropped
 */
public record TypingState(long id, String name, int color, boolean typing) implements CustomPacketPayload {

    public static final Type<TypingState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "typing"));

    public static final StreamCodec<ByteBuf, TypingState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TypingState::id,
            ByteBufCodecs.stringUtf8(64), TypingState::name,
            ByteBufCodecs.VAR_INT, TypingState::color,
            ByteBufCodecs.BOOL, TypingState::typing,
            TypingState::new);

    @Override
    public Type<TypingState> type() {
        return TYPE;
    }
}

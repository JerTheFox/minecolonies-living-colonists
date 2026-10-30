package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: a speech bubble over an entity (a colonist, or a player when player bubbles are
 * on): a line said, or typing starting or stopping.
 *
 * @param entity the entity's network id in the client's world
 * @param kind   {@link #LINE}, {@link #TYPING} or {@link #STOPPED}
 */
public record SpeechBubble(int entity, String text, int kind) implements CustomPacketPayload {
    public static final int LINE = 0;
    public static final int TYPING = 1;
    public static final int STOPPED = 2;

    public static final Type<SpeechBubble> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "bubble"));

    public static final StreamCodec<ByteBuf, SpeechBubble> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpeechBubble::entity,
            ByteBufCodecs.stringUtf8(512), SpeechBubble::text,
            ByteBufCodecs.VAR_INT, SpeechBubble::kind,
            SpeechBubble::new);

    @Override
    public Type<SpeechBubble> type() {
        return TYPE;
    }
}

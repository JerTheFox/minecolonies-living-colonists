package me.bytefox.livingcolonists.network;

import java.util.List;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: translation keys of items colonists talk about, which the client names in its
 * own language. A dedicated server has only the English names of vanilla items.
 */
public record ItemNamesRequest(List<String> keys) implements CustomPacketPayload {
    public static final int MAX_KEYS = 64;
    public static final Type<ItemNamesRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "item_names_request"));

    public static final StreamCodec<ByteBuf, ItemNamesRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(MAX_KEYS)), ItemNamesRequest::keys, ItemNamesRequest::new);

    @Override
    public Type<ItemNamesRequest> type() {
        return TYPE;
    }
}

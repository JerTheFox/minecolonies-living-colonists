package me.bytefox.livingcolonists.network;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: the asked item names in the client's language, by translation key. */
public record ItemNamesReply(Map<String, String> names) implements CustomPacketPayload {
    public static final Type<ItemNamesReply> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "item_names_reply"));

    public static final StreamCodec<ByteBuf, ItemNamesReply> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.stringUtf8(256), ByteBufCodecs.stringUtf8(256),
                    ItemNamesRequest.MAX_KEYS),
            ItemNamesReply::names, ItemNamesReply::new);

    @Override
    public Type<ItemNamesReply> type() {
        return TYPE;
    }
}

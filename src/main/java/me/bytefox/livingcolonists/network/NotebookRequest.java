package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client → server: a button in the open notebook was used, send its pages again (#66). */
public record NotebookRequest() implements CustomPacketPayload {
    public static final Type<NotebookRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "notebook_request"));
    public static final StreamCodec<ByteBuf, NotebookRequest> STREAM_CODEC = StreamCodec.unit(new NotebookRequest());

    @Override
    public Type<NotebookRequest> type() {
        return TYPE;
    }
}

package me.bytefox.livingcolonists.network;

import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the talk menu without a language model (#68), with what the colonist just said.
 *
 * @param open true opens the menu; false only shows the answer if the menu for this colonist is open
 */
public record TalkMenuState(int colonyId, int citizenId, String name, String jobKey, Component line, boolean open,
                            java.util.List<String> options, int hearts)
        implements CustomPacketPayload {
    public static final Type<TalkMenuState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "talk_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TalkMenuState> STREAM_CODEC = StreamCodec.of(
            (buf, state) -> {
                buf.writeVarInt(state.colonyId());
                buf.writeVarInt(state.citizenId());
                buf.writeUtf(state.name());
                buf.writeUtf(state.jobKey());
                ComponentSerialization.STREAM_CODEC.encode(buf, state.line());
                buf.writeBoolean(state.open());
                buf.writeCollection(state.options(), (out, option) -> out.writeUtf(option));
                buf.writeVarInt(state.hearts());
            },
            buf -> new TalkMenuState(buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readUtf(),
                    ComponentSerialization.STREAM_CODEC.decode(buf), buf.readBoolean(),
                    buf.readList(in -> in.readUtf()), buf.readVarInt()));

    @Override
    public Type<TalkMenuState> type() {
        return TYPE;
    }
}

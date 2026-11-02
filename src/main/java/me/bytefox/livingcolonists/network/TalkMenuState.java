package me.bytefox.livingcolonists.network;

import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the talk menu without a language model (#68), with what the colonist just said.
 *
 * @param open true opens the menu; false only shows the answer if the menu for this colonist is open
 */
public record TalkMenuState(int colonyId, int citizenId, String name, String jobKey, Component line, boolean open)
        implements CustomPacketPayload {
    /** What the player can say, in the menu's order. */
    public static final java.util.List<String> OPTIONS = java.util.List.of("how", "work", "likes", "birthday", "compliment", "bye");

    public static final Type<TalkMenuState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "talk_menu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TalkMenuState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TalkMenuState::colonyId,
            ByteBufCodecs.VAR_INT, TalkMenuState::citizenId,
            ByteBufCodecs.STRING_UTF8, TalkMenuState::name,
            ByteBufCodecs.STRING_UTF8, TalkMenuState::jobKey,
            ComponentSerialization.STREAM_CODEC, TalkMenuState::line,
            ByteBufCodecs.BOOL, TalkMenuState::open,
            TalkMenuState::new);

    @Override
    public Type<TalkMenuState> type() {
        return TYPE;
    }
}

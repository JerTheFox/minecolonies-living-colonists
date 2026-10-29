package me.bytefox.livingcolonists.network;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.talk.ChatMode;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: who the player is talking to, for the indicator above the chat input.
 *
 * @param partnerName    empty when no partner is locked
 * @param partnerJobKey  translation key of the partner's profession, empty if unemployed or no partner
 * @param openChat       open the chat input, because the player just picked a partner with the key
 */
public record ConversationState(ChatMode mode, String partnerName, String partnerJobKey, boolean openChat)
        implements CustomPacketPayload {

    public static final Type<ConversationState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "conversation_state"));

    public static final StreamCodec<ByteBuf, ConversationState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> ChatMode.values()[Math.floorMod(i, ChatMode.values().length)], ChatMode::ordinal),
            ConversationState::mode,
            ByteBufCodecs.STRING_UTF8, ConversationState::partnerName,
            ByteBufCodecs.STRING_UTF8, ConversationState::partnerJobKey,
            ByteBufCodecs.BOOL, ConversationState::openChat,
            ConversationState::new);

    public boolean hasPartner() {
        return !partnerName.isEmpty();
    }

    @Override
    public Type<ConversationState> type() {
        return TYPE;
    }
}

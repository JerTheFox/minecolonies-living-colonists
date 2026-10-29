package me.bytefox.livingcolonists.network;

import java.util.HashMap;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the settings screen wants the settings, or changes them, or runs a check.
 *
 * @param changes new values by setting id, for {@link Action#APPLY}; a key appears only when typed or cleared
 */
public record SettingsRequest(Action action, Map<String, String> changes) implements CustomPacketPayload {
    public enum Action {
        OPEN, APPLY, TEST_CHAT, TEST_CHARACTER, MODELS, FALLBACK_MODELS
    }

    public static final Type<SettingsRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "settings_request"));

    public static final StreamCodec<ByteBuf, SettingsRequest> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal),
            SettingsRequest::action,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8, 64),
            SettingsRequest::changes,
            SettingsRequest::new);

    @Override
    public Type<SettingsRequest> type() {
        return TYPE;
    }
}

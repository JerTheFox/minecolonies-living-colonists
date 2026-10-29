package me.bytefox.livingcolonists.network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the settings for the settings screen, never with API keys (see
 * {@code SettingsService#values}).
 *
 * @param canEdit the player is an operator (or the single-player owner) and may change them
 * @param message the outcome of the last action, to show; empty if none
 * @param models  models from the last "Load models", empty otherwise
 */
public record SettingsState(boolean canEdit, Map<String, String> values, List<String> usage, String message,
                            List<String> models) implements CustomPacketPayload {

    public static final Type<SettingsState> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "settings_state"));

    public static final StreamCodec<ByteBuf, SettingsState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SettingsState::canEdit,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8, 64),
            SettingsState::values,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), SettingsState::usage,
            ByteBufCodecs.STRING_UTF8, SettingsState::message,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(512)), SettingsState::models,
            SettingsState::new);

    @Override
    public Type<SettingsState> type() {
        return TYPE;
    }
}

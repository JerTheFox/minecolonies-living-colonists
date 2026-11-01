package me.bytefox.livingcolonists.network;

import java.util.List;

import me.bytefox.livingcolonists.LivingColonists;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the pages of the player's promise notebook (#60).
 *
 * @param open true to open the notebook; false only refreshes it if it is open (a promise in it changed)
 */
public record PromiseBookPages(List<Component> pages, boolean open) implements CustomPacketPayload {
    public static final Type<PromiseBookPages> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "promise_book_pages"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PromiseBookPages> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(100)), PromiseBookPages::pages,
            ByteBufCodecs.BOOL, PromiseBookPages::open,
            PromiseBookPages::new);

    @Override
    public Type<PromiseBookPages> type() {
        return TYPE;
    }
}

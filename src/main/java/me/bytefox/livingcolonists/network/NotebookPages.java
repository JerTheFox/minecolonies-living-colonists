package me.bytefox.livingcolonists.network;

import java.util.List;

import me.bytefox.livingcolonists.LivingColonists;
import me.bytefox.livingcolonists.notebook.Notebook;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the Colony Notebook's sections (#66).
 *
 * @param open true to open the notebook; false only refreshes it if it is open
 */
public record NotebookPages(List<Notebook.Section> sections, boolean open) implements CustomPacketPayload {
    public static final Type<NotebookPages> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LivingColonists.MODID, "notebook_pages"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Notebook.Section> SECTION = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Notebook.Section::key,
            ComponentSerialization.STREAM_CODEC, Notebook.Section::title,
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(100)), Notebook.Section::pages,
            ByteBufCodecs.STRING_UTF8, Notebook.Section::news,
            Notebook.Section::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, NotebookPages> STREAM_CODEC = StreamCodec.composite(
            SECTION.apply(ByteBufCodecs.list(8)), NotebookPages::sections,
            ByteBufCodecs.BOOL, NotebookPages::open,
            NotebookPages::new);

    @Override
    public Type<NotebookPages> type() {
        return TYPE;
    }
}

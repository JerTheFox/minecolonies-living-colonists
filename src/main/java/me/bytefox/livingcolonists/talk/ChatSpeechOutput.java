package me.bytefox.livingcolonists.talk;

import java.util.Collection;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Writes conversation lines into chat exactly like player chat ({@code <Name> text}). Hovering a
 * colonist's name shows the profession and colony; clicking it starts a private talk.
 */
public final class ChatSpeechOutput implements SpeechOutput {
    @Override
    public void playerSaid(ServerPlayer player, String text, Collection<ServerPlayer> recipients) {
        send(Component.translatable("chat.type.text", player.getDisplayName(), text), recipients);
    }

    @Override
    public void colonistSaid(Speaker speaker, String text, Collection<ServerPlayer> recipients) {
        Component job = speaker.jobTranslationKey() == null
                ? Component.translatable("livingcolonists.job.unemployed")
                : Component.translatable(speaker.jobTranslationKey());
        Component hover = Component.empty().append(job).append("\n" + speaker.colony() + "\n")
                .append(Component.translatable("livingcolonists.chat.click_to_answer").withStyle(ChatFormatting.GRAY));
        Component name = Component.literal(speaker.name()).withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                        "/lc lock " + speaker.colonyId() + " " + speaker.citizenId())));
        send(Component.translatable("chat.type.text", name, text), recipients);
    }

    private static void send(Component line, Collection<ServerPlayer> recipients) {
        for (ServerPlayer player : recipients) {
            player.sendSystemMessage(line);
        }
    }
}

package me.bytefox.livingcolonists.talk;

import java.util.Collection;

import me.bytefox.livingcolonists.debug.Counters;
import me.bytefox.livingcolonists.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Writes conversation lines into chat exactly like player chat ({@code <Name> text}). A colonist's
 * name is shown in the colony's color; hovering it shows the profession and colony, clicking it
 * starts a private talk. Every line also goes to the server log, as player chat does.
 */
public final class ChatSpeechOutput implements SpeechOutput {
    @Override
    public void playerSaid(ServerPlayer player, String colony, String text, Collection<ServerPlayer> recipients) {
        ModLog.chat("[Colony {}] <{}> {}", colony, player.getGameProfile().getName(), text);
        send(Component.translatable("chat.type.text", player.getDisplayName(), text), recipients);
    }

    @Override
    public void colonistSaid(Speaker speaker, String text, Collection<ServerPlayer> recipients) {
        Counters.add("lines.colony");
        ModLog.chat("[Colony {}] <{}> {}", speaker.colony(), speaker.name(), text);
        send(Component.translatable("chat.type.text", name(speaker), text), recipients);
    }

    @Override
    public void playerWhispered(ServerPlayer player, Speaker colonist, String text) {
        ModLog.chat("[Private {}] <{}> -> <{}> {}", colonist.colony(), player.getGameProfile().getName(),
                colonist.name(), text);
        player.sendSystemMessage(whisper(Component.literal("<").append(player.getDisplayName()).append(">"),
                Component.literal("<").append(name(colonist)).append(">"), accent(colonist), text));
    }

    @Override
    public void colonistWhispered(Speaker colonist, ServerPlayer player, String text) {
        Counters.add("lines.private");
        ModLog.chat("[Private {}] <{}> -> <{}> {}", colonist.colony(), colonist.name(),
                player.getGameProfile().getName(), text);
        player.sendSystemMessage(whisper(Component.literal("<").append(name(colonist)).append(">"),
                Component.literal("<").append(player.getDisplayName()).append(">"), accent(colonist), text));
    }

    /** {@code <From> → <To> text}: the arrow and the addressee in the colony's color, so private lines stand out. */
    private static Component whisper(Component from, Component to, ChatFormatting accent, String text) {
        return Component.empty().append(from)
                .append(Component.literal(" → ").withStyle(accent))
                .append(to.copy().withStyle(accent))
                .append(" " + text);
    }

    /** The colony's color for the arrow of a private line; gold when the colony has none or plain white. */
    private static ChatFormatting accent(Speaker colonist) {
        return hasColor(colonist) ? colonist.color() : ChatFormatting.GOLD;
    }

    private static boolean hasColor(Speaker speaker) {
        ChatFormatting color = speaker.color();
        return color != null && color.isColor() && color != ChatFormatting.WHITE;
    }

    /** The colonist's name in the colony's color, with the profession and colony on hover; a click starts a private talk. */
    private static Component name(Speaker speaker) {
        Component job = speaker.jobTranslationKey() == null
                ? Component.translatable("livingcolonists.job.unemployed")
                : Component.translatable(speaker.jobTranslationKey());
        Component hover = Component.empty().append(job).append("\n" + speaker.colony() + "\n")
                .append(Component.translatable("livingcolonists.chat.click_to_answer").withStyle(ChatFormatting.GRAY));
        return Component.literal(speaker.name()).withStyle(style -> {
            style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/lc lock " + speaker.colonyId() + " " + speaker.citizenId()));
            return hasColor(speaker) ? style.withColor(speaker.color()) : style;
        });
    }

    private static void send(Component line, Collection<ServerPlayer> recipients) {
        for (ServerPlayer player : recipients) {
            player.sendSystemMessage(line);
        }
    }
}

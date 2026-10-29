package me.bytefox.livingcolonists.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.bytefox.livingcolonists.network.TypingState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * "Idris is typing..." with the names in their colony's color. The dots now and then jump in a
 * small wave. With the chat closed it sits between the chat and the hotbar; with the chat open,
 * next to the switch above the input (see {@link ConversationIndicator}).
 */
public final class TypingIndicator {
    /** Drawn above the hotbar, below the chat lines, when the chat is closed. */
    private static final int HUD_BOTTOM_OFFSET = 34;
    private static final int TEXT_COLOR = 0xFFAAAAAA;
    /** A typing notice the server never ended (a lost packet) disappears after a minute. */
    private static final long STALE_MILLIS = 60_000;
    /** The dots jump once every this long, each a little after the one before. */
    private static final long WAVE_PERIOD_MILLIS = 2400;
    private static final long DOT_JUMP_MILLIS = 300;
    private static final long DOT_DELAY_MILLIS = 120;
    private static final int JUMP_HEIGHT = 2;

    private record Typist(String name, int color, long since) {
    }

    /** Client thread only. */
    private static final Map<Long, Typist> TYPING = new LinkedHashMap<>();

    private TypingIndicator() {
    }

    public static void apply(TypingState state) {
        if (state.typing()) {
            TYPING.put(state.id(), new Typist(state.name(), state.color(), System.currentTimeMillis()));
        } else {
            TYPING.remove(state.id());
        }
    }

    public static void reset() {
        TYPING.clear();
    }

    /** With the chat closed. */
    static void renderHud(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen instanceof ChatScreen) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        draw(graphics, minecraft.font, 4, graphics.guiHeight() - HUD_BOTTOM_OFFSET);
    }

    /** Draws the notice at {@code x, y}, if anyone is typing. */
    static void draw(GuiGraphics graphics, Font font, int x, int y) {
        long now = System.currentTimeMillis();
        TYPING.values().removeIf(typist -> now - typist.since() > STALE_MILLIS);
        if (TYPING.isEmpty()) {
            return;
        }
        List<Component> names = new ArrayList<>();
        for (Typist typist : TYPING.values()) {
            ChatFormatting color = ChatFormatting.getById(typist.color());
            names.add(color != null && color.isColor() ? Component.literal(typist.name()).withStyle(color)
                    : Component.literal(typist.name()));
        }
        Component text = switch (names.size()) {
            case 1 -> Component.translatable("livingcolonists.typing.one", names.get(0));
            case 2 -> Component.translatable("livingcolonists.typing.two", names.get(0), names.get(1));
            default -> Component.translatable("livingcolonists.typing.many", names.get(0), names.size() - 1);
        };
        graphics.drawString(font, text, x, y, TEXT_COLOR);
        int dotX = x + font.width(text) + 1;
        long phase = now % WAVE_PERIOD_MILLIS;
        for (int i = 0; i < 3; i++) {
            long local = phase - i * DOT_DELAY_MILLIS;
            int lift = local >= 0 && local < DOT_JUMP_MILLIS
                    ? (int) Math.round(JUMP_HEIGHT * Math.sin(Math.PI * local / DOT_JUMP_MILLIS)) : 0;
            graphics.drawString(font, ".", dotX, y - lift, TEXT_COLOR);
            dotX += font.width(".") + 1;
        }
    }
}

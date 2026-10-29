package me.bytefox.livingcolonists.client;

import java.util.ArrayList;
import java.util.List;

import me.bytefox.livingcolonists.network.ConversationAction;
import me.bytefox.livingcolonists.network.ConversationState;
import me.bytefox.livingcolonists.talk.ChatMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Above the chat input: a "Players | Colony" switch, and the colonist of a private talk with a ✕
 * to end it. Everything is clickable.
 */
final class ConversationIndicator {
    /** Distance from the bottom of the screen: just above the chat input box. */
    private static final int BOTTOM_OFFSET = 27;
    private static final int PADDING = 3;
    private static final int GAP = 4;

    private static final int ACTIVE_BACKGROUND = 0xE0505050;
    private static final int IDLE_BACKGROUND = 0x80000000;
    private static final int HOVER_BACKGROUND = 0xC0303030;
    private static final int ACTIVE_COLOR = 0xFFFFFFFF;
    private static final int IDLE_COLOR = 0xFF909090;
    private static final int PARTNER_COLOR = 0xFFFFD866;

    private record Button(int left, int right, Component text, boolean active, int color, ConversationAction.Action action) {
        boolean contains(double x, double y, int top, int bottom) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private ConversationIndicator() {
    }

    static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen screen)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int top = screen.height - BOTTOM_OFFSET - PADDING;
        int bottom = top + font.lineHeight + 2 * PADDING - 1;
        GuiGraphics graphics = event.getGuiGraphics();
        for (Button button : layout(font, ClientConversation.state())) {
            boolean hovered = button.contains(event.getMouseX(), event.getMouseY(), top, bottom);
            int background = button.active() ? ACTIVE_BACKGROUND : hovered ? HOVER_BACKGROUND : IDLE_BACKGROUND;
            graphics.fill(button.left(), top, button.right(), bottom, background);
            graphics.drawString(font, button.text(), button.left() + PADDING, top + PADDING, button.color());
        }
    }

    static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen screen) || event.getButton() != 0) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int top = screen.height - BOTTOM_OFFSET - PADDING;
        int bottom = top + font.lineHeight + 2 * PADDING - 1;
        for (Button button : layout(font, ClientConversation.state())) {
            if (button.contains(event.getMouseX(), event.getMouseY(), top, bottom)) {
                ClientConversation.send(button.action());
                event.setCanceled(true);
                return;
            }
        }
    }

    private static List<Button> layout(Font font, ConversationState state) {
        List<Button> buttons = new ArrayList<>();
        int x = 2;
        boolean talking = state.hasPartner();
        x = add(buttons, font, x, Component.translatable("livingcolonists.indicator.players"),
                !talking && state.mode() == ChatMode.PLAYERS, ConversationAction.Action.MODE_PLAYERS, null);
        x = add(buttons, font, x, Component.translatable("livingcolonists.indicator.colony"),
                !talking && state.mode() == ChatMode.COLONY, ConversationAction.Action.MODE_COLONY, null);
        if (talking) {
            Component job = state.partnerJobKey().isEmpty()
                    ? Component.translatable("livingcolonists.job.unemployed")
                    : Component.translatable(state.partnerJobKey());
            add(buttons, font, x + GAP, Component.translatable("livingcolonists.indicator.partner", state.partnerName(), job),
                    true, ConversationAction.Action.RELEASE, PARTNER_COLOR);
        }
        return buttons;
    }

    private static int add(List<Button> buttons, Font font, int left, Component text, boolean active,
                           ConversationAction.Action action, Integer color) {
        int right = left + font.width(text) + 2 * PADDING;
        buttons.add(new Button(left, right, text, active, color != null ? color : active ? ACTIVE_COLOR : IDLE_COLOR, action));
        return right;
    }
}

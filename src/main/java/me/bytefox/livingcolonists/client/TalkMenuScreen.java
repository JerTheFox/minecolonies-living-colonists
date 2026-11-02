package me.bytefox.livingcolonists.client;

import java.util.List;

import me.bytefox.livingcolonists.network.TalkMenuState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Talking to a colonist without a language model (#68): a small sheet of paper with what they just said and a few
 * things to say to them. Choices go to the server as {@code /lc menu} commands; the answer comes back here and in chat.
 */
public final class TalkMenuScreen extends Screen {
    private static final int WIDTH = 236;
    private static final int HEIGHT = 166;
    private static final int PADDING = 12;
    private static final int INK = 0x3B2A1A;
    private static final int BUTTON_HEIGHT = 18;

    private final int colonyId;
    private final int citizenId;
    private final Component who;
    private Component line;

    private TalkMenuScreen(TalkMenuState state) {
        super(Component.literal(state.name()));
        colonyId = state.colonyId();
        citizenId = state.citizenId();
        who = state.jobKey().isEmpty() ? Component.literal(state.name())
                : Component.literal(state.name() + ", ").append(Component.translatable(state.jobKey()));
        line = state.line();
    }

    public static void apply(TalkMenuState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (state.open()) {
            minecraft.setScreen(new TalkMenuScreen(state));
        } else if (minecraft.screen instanceof TalkMenuScreen open && open.colonyId == state.colonyId()
                && open.citizenId == state.citizenId()) {
            open.line = state.line();
        }
    }

    private int left() {
        return (width - WIDTH) / 2;
    }

    private int top() {
        return (height - HEIGHT) / 2;
    }

    @Override
    protected void init() {
        int columns = 2;
        int buttonWidth = (WIDTH - 2 * PADDING - 6) / columns;
        int y = top() + HEIGHT - PADDING - 3 * (BUTTON_HEIGHT + 4) + 4;
        List<String> options = TalkMenuState.OPTIONS;
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            int x = left() + PADDING + (i % columns) * (buttonWidth + 6);
            int row = y + (i / columns) * (BUTTON_HEIGHT + 4);
            addRenderableWidget(Button.builder(Component.translatable("livingcolonists.menu." + option), button -> choose(option))
                    .bounds(x, row, buttonWidth, BUTTON_HEIGHT).build());
        }
    }

    private void choose(String option) {
        if (minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("lc menu " + colonyId + " " + citizenId + " " + option);
        }
        if (option.equals("bye")) {
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();
        graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xFF5A2A1A);
        graphics.fill(left + 3, top + 3, left + WIDTH - 3, top + HEIGHT - 3, 0xFFF3E9D2);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = left() + PADDING;
        int y = top() + PADDING;
        graphics.drawString(font, who.copy().withStyle(style -> style.withBold(true)), x, y, INK, false);
        y += font.lineHeight + 6;
        for (FormattedCharSequence part : font.split(line, WIDTH - 2 * PADDING)) {
            graphics.drawString(font, part, x, y, INK, false);
            y += font.lineHeight;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

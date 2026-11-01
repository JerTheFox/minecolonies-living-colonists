package me.bytefox.livingcolonists.client;

import java.util.List;

import me.bytefox.livingcolonists.network.PromiseBookPages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/**
 * The promise notebook (#60): an open book, two pages at a time, with the server's pages and their buttons. Drawn on
 * MineColonies' own town hall book so it looks like part of the colony; the texture is theirs and stays in their jar
 * (we only point to it), and without it the pages are drawn on plain paper.
 */
public final class PromiseBookScreen extends Screen {
    private static final ResourceLocation BOOK = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/townhall_book.png");
    private static final int WIDTH = 374;
    private static final int HEIGHT = 243;
    private static final int PAGE_X = 34;
    private static final int PAGE_GAP = 175;
    private static final int TEXT_Y = 32;
    private static final int TEXT_WIDTH = 130;
    private static final int LINES = 18;
    private static final int INK = 0x3B2A1A;

    private List<Component> pages;
    private int spread;
    private PageButton back;
    private PageButton forward;

    private PromiseBookScreen(List<Component> pages) {
        super(Component.translatable("item.livingcolonists.promise_book"));
        this.pages = pages;
    }

    public static void apply(PromiseBookPages payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new PromiseBookScreen(payload.pages()));
        } else if (minecraft.screen instanceof PromiseBookScreen open) {
            open.pages = payload.pages();
            open.spread = Math.min(open.spread, open.lastSpread());
            open.updateButtons();
        }
    }

    @Override
    protected void init() {
        int left = left();
        int bottom = top() + HEIGHT - 30;
        back = addRenderableWidget(new PageButton(left + 30, bottom, false, button -> turn(-1), true));
        forward = addRenderableWidget(new PageButton(left + WIDTH - 30 - 23, bottom, true, button -> turn(1), true));
        updateButtons();
    }

    private int left() {
        return (width - WIDTH) / 2;
    }

    private int top() {
        return Math.max(2, (height - HEIGHT) / 2);
    }

    private int lastSpread() {
        return Math.max(0, (pages.size() - 1) / 2);
    }

    private void turn(int by) {
        spread = Math.max(0, Math.min(lastSpread(), spread + by));
        updateButtons();
    }

    private void updateButtons() {
        if (back != null) {
            back.visible = spread > 0;
            forward.visible = spread < lastSpread();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            if (index >= pages.size()) {
                break;
            }
            List<FormattedCharSequence> lines = lines(index);
            int x = left() + PAGE_X + side * PAGE_GAP;
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawString(font, lines.get(i), x, top() + TEXT_Y + i * font.lineHeight, INK, false);
            }
            Component number = Component.literal((index + 1) + " / " + pages.size());
            graphics.drawString(font, number, x + TEXT_WIDTH - font.width(number), top() + HEIGHT - 40, 0x8A7556, false);
        }
        Style hovered = styleAt(mouseX, mouseY);
        if (hovered != null) {
            graphics.renderComponentHoverEffect(font, hovered, mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        int top = top();
        if (minecraft.getResourceManager().getResource(BOOK).isPresent()) {
            graphics.blit(BOOK, left, top, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        } else {
            graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xFF5A2A1A);
            graphics.fill(left + 6, top + 6, left + WIDTH - 6, top + HEIGHT - 6, 0xFFF3E9D2);
        }
    }

    private List<FormattedCharSequence> lines(int index) {
        List<FormattedCharSequence> lines = font.split(pages.get(index), TEXT_WIDTH);
        return lines.size() > LINES ? lines.subList(0, LINES) : lines;
    }

    /** The text style under the mouse on either page, as in vanilla's book screen; null over blank paper. */
    public Style styleAt(double mouseX, double mouseY) {
        for (int side = 0; side < 2; side++) {
            int index = spread * 2 + side;
            int x = left() + PAGE_X + side * PAGE_GAP;
            if (index >= pages.size() || mouseX < x || mouseX >= x + TEXT_WIDTH) {
                continue;
            }
            int line = (int) Math.floor((mouseY - top() - TEXT_Y) / font.lineHeight);
            List<FormattedCharSequence> lines = lines(index);
            if (mouseY < top() + TEXT_Y || line >= lines.size()) {
                return null;
            }
            return font.getSplitter().componentStyleAtWidth(lines.get(line), (int) mouseX - x);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            Style style = styleAt(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null && handleComponentClicked(style)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

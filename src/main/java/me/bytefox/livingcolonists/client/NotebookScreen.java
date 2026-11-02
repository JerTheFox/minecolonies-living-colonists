package me.bytefox.livingcolonists.client;

import java.util.List;

import me.bytefox.livingcolonists.network.NotebookPages;
import me.bytefox.livingcolonists.network.NotebookRequest;
import me.bytefox.livingcolonists.notebook.Notebook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Colony Notebook (#66): an open book, two pages at a time, with tabs for its sections on the right, as in MineColonies' own windows. Drawn on
 * MineColonies' own town hall book, window tabs and icons so it looks like part of the colony; those textures are theirs
 * and stay in their jar (we only point to them), and without them the notebook is drawn on plain paper. The pages come
 * from the server; after a button in them is used, the notebook asks for them again.
 */
public final class NotebookScreen extends Screen {
    private static final ResourceLocation BOOK = ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/townhall_book.png");
    private static final int WIDTH = 374;
    private static final int HEIGHT = 243;
    private static final int PAGE_X = 34;
    private static final int PAGE_GAP = 175;
    private static final int TEXT_Y = 32;
    private static final int TEXT_WIDTH = 130;
    private static final int LINES = 18;
    private static final int INK = 0x3B2A1A;
    /** MineColonies' window tabs: 32 by 26, 28 apart, 12 of their width under the paper, the icon 5 from the end. */
    private static final int TAB_WIDTH = 32;
    private static final int TAB_HEIGHT = 26;
    private static final int TAB_STEP = 28;
    private static final int TAB_TOP = 36;
    private static final int TAB_UNDER = 12;
    /** The open section's tab sticks out a little further. */
    private static final int TAB_OUT = 3;
    private static final int ICON_INSET = 5;
    private static final int ICON = 20;
    /** Ticks after a button before the pages are asked for again: the command runs first. */
    private static final int REFRESH_DELAY = 8;

    private List<Notebook.Section> sections;
    private int section;
    private int spread;
    private int refreshIn = -1;
    private PageButton back;
    private PageButton forward;

    private NotebookScreen(List<Notebook.Section> sections) {
        super(Component.translatable("item.livingcolonists.promise_book"));
        this.sections = sections;
    }

    public static void apply(NotebookPages payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.open()) {
            minecraft.setScreen(new NotebookScreen(payload.sections()));
        } else if (minecraft.screen instanceof NotebookScreen open) {
            open.sections = payload.sections();
            open.section = Math.min(open.section, open.sections.size() - 1);
            open.spread = Math.min(open.spread, open.lastSpread());
            open.updateButtons();
        }
    }

    /** The key of the section shown now, for live runs. */
    public String sectionKey() {
        return sections.isEmpty() ? "" : sections.get(section).key();
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

    private List<Component> pages() {
        return sections.isEmpty() ? List.of() : sections.get(section).pages();
    }

    private int lastSpread() {
        return Math.max(0, (pages().size() - 1) / 2);
    }

    private void turn(int by) {
        spread = Math.max(0, Math.min(lastSpread(), spread + by));
        updateButtons();
    }

    /** Opens a section at its first pages. */
    public void open(int index) {
        section = Math.max(0, Math.min(sections.size() - 1, index));
        spread = 0;
        updateButtons();
    }

    private void updateButtons() {
        if (back != null) {
            back.visible = spread > 0;
            forward.visible = spread < lastSpread();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (refreshIn > 0 && --refreshIn == 0) {
            PacketDistributor.sendToServer(new NotebookRequest());
            refreshIn = -1;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        List<Component> pages = pages();
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
        int tab = tabAt(mouseX, mouseY);
        if (tab >= 0) {
            graphics.renderTooltip(font, sections.get(tab).title(), mouseX, mouseY);
            return;
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
        boolean colonies = minecraft.getResourceManager().getResource(BOOK).isPresent();
        // Tabs as in MineColonies' own windows: their paper tab with a module icon on it, tucked under the book.
        for (int i = 0; i < sections.size(); i++) {
            int x = tabX(i);
            int y = tabY(i);
            ResourceLocation tab = ResourceLocation.fromNamespaceAndPath("minecolonies",
                    "textures/gui/modules/tab_right_side" + (i % 4 + 1) + ".png");
            ResourceLocation icon = ResourceLocation.fromNamespaceAndPath("minecolonies",
                    "textures/gui/modules/" + icon(sections.get(i).key()) + ".png");
            if (colonies && minecraft.getResourceManager().getResource(tab).isPresent()) {
                graphics.blit(tab, x, y, 0, 0, TAB_WIDTH, TAB_HEIGHT, TAB_WIDTH, TAB_HEIGHT);
            } else {
                graphics.fill(x, y, x + TAB_WIDTH, y + TAB_HEIGHT, 0xFFE8D9B8);
            }
            if (minecraft.getResourceManager().getResource(icon).isPresent()) {
                graphics.blit(icon, x + TAB_WIDTH - ICON_INSET - ICON, y + 3, 0, 0, ICON, ICON, ICON, ICON);
            }
        }
        if (colonies) {
            graphics.blit(BOOK, left, top, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        } else {
            graphics.fill(left, top, left + WIDTH, top + HEIGHT, 0xFF5A2A1A);
            graphics.fill(left + 6, top + 6, left + WIDTH - 6, top + HEIGHT - 6, 0xFFF3E9D2);
        }
    }

    private int tabX(int index) {
        return left() + WIDTH - TAB_UNDER + (index == section ? TAB_OUT : 0);
    }

    private int tabY(int index) {
        return top() + TAB_TOP + index * TAB_STEP;
    }

    /** MineColonies' module icon for a section. */
    private static String icon(String section) {
        return switch (section) {
            case "promises" -> "info";
            case "relations" -> "happiness";
            case "orders" -> "scepter";
            case "colony" -> "family";
            default -> "help";
        };
    }

    private List<FormattedCharSequence> lines(int index) {
        List<FormattedCharSequence> lines = font.split(pages().get(index), TEXT_WIDTH);
        return lines.size() > LINES ? lines.subList(0, LINES) : lines;
    }

    /** The tab under the mouse, or -1. */
    private int tabAt(double mouseX, double mouseY) {
        for (int i = 0; i < sections.size(); i++) {
            if (mouseX >= left() + WIDTH && mouseX < tabX(i) + TAB_WIDTH && mouseY >= tabY(i) && mouseY < tabY(i) + TAB_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    /** The text style under the mouse on either page, as in vanilla's book screen; null over blank paper. */
    public Style styleAt(double mouseX, double mouseY) {
        List<Component> pages = pages();
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
            int tab = tabAt(mouseX, mouseY);
            if (tab >= 0) {
                open(tab);
                return true;
            }
            Style style = styleAt(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null && handleComponentClicked(style)) {
                refreshIn = REFRESH_DELAY;
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

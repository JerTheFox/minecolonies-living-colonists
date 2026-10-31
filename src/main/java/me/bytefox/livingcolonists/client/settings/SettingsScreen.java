package me.bytefox.livingcolonists.client.settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import me.bytefox.livingcolonists.network.SettingsRequest;
import me.bytefox.livingcolonists.network.SettingsState;
import me.bytefox.livingcolonists.settings.Setting;
import me.bytefox.livingcolonists.client.ClientConfig;
import me.bytefox.livingcolonists.settings.Presets;
import me.bytefox.livingcolonists.settings.SettingsSchema;
import me.bytefox.livingcolonists.settings.SettingsService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The mod's settings: connection, behaviour, limits and usage, all of
 * {@code livingcolonists-common.toml}. Changes apply at once, no restart. In a world it edits the
 * server's settings (operators only, others just look); from the main menu, the local file.
 * API keys are never shown: only whether they are set; typing one replaces it.
 */
public final class SettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int TOP = 48;
    private static final int BOTTOM = 52;

    private enum Page {
        CONNECTION, BEHAVIOUR, LIMITS, USAGE
    }

    private final Screen parent;
    private Page page = Page.CONNECTION;
    private SettingsState state;
    /** Values typed or picked and not saved yet, by setting id; for keys, the new key. */
    private final Map<String, String> edits = new HashMap<>();
    /** Keys asked to be cleared. */
    private final Set<String> cleared = new HashSet<>();
    private Component status = Component.empty();
    private Rows rows;
    private boolean initializing;

    public SettingsScreen(Screen parent) {
        super(Component.translatable("livingcolonists.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientSettings.opened(this);
        if (state == null) {
            status = Component.translatable("livingcolonists.settings.loading");
            // From the main menu the answer comes back at once, while this screen is still being built.
            initializing = true;
            ClientSettings.request(SettingsRequest.Action.OPEN, Map.of());
            initializing = false;
        }
        int tabWidth = Math.min(100, (width - 20) / Page.values().length);
        int x = (width - tabWidth * Page.values().length) / 2;
        for (Page each : Page.values()) {
            Button tab = Button.builder(Component.translatable("livingcolonists.settings.tab." + each.name().toLowerCase(Locale.ROOT)),
                    button -> {
                        page = each;
                        rebuildWidgets();
                    }).bounds(x, 22, tabWidth - 2, 20).build();
            tab.active = each != page;
            addRenderableWidget(tab);
            x += tabWidth;
        }
        rows = new Rows();
        addRenderableWidget(rows);
        fillRows();
        boolean editable = state != null && state.canEdit();
        Button save = Button.builder(Component.translatable("livingcolonists.settings.save"), button -> save())
                .bounds(width / 2 - 154, height - 28, 150, 20).build();
        save.active = editable;
        save.visible = page != Page.USAGE;
        addRenderableWidget(save);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 + 4, height - 28, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        List<FormattedCharSequence> lines = font.split(status, width - 40);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            graphics.drawCenteredString(font, lines.get(i), width / 2, height - 50 + i * 10, 0xFFFFFF);
        }
    }

    @Override
    public void onClose() {
        ClientSettings.closed(this);
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        ClientSettings.closed(this);
    }

    /** New settings from the server or the local file, after opening, saving or a check. */
    void update(SettingsState newState) {
        boolean saved = "livingcolonists.settings.saved".equals(newState.message());
        if (saved) {
            edits.clear();
            cleared.clear();
        }
        List<String> models = newState.models().isEmpty() && state != null ? state.models() : newState.models();
        state = new SettingsState(newState.canEdit(), newState.values(), newState.usage(), newState.message(), models);
        status = newState.message().isEmpty() ? (newState.canEdit()
                ? Component.translatable("livingcolonists.settings.hint").withStyle(ChatFormatting.GRAY)
                : Component.translatable("livingcolonists.settings.read_only").withStyle(ChatFormatting.YELLOW))
                : message(newState.message(), models.size());
        if (!initializing && minecraft != null && minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    private static Component message(String message, int models) {
        if (message.startsWith("livingcolonists.")) {
            return Component.translatable(message, models);
        }
        return Component.literal(message);
    }

    private void save() {
        Map<String, String> changes = new HashMap<>(edits);
        cleared.forEach(id -> changes.put(id, ""));
        if (changes.isEmpty()) {
            status = Component.translatable("livingcolonists.settings.nothing_changed");
            return;
        }
        status = Component.translatable("livingcolonists.settings.saving");
        ClientSettings.request(SettingsRequest.Action.APPLY, changes);
    }

    private void fillRows() {
        if (state == null) {
            return;
        }
        if (page == Page.USAGE) {
            if (state.usage().isEmpty()) {
                rows.add(new TextRow(Component.translatable("livingcolonists.debug.stats.empty")));
            }
            state.usage().forEach(line -> font.split(Component.literal(line), width - 60)
                    .forEach(part -> rows.add(new TextRow(part))));
            return;
        }
        Setting.Tab tab = Setting.Tab.valueOf(page.name());
        if (page == Page.CONNECTION) {
            List<Button> presets = new java.util.ArrayList<>();
            for (Presets preset : Presets.values()) {
                Button button = Button.builder(Component.translatable("livingcolonists.settings.preset", preset.label()), b -> {
                    // Fills the fields like typing would; nothing is saved until Save.
                    preset.fields().forEach((id, value) -> SettingsSchema.find(id).ifPresent(setting -> edit(setting, value)));
                    status = Component.translatable("livingcolonists.settings.preset_set", preset.label());
                    rebuildWidgets();
                }).size(120, 20).build();
                button.active = state.canEdit();
                presets.add(button);
            }
            rows.add(new ButtonsRow(presets));
            rows.add(new ButtonsRow(List.of(
                    action("livingcolonists.settings.test_chat", SettingsRequest.Action.TEST_CHAT),
                    action("livingcolonists.settings.test_character", SettingsRequest.Action.TEST_CHARACTER),
                    action("livingcolonists.settings.load_models", SettingsRequest.Action.MODELS))));
        }
        for (Setting setting : SettingsSchema.ALL) {
            if (setting.tab() == tab) {
                rows.add(new SettingRow(setting));
                if (setting.id().equals("speechBubbles")) {
                    rows.add(new ClientRow());
                }
            }
        }
    }

    private Button action(String key, SettingsRequest.Action action) {
        Button button = Button.builder(Component.translatable(key), b -> {
            status = Component.translatable("livingcolonists.settings.working");
            ClientSettings.request(action, Map.of());
        }).size(120, 20).build();
        button.active = state != null && state.canEdit();
        return button;
    }

    private String shown(Setting setting) {
        String value = edits.getOrDefault(setting.id(), state.values().getOrDefault(setting.id(), ""));
        return setting.kind().type() == Setting.Type.LIST ? value.replace("\n", "; ") : value;
    }

    private void edit(Setting setting, String value) {
        String stored = setting.kind().type() == Setting.Type.LIST
                ? String.join("\n", value.split("\\s*;\\s*")) : value;
        if (setting.kind().type() == Setting.Type.SECRET) {
            if (stored.isEmpty()) {
                edits.remove(setting.id());
            } else {
                edits.put(setting.id(), stored);
                cleared.remove(setting.id());
            }
            return;
        }
        if (stored.equals(state.values().get(setting.id()))) {
            edits.remove(setting.id());
        } else {
            edits.put(setting.id(), stored);
        }
    }

    // ---- the list

    private final class Rows extends ContainerObjectSelectionList<Row> {
        Rows() {
            super(SettingsScreen.this.minecraft, SettingsScreen.this.width, SettingsScreen.this.height - TOP - BOTTOM, TOP,
                    ROW_HEIGHT);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, width - 40);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    private final class TextRow extends Row {
        private final FormattedCharSequence text;

        TextRow(Component text) {
            this(text.getVisualOrderText());
        }

        TextRow(FormattedCharSequence text) {
            this.text = text;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovered, float partialTick) {
            graphics.drawString(font, text, left, top + 6, 0xFFFFFF);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class ButtonsRow extends Row {
        private final List<Button> buttons;

        ButtonsRow(List<Button> buttons) {
            this.buttons = buttons;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovered, float partialTick) {
            int each = (rowWidth - 4 * (buttons.size() - 1)) / buttons.size();
            int x = left;
            for (Button button : buttons) {
                button.setX(x);
                button.setY(top);
                button.setWidth(each);
                button.render(graphics, mouseX, mouseY, partialTick);
                x += each + 4;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return buttons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return buttons;
        }
    }

    /**
     * The player's own choice of colony chat lines (#58), next to the speech bubbles: kept on this client, saved at
     * once, open to every player whatever the server's settings.
     */
    private final class ClientRow extends Row {
        private static final String KEY = "livingcolonists.configuration.colonyChatLines";
        private final Component label = Component.translatable(KEY);
        private final CycleButton<Boolean> button = CycleButton.onOffBuilder(ClientConfig.COLONY_CHAT_LINES.get())
                .displayOnlyValue().create(0, 0, 100, 20, label, (b, on) -> {
                    ClientConfig.COLONY_CHAT_LINES.set(on);
                    ClientConfig.SPEC.save();
                });

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovered, float partialTick) {
            int labelWidth = rowWidth * 2 / 5;
            String help = " (?)";
            String name = font.substrByWidth(label, labelWidth - 4 - font.width(help)).getString();
            graphics.drawString(font, name, left, top + 6, 0xFFFFFFFF);
            graphics.drawString(font, help, left + font.width(name), top + 6, 0xFF808080);
            button.setX(left + labelWidth);
            button.setY(top + 1);
            button.setWidth(rowWidth - labelWidth);
            button.render(graphics, mouseX, mouseY, partialTick);
            if (mouseX >= left && mouseX < left + rowWidth && mouseY >= top && mouseY < top + rowHeight && I18n.exists(KEY + ".tooltip")) {
                setTooltipForNextRenderPass(font.split(Component.translatable(KEY + ".tooltip"), 240));
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(button);
        }
    }

    /** A label (hover for help) and the widget to change the setting. */
    private final class SettingRow extends Row {
        private final Setting setting;
        private final Component label;
        private final List<AbstractWidget> widgets = new ArrayList<>();

        SettingRow(Setting setting) {
            this.setting = setting;
            this.label = Component.translatable(setting.translationKey());
            boolean editable = state.canEdit();
            Setting.Kind kind = setting.kind();
            switch (kind.type()) {
                case BOOL -> widgets.add(CycleButton.onOffBuilder(Boolean.parseBoolean(shown(setting)))
                        .displayOnlyValue().create(0, 0, 100, 20, label, (button, value) -> edit(setting, String.valueOf(value))));
                case CHOICE -> widgets.add(CycleButton.<String>builder(Component::literal).withValues(kind.choices())
                        .withInitialValue(shown(setting)).displayOnlyValue()
                        .create(0, 0, 100, 20, label, (button, value) -> edit(setting, value)));
                case SECRET -> {
                    EditBox box = box(edits.getOrDefault(setting.id(), ""));
                    box.setHint(Component.translatable(cleared.contains(setting.id()) ? "livingcolonists.settings.key_cleared"
                            : SettingsService.SECRET_SET.equals(state.values().get(setting.id()))
                            ? "livingcolonists.settings.key_set" : "livingcolonists.settings.key_not_set")
                            .withStyle(ChatFormatting.DARK_GRAY));
                    box.setFormatter((text, start) -> FormattedCharSequence.forward("*".repeat(text.length()),
                            net.minecraft.network.chat.Style.EMPTY));
                    widgets.add(box);
                    Button clear = Button.builder(Component.translatable("livingcolonists.settings.clear"), b -> {
                        cleared.add(setting.id());
                        edits.remove(setting.id());
                        rebuildWidgets();
                    }).size(50, 20).build();
                    clear.active = editable;
                    widgets.add(clear);
                }
                default -> {
                    EditBox box = box(shown(setting));
                    box.setResponder(text -> edit(setting, text));
                    widgets.add(box);
                    if (setting.id().toLowerCase(Locale.ROOT).endsWith("model") && !state.models().isEmpty()) {
                        Button next = Button.builder(Component.literal("▼"), b -> {
                            List<String> models = state.models();
                            int at = models.indexOf(box.getValue());
                            box.setValue(models.get((at + 1) % models.size()));
                        }).size(20, 20).build();
                        next.active = editable;
                        widgets.add(next);
                    }
                }
            }
            if (kind.type() == Setting.Type.SECRET) {
                ((EditBox) widgets.get(0)).setResponder(text -> edit(setting, text));
            }
            widgets.forEach(widget -> widget.active = editable && widget.active);
        }

        private EditBox box(String value) {
            EditBox box = new EditBox(font, 0, 0, 100, 18, label);
            box.setMaxLength(4096);
            box.setValue(value);
            box.moveCursorToStart(false);
            return box;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovered, float partialTick) {
            int labelWidth = rowWidth * 2 / 5;
            boolean changed = edits.containsKey(setting.id()) || cleared.contains(setting.id());
            String help = changed ? " * (?)" : " (?)";
            String name = font.substrByWidth(label, labelWidth - 4 - font.width(help)).getString();
            graphics.drawString(font, name, left, top + 6, changed ? 0xFFFFD866 : 0xFFFFFFFF);
            graphics.drawString(font, help, left + font.width(name), top + 6, 0xFF808080);
            int x = left + labelWidth;
            int room = rowWidth - labelWidth;
            int fixed = widgets.stream().skip(1).mapToInt(widget -> widget.getWidth() + 4).sum();
            for (int i = 0; i < widgets.size(); i++) {
                AbstractWidget widget = widgets.get(i);
                if (i == 0) {
                    widget.setWidth(room - fixed);
                }
                widget.setX(x);
                widget.setY(top + 1);
                widget.render(graphics, mouseX, mouseY, partialTick);
                x += widget.getWidth() + 4;
            }
            // The help shows over the whole row, but not over a field being typed into.
            if (mouseX >= left && mouseX < left + rowWidth && mouseY >= top && mouseY < top + rowHeight
                    && widgets.stream().noneMatch(widget -> widget.isFocused() && widget.isMouseOver(mouseX, mouseY))
                    && I18n.exists(setting.translationKey() + ".tooltip")) {
                setTooltipForNextRenderPass(font.split(Component.translatable(setting.translationKey() + ".tooltip"), 240));
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }
    }
}

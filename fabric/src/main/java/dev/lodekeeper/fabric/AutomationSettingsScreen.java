package dev.lodekeeper.fabric;

import dev.lodekeeper.core.SettingSpec;
import dev.lodekeeper.core.SettingsDraft;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AutomationSettingsScreen extends Screen implements dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier {
    private static final int PANEL = 0xc918242b;
    private static final int CYAN = 0xff55dce8;
    private static final int PRIMARY = 0xffedf4f6;
    private static final int SECONDARY = 0xffa8c2df;
    private static final int GREEN = 0xffabf49b;
    private static final int ERROR = 0xffff8b8b;
    private static final int ROW_HEIGHT = 38;

    private final Screen parent;
    private final LodekeeperConfig config;
    private final SettingsDraft draft;
    private final Runnable savedCallback;
    private final WorldProtection protection;
    private final List<SettingWidget> settingWidgets = new ArrayList<>();
    private String category;
    private String query;
    private String message = "";
    private int page;
    private int panelX;
    private int panelWidth;
    private int rowTop;
    private int pageSize;
    private List<SettingSpec> results = List.of();
    private TextFieldWidget searchField;

    public AutomationSettingsScreen(Screen parent, LodekeeperConfig config, Runnable savedCallback) {
        this(parent, config, new SettingsDraft(LodekeeperConfig.specs(), config::read),
                savedCallback, "All", "", 0, null);
    }

    public AutomationSettingsScreen(LodekeeperConfig config, Runnable savedCallback) {
        this(null, config, savedCallback);
    }

    AutomationSettingsScreen(LodekeeperConfig config, Runnable savedCallback, WorldProtection protection) {
        this(null, config, new SettingsDraft(LodekeeperConfig.specs(), config::read),
                savedCallback, "All", "", 0, protection);
    }

    private AutomationSettingsScreen(Screen parent, LodekeeperConfig config, SettingsDraft draft,
                                     Runnable savedCallback, String category, String query, int page, WorldProtection protection) {
        super(Text.literal("Lodekeeper automation settings"));
        this.parent = parent;
        this.protection = protection;
        this.config = config;
        this.draft = draft;
        this.savedCallback = savedCallback == null ? () -> {} : savedCallback;
        this.category = category;
        this.query = query;
        this.page = page;
    }

    @Override
    protected void init() {
        settingWidgets.clear();

        panelWidth = Math.max(1, Math.min(760, width - 24));
        panelX = (width - panelWidth) / 2;
        int contentWidth = Math.max(1, panelWidth - 24);
        int categoryWidth = Math.min(154, Math.max(1, contentWidth / 3));
        int filterWidth = Math.min(54, Math.max(1, contentWidth / 5));
        int searchWidth = Math.max(1, contentWidth - categoryWidth - filterWidth - 12);
        int filterY = Math.max(42, Math.min(48, height / 5));
        int left = panelX + 12;

        ButtonWidget categoryButton = button("Category: " + category, left, filterY, categoryWidth, 20,
                "Cycle through categories. Search can be combined with a category.", ignored -> {
                    if (!syncVisibleEditors()) return;
                    List<String> categories = categories();
                    int next = (categories.indexOf(category) + 1) % categories.size();
                    category = categories.get(next);
                    query = searchField.getText();
                    page = 0;
                    reopen();
                });
        searchField = new TextFieldWidget(textRenderer, left + categoryWidth + 6, filterY,
                searchWidth, 20, Text.literal("Search settings"));
        searchField.setMaxLength(64);
        searchField.setText(query);
        searchField.setPlaceholder(Text.literal("Search settings"));
        searchField.setTooltip(Tooltip.of(Text.literal("Search setting names, keys, categories, and help text.")));

        ButtonWidget filterButton = button("Filter", left + categoryWidth + searchWidth + 12, filterY, filterWidth, 20,
                "Apply the selected category and search text.", ignored -> applyFilters());

        results = draft.matching(category, query);
        rowTop = Math.max(filterY + 34, 80);
        int rowBottom = Math.max(rowTop + ROW_HEIGHT, height - 92);
        pageSize = Math.max(1, Math.min(8, (rowBottom - rowTop) / ROW_HEIGHT));
        int pageCount = Math.max(1, (results.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));
        addDrawable(new PanelDrawable());
        add(categoryButton);
        add(searchField);
        add(filterButton);
        int valueAreaWidth = Math.min(184, Math.max(124, contentWidth / 3));
        int valueX = panelX + panelWidth - 12 - valueAreaWidth;
        int valueResetWidth = Math.min(50, Math.max(42, valueAreaWidth / 4));
        int settingWidth = Math.max(1, valueAreaWidth - valueResetWidth - 4);

        int start = page * pageSize;
        int end = Math.min(results.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            SettingSpec spec = results.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT + 7;
            int widgetY = y - 1;
            ClickableWidget widget = createSettingWidget(spec, valueX, widgetY, settingWidth);
            widget.active = draft.enabled(spec.key());
            ButtonWidget reset = button("Reset", valueX + settingWidth + 4, widgetY, valueResetWidth, 22,
                    "Reset " + spec.label() + " to its default in this draft.", ignored -> {
                        if (!syncVisibleEditors(spec.key())) return;
                        draft.reset(spec.key());
                        query = searchField.getText();
                        message = "";
                        reopen();
                    });
            reset.active = draft.enabled(spec.key());
            settingWidgets.add(new SettingWidget(spec, widget, reset));
            add(widget);
            add(reset);
        }

        int navY = height - 44;
        int navWidth = Math.min(64, Math.max(42, contentWidth / 5));
        ButtonWidget previous = button("Prev", width / 2 - navWidth - 48, navY, navWidth, 20,
                "Show the previous settings page.", ignored -> changePage(-1));
        ButtonWidget next = button("Next", width / 2 + 48, navY, navWidth, 20,
                "Show the next settings page.", ignored -> changePage(1));
        int visiblePageCount = Math.max(1, (results.size() + pageSize - 1) / pageSize);
        previous.active = page > 0;
        next.active = page + 1 < visiblePageCount;
        add(previous);
        add(next);

        int actionY = height - 23;
        int resetWidth = Math.min(86, Math.max(38, contentWidth / 4));
        int closeWidth = Math.min(126, Math.max(52, contentWidth / 3));
        int saveWidth = Math.min(82, Math.max(38, contentWidth / 4));
        int gap = 6;
        int groupWidth = resetWidth + closeWidth + saveWidth + gap * 2;
        int actionX = panelX + (panelWidth - groupWidth) / 2;
        add(button("Reset all", actionX, actionY, resetWidth, 20,
                "Reset all 48 settings in this draft. Save to apply the defaults.", ignored -> resetAll()));
        add(button("Discard", actionX + resetWidth + gap, actionY, closeWidth, 20,
                "Discard unsaved changes and return to the previous screen.", ignored -> close()));
        add(button("Save", actionX + resetWidth + closeWidth + gap * 2, actionY, saveWidth, 20,
                "Validate and save every setting.", ignored -> save()));

        if (protection != null) add(button("Protected plots", panelX + panelWidth - 118, 25, 106, 18,
                "Manage protected 3D plots and preferred crafting stations. Plot changes save immediately.", ignored -> {
                    if (!syncVisibleEditors()) return;
                    query = searchField.getText();
                    client.setScreen(new ClaimsScreen(this, protection));
                }));
        refreshEnabledState();
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    private void drawPanels(DrawContext context) {
        int bottom = Math.max(16, height - 2);
        context.fill(panelX - 2, 10, panelX + panelWidth + 2, bottom, PANEL);
        context.fill(panelX - 2, 10, panelX + panelWidth + 2, 12, CYAN);
        int start = page * pageSize;
        int end = Math.min(results.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            int y = rowTop + (index - start) * ROW_HEIGHT;
            context.fill(panelX + 8, y, panelX + panelWidth - 8, y + ROW_HEIGHT - 1,
                    (index - start) % 2 == 0 ? 0x70344852 : 0x5040525d);
            context.fill(panelX + 8, y, panelX + 10, y + ROW_HEIGHT - 1, CYAN);
        }
    }

    private void drawText(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("LODEKEEPER · AUTOMATION SETTINGS"),
                width / 2, 17, CYAN);
        int start = page * pageSize;
        int end = Math.min(results.size(), start + pageSize);
        int valueX = panelX + panelWidth - 12 - Math.min(184, Math.max(124, (panelWidth - 24) / 3));
        int labelWidth = Math.max(1, valueX - panelX - 30);
        for (int index = start; index < end; index++) {
            SettingSpec spec = results.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT;
            boolean enabled = draft.enabled(spec.key());
            context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(spec.label(), labelWidth)),
                    panelX + 16, y + 4, enabled ? PRIMARY : SECONDARY);
            context.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(helpText(spec), labelWidth)),
                    panelX + 16, y + 19, SECONDARY, false);
        }
        String pageLabel = results.isEmpty() ? "0 settings" : (start + 1) + "–" + end + " of " + results.size();
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(pageLabel), width / 2, height - 38, SECONDARY);
        String state = message.isBlank() ? (draft.isDirty() ? "Unsaved changes · Esc discards" : "Saved settings") : message;
        int stateColor = message.isBlank() ? (draft.isDirty() ? GREEN : SECONDARY) : ERROR;
        context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(state, panelWidth - 24)),
                panelX + 12, height - 59, stateColor);
        if (results.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal("No settings match."),
                width / 2, rowTop + 12, SECONDARY);
    }

    private ClickableWidget createSettingWidget(SettingSpec spec, int x, int y, int widgetWidth) {
        Object value = draft.value(spec.key());
        if (spec.type() == SettingSpec.Type.BOOLEAN) {
            boolean enabled = Boolean.TRUE.equals(value);
            return button(enabled ? "On" : "Off", x, y, widgetWidth, 22,
                    spec.label() + ". " + helpText(spec), ignored -> {
                        if (!syncVisibleEditors()) return;
                        draft.setValue(spec.key(), !Boolean.TRUE.equals(draft.value(spec.key())));
                        query = searchField.getText();
                        message = "";
                        reopen();
                    });
        }
        if (spec.type() == SettingSpec.Type.STRING_MAP) {
            int count = draft.navigationPreferences().size();
            return button("Edit · " + count + " entries", x, y, widgetWidth, 22,
                    spec.label() + ". " + helpText(spec), ignored -> {
                        if (!syncVisibleEditors()) return;
                        query = searchField.getText();
                        client.setScreen(new NavigationPreferencesScreen(this, draft));
                    });
        }

        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, widgetWidth, 22,
                Text.literal(spec.label()));
        field.setMaxLength(spec.type() == SettingSpec.Type.TEXT ? (int) spec.maximum() : 16);
        field.setText(displayValue(value));
        field.setPlaceholder(Text.literal(spec.label()));
        field.setTooltip(Tooltip.of(Text.literal(spec.label() + ". " + helpText(spec))));
        if (spec.type() == SettingSpec.Type.INTEGER) field.setTextPredicate(text -> text.matches("\\d*"));
        if (spec.type() == SettingSpec.Type.DECIMAL) field.setTextPredicate(text -> text.matches("\\d*(\\.\\d*)?"));
        field.setChangedListener(text -> {
            try { draft.setValue(spec.key(), spec.parse(text)); }
            catch (IllegalArgumentException ignored) { }
        });
        return field;
    }

    private ButtonWidget button(String label, int x, int y, int buttonWidth, int buttonHeight,
                                String help, ButtonWidget.PressAction action) {
        return ButtonWidget.builder(Text.literal(label), action)
                .dimensions(x, y, buttonWidth, buttonHeight)
                .tooltip(Tooltip.of(Text.literal(help)))
                .build();
    }

    private <T extends ClickableWidget> T add(T widget) {
        return addDrawableChild(widget);
    }

    private List<String> categories() {
        List<String> categories = new ArrayList<>();
        categories.add("All");
        for (SettingSpec spec : draft.specs())
            if (!categories.contains(spec.category())) categories.add(spec.category());
        return categories;
    }

    private void applyFilters() {
        if (!syncVisibleEditors()) return;
        query = searchField.getText();
        page = 0;
        message = "";
        reopen();
    }

    private void changePage(int direction) {
        if (!syncVisibleEditors()) return;
        query = searchField.getText();
        List<SettingSpec> filtered = draft.matching(category, query);
        int count = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page + direction, count - 1));
        message = "";
        reopen();
    }

    private boolean syncVisibleEditors() {
        return syncVisibleEditors(null);
    }

    private boolean syncVisibleEditors(String exceptKey) {
        for (SettingWidget entry : settingWidgets) {
            if (entry.spec().key().equals(exceptKey)) continue;
            if (!(entry.widget() instanceof TextFieldWidget field)) continue;
            try { draft.setValue(entry.spec().key(), entry.spec().parse(field.getText())); }
            catch (IllegalArgumentException invalid) {
                message = entry.spec().label() + " needs a valid value before you leave this page.";
                return false;
            }
        }
        return true;
    }

    private void refreshEnabledState() {
        for (SettingWidget entry : settingWidgets) {
            boolean enabled = draft.enabled(entry.spec().key());
            entry.widget().active = enabled;
            entry.reset().active = enabled;
        }
    }

    private void resetAll() {
        draft.resetAll();
        query = searchField.getText();
        page = 0;
        message = "";
        reopen();
    }

    private void save() {
        if (!syncVisibleEditors()) return;
        try {
            draft.saveTo(config::read, config::writeValue, config::save);
        } catch (IOException | RuntimeException failure) {
            String detail = failure.getMessage();
            message = detail == null || detail.isBlank()
                    ? "Settings could not be saved. Check the config folder and try again."
                    : "Settings could not be saved: " + detail;
            return;
        }
        message = "";
        savedCallback.run();
        close();
    }

    void returnFromPreferences() {
        query = searchField.getText();
        reopen();
    }

    private void reopen() {
        if (client != null) client.setScreen(new AutomationSettingsScreen(parent, config, draft,
                savedCallback, category, query, page, protection));
    }

    private static String displayValue(Object value) {
        return value instanceof Number number ? number.toString() : String.valueOf(value);
    }

    private String helpText(SettingSpec spec) {
        String help = spec.key().equals("navigationPreferences")
                ? "Add or remove profile key/value entries. The editor limits the list to 128 entries, keys to 64 characters, and values to 128 characters."
                : spec.help();
        if (spec.parentKey() == null) return help;
        String parentLabel = draft.specs().stream().filter(candidate -> candidate.key().equals(spec.parentKey()))
                .map(SettingSpec::label).findFirst().orElse(spec.parentKey());
        return help + " Requires " + parentLabel + " to be enabled.";
    }

    private record SettingWidget(SettingSpec spec, ClickableWidget widget, ButtonWidget reset) {}

    private final class PanelDrawable implements Drawable {
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            drawPanels(context);
            drawText(context);
        }
    }
}

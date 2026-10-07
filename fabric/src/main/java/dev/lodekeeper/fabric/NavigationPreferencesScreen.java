package dev.lodekeeper.fabric;

import dev.lodekeeper.core.NavigationPreferenceCatalog;
import dev.lodekeeper.core.SettingsDraft;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

final class NavigationPreferencesScreen extends Screen implements dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier {
    private static final int PANEL = 0xc918242b;
    private static final int CYAN = 0xff55dce8;
    private static final int PRIMARY = 0xffedf4f6;
    private static final int SECONDARY = 0xffa8c2df;
    private static final int GREEN = 0xffabf49b;
    private static final int ERROR = 0xffff8b8b;
    private static final int ROW_HEIGHT = 39;

    private final AutomationSettingsScreen parent;
    private final SettingsDraft draft;
    private String category;
    private String query;
    private String message = "";
    private int panelX;
    private int panelWidth;
    private int rowTop;
    private int pageSize;
    private int page;
    private List<NavigationPreferenceCatalog.Preference> results = List.of();
    private final List<PreferenceWidget> preferenceWidgets = new ArrayList<>();
    private TextFieldWidget searchField;

    NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft) {
        this(parent, draft, "All", "", 0, "");
    }

    private NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft,
                                        String category, String query, int page, String message) {
        super(Text.literal("Lodekeeper navigation preferences"));
        this.parent = parent;
        this.draft = draft;
        this.category = category;
        this.query = query;
        this.page = page;
        this.message = message;
    }

    @Override
    protected void init() {
        preferenceWidgets.clear();
        panelWidth = Math.max(1, Math.min(760, width - 24));
        panelX = (width - panelWidth) / 2;
        addDrawable(new PanelDrawable());
        int contentWidth = Math.max(1, panelWidth - 24);
        int categoryWidth = Math.min(154, Math.max(1, contentWidth / 3));
        int filterWidth = Math.min(54, Math.max(1, contentWidth / 5));
        int searchWidth = Math.max(1, contentWidth - categoryWidth - filterWidth - 12);
        int filterY = Math.max(42, Math.min(48, height / 5));
        int left = panelX + 12;

        add(button("Category: " + category, left, filterY, categoryWidth, 20,
                "Cycle through movement, safety, path planning, and cost options.", ignored -> {
                    if (!syncVisibleEditors()) return;
                    List<String> categories = NavigationPreferenceCatalog.categories();
                    category = categories.get((categories.indexOf(category) + 1) % categories.size());
                    query = searchField.getText();
                    page = 0;
                    reopen();
                }));
        searchField = new TextFieldWidget(textRenderer, left + categoryWidth + 6, filterY,
                searchWidth, 20, Text.literal("Search advanced options"));
        searchField.setMaxLength(64);
        searchField.setText(query);
        searchField.setPlaceholder(Text.literal("Search advanced options"));
        searchField.setTooltip(Tooltip.of(Text.literal("Search option names, categories, and help text.")));
        add(searchField);
        add(button("Filter", left + categoryWidth + searchWidth + 12, filterY, filterWidth, 20,
                "Apply the selected category and search text.", ignored -> applyFilters()));

        results = NavigationPreferenceCatalog.matching(category, query);
        rowTop = Math.max(filterY + 34, 80);
        int rowBottom = Math.max(rowTop + ROW_HEIGHT, height - 105);
        pageSize = Math.max(1, Math.min(8, (rowBottom - rowTop) / ROW_HEIGHT));
        int pageCount = Math.max(1, (results.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));

        int valueAreaWidth = Math.min(196, Math.max(136, contentWidth / 3));
        int valueX = panelX + panelWidth - 12 - valueAreaWidth;
        int resetWidth = Math.min(58, Math.max(48, valueAreaWidth / 4));
        int widgetWidth = Math.max(1, valueAreaWidth - resetWidth - 4);
        int start = page * pageSize;
        int end = Math.min(results.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            NavigationPreferenceCatalog.Preference preference = results.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT + 7;
            ClickableWidget widget = createPreferenceWidget(preference, valueX, y, widgetWidth);
            ButtonWidget reset = button("Reset",
                    valueX + widgetWidth + 4, y, resetWidth, 22,
                    "Remove this saved override and use the native default.", ignored -> reset(preference.key()));
            reset.active = draft.hasNavigationPreferenceOverride(preference.key());
            preferenceWidgets.add(new PreferenceWidget(preference, widget, reset));
            add(widget);
            add(reset);
        }

        int navY = height - 52;
        int navWidth = Math.min(64, Math.max(42, contentWidth / 5));
        ButtonWidget previous = button("Prev", width / 2 - navWidth - 48, navY, navWidth, 20,
                "Show the previous options page.", ignored -> changePage(-1));
        ButtonWidget next = button("Next", width / 2 + 48, navY, navWidth, 20,
                "Show the next options page.", ignored -> changePage(1));
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
        add(previous);
        add(next);
        add(button("Back to settings", width / 2 - 72, height - 23, 144, 20,
                "Return to settings. Save there to apply these overrides.", ignored -> close()));
        refreshEnabledState();
    }

    @Override
    public void close() {
        if (!syncVisibleEditors()) return;
        if (client != null) parent.returnFromPreferences();
    }

    private void drawPanels(DrawContext context) {
        context.fill(panelX - 2, 10, panelX + panelWidth + 2, Math.max(16, height - 2), PANEL);
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
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("ADVANCED NAVIGATION OPTIONS"),
                width / 2, 17, CYAN);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal(draft.navigationPreferences().size() + " custom of " + NavigationPreferenceCatalog.entries().size() + " options"),
                width / 2, 39, SECONDARY);
        int start = page * pageSize;
        int end = Math.min(results.size(), start + pageSize);
        int contentWidth = Math.max(1, panelWidth - 24);
        int valueX = panelX + panelWidth - 12 - Math.min(196, Math.max(136, contentWidth / 3));
        int labelWidth = Math.max(1, valueX - panelX - 30);
        for (int index = start; index < end; index++) {
            NavigationPreferenceCatalog.Preference preference = results.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT;
            context.drawTextWithShadow(textRenderer,
                    Text.literal(textRenderer.trimToWidth(preference.label(), labelWidth)),
                    panelX + 16, y + 4, isEnabled(preference) ? PRIMARY : SECONDARY);
            context.drawText(textRenderer,
                    Text.literal(textRenderer.trimToWidth(preference.help(), labelWidth)),
                    panelX + 16, y + 19, SECONDARY, false);
        }
        String pageLabel = results.isEmpty() ? "0 options" : (start + 1) + "–" + end + " of " + results.size();
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(pageLabel), width / 2, height - 36, SECONDARY);
        String state = statusText();
        int stateColor = message.isBlank() ? (draft.ignoredNavigationPreferenceCount() > 0 ? ERROR
                : draft.isDirty() ? GREEN : SECONDARY) : ERROR;
        context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(state, panelWidth - 24)),
                panelX + 12, height - 72, stateColor);
        if (results.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal("No options match."),
                width / 2, rowTop + 12, SECONDARY);
    }

    private String statusText() {
        if (!message.isBlank()) return message;
        if (draft.ignoredNavigationPreferenceCount() > 0)
            return "Ignored " + draft.ignoredNavigationPreferenceCount()
                    + " unsupported, invalid, or default saved entries. Save settings to remove them.";
        return draft.isDirty() ? "Unsaved changes · Save on the settings screen to apply" : "Native defaults are used unless an override is saved";
    }

    private ClickableWidget createPreferenceWidget(NavigationPreferenceCatalog.Preference preference,
                                                   int x, int y, int widgetWidth) {
        Object value = draft.navigationPreferenceValue(preference.key());
        String help = preference.label() + ". " + preference.help();
        if (preference.type() == NavigationPreferenceCatalog.Type.BOOLEAN) {
            return button(Boolean.TRUE.equals(value) ? "On" : "Off", x, y, widgetWidth, 22,
                    help, ignored -> {
                        if (!syncVisibleEditors()) return;
                        try {
                            draft.setNavigationPreferenceValue(preference.key(),
                                    !Boolean.TRUE.equals(draft.navigationPreferenceValue(preference.key())));
                            query = searchField.getText();
                            message = "";
                            reopen();
                        } catch (IllegalArgumentException invalid) { message = invalid.getMessage(); }
                    });
        }

        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, widgetWidth, 22,
                Text.literal(preference.label()));
        field.setMaxLength(16);
        field.setText(displayValue(value));
        field.setPlaceholder(Text.literal(preference.label()));
        field.setTooltip(Tooltip.of(Text.literal(help + " Default: " + preference.defaultValue()
                + ". Range: " + preference.minimum() + " to " + preference.maximum() + ".")));
        if (preference.type() == NavigationPreferenceCatalog.Type.INTEGER) field.setTextPredicate(text -> text.matches("\\d*"));
        if (preference.type() == NavigationPreferenceCatalog.Type.DECIMAL) field.setTextPredicate(text -> text.matches("\\d*(\\.\\d*)?"));
        field.setChangedListener(text -> {
            try {
                draft.setNavigationPreferenceValue(preference.key(), preference.parse(text));
                message = "";
            } catch (IllegalArgumentException invalid) { message = invalid.getMessage(); }
            refreshResetStates();
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

    private <T extends ClickableWidget> T add(T widget) { return addDrawableChild(widget); }

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
        int count = Math.max(1, (NavigationPreferenceCatalog.matching(category, query).size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page + direction, count - 1));
        message = "";
        reopen();
    }

    private boolean syncVisibleEditors() {
        for (PreferenceWidget entry : preferenceWidgets) {
            if (!(entry.widget() instanceof TextFieldWidget field)) continue;
            try {
                draft.setNavigationPreferenceValue(entry.preference().key(), entry.preference().parse(field.getText()));
            } catch (IllegalArgumentException invalid) {
                message = entry.preference().label() + " needs a value from " + entry.preference().minimum()
                        + " to " + entry.preference().maximum() + " before you leave this page.";
                return false;
            }
        }
        return true;
    }

    private void reset(String key) {
        if (!syncVisibleEditorsExcept(key)) return;
        if (draft.removeNavigationPreference(key)) message = "Override removed. The native default will be used.";
        query = searchField.getText();
        reopen();
    }

    private boolean syncVisibleEditorsExcept(String exceptKey) {
        for (PreferenceWidget entry : preferenceWidgets) {
            if (entry.preference().key().equals(exceptKey)) continue;
            if (!(entry.widget() instanceof TextFieldWidget field)) continue;
            try { draft.setNavigationPreferenceValue(entry.preference().key(), entry.preference().parse(field.getText())); }
            catch (IllegalArgumentException invalid) {
                message = entry.preference().label() + " needs a valid value before you leave this page.";
                return false;
            }
        }
        return true;
    }

    private void refreshEnabledState() {
        for (PreferenceWidget entry : preferenceWidgets)
            entry.widget().active = isEnabled(entry.preference());
    }

    private boolean isEnabled(NavigationPreferenceCatalog.Preference preference) {
        return preference.parentKey() == null || draft.enabled(preference.parentKey())
                && Boolean.TRUE.equals(draft.value(preference.parentKey()));
    }

    private void refreshResetStates() {
        for (PreferenceWidget entry : preferenceWidgets)
            entry.reset().active = draft.hasNavigationPreferenceOverride(entry.preference().key());
    }

    private void reopen() {
        if (client != null) client.setScreen(new NavigationPreferencesScreen(parent, draft,
                category, query, page, message));
    }

    private static String displayValue(Object value) {
        return value instanceof Number number ? number.toString() : String.valueOf(value);
    }

    private record PreferenceWidget(NavigationPreferenceCatalog.Preference preference,
                                    ClickableWidget widget, ButtonWidget reset) {}

    private final class PanelDrawable implements Drawable {
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            drawPanels(context);
            drawText(context);
        }
    }
}

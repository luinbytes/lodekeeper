package dev.lodekeeper.fabric;

import dev.lodekeeper.core.SettingsDraft;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class NavigationPreferencesScreen extends Screen {
    private static final int PANEL = 0xc918242b;
    private static final int CYAN = 0xff55dce8;
    private static final int PRIMARY = 0xffedf4f6;
    private static final int SECONDARY = 0xffa8c2df;
    private static final int GREEN = 0xffabf49b;
    private static final int ERROR = 0xffff8b8b;
    private static final int ROW_HEIGHT = 28;

    private final AutomationSettingsScreen parent;
    private final SettingsDraft draft;
    private String message = "";
    private int panelX;
    private int panelWidth;
    private int rowTop;
    private int pageSize;
    private int page;
    private List<Map.Entry<String, String>> entries = List.of();
    private TextFieldWidget keyField;
    private TextFieldWidget valueField;

    NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft) {
        this(parent, draft, 0, "");
    }

    private NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft, int page, String message) {
        super(Text.literal("Lodekeeper navigation preferences"));
        this.parent = parent;
        this.draft = draft;
        this.page = page;
        this.message = message;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(1, Math.min(700, width - 24));
        panelX = (width - panelWidth) / 2;
        int contentWidth = Math.max(1, panelWidth - 24);
        rowTop = Math.max(56, Math.min(72, height / 5));
        int rowBottom = Math.max(rowTop + ROW_HEIGHT, height - 132);
        pageSize = Math.max(1, Math.min(8, (rowBottom - rowTop) / ROW_HEIGHT));
        entries = draft.navigationPreferences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList();
        int pageCount = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));
        addDrawable(new PanelDrawable());

        int removeWidth = Math.min(68, Math.max(54, contentWidth / 7));
        int rowButtonWidth = Math.max(1, contentWidth - removeWidth - 8);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            Map.Entry<String, String> entry = entries.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT;
            add(button(entry.getKey() + " = " + entry.getValue(), panelX + 12, y,
                    rowButtonWidth, 21, "Select this key and value for editing.", ignored -> {
                        keyField.setText(entry.getKey());
                        valueField.setText(entry.getValue());
                        message = "Editing " + entry.getKey() + " in the draft.";
                    }));
            add(button("Remove", panelX + panelWidth - 12 - removeWidth, y,
                    removeWidth, 21, "Remove " + entry.getKey() + " from this draft.", ignored -> remove(entry.getKey())));
        }

        int editorY = height - 104;
        int addWidth = Math.min(118, Math.max(62, contentWidth / 5));
        int fieldsWidth = Math.max(2, contentWidth - addWidth - 8);
        int keyWidth = fieldsWidth / 2;
        int valueWidth = fieldsWidth - keyWidth;
        int editorX = panelX + 12;
        keyField = add(new TextFieldWidget(textRenderer, editorX, editorY, keyWidth, 21,
                Text.literal("Preference key")));
        keyField.setMaxLength(64);
        keyField.setPlaceholder(Text.literal("Preference key"));
        keyField.setTooltip(Tooltip.of(Text.literal("Key, up to 64 characters. Existing keys update their value.")));
        valueField = add(new TextFieldWidget(textRenderer, editorX + keyWidth + 4, editorY,
                valueWidth, 21, Text.literal("Preference value")));
        valueField.setMaxLength(128);
        valueField.setPlaceholder(Text.literal("Preference value"));
        valueField.setTooltip(Tooltip.of(Text.literal("Value, up to 128 characters.")));
        add(button("Add / update", editorX + keyWidth + valueWidth + 8, editorY, addWidth, 21,
                "Add a key, or update the selected key, in this unsaved draft.", ignored -> put()));

        int pageY = height - 61;
        int navWidth = Math.min(64, Math.max(42, contentWidth / 5));
        ButtonWidget previous = button("Prev", width / 2 - navWidth - 48, pageY, navWidth, 20,
                "Show the previous preference page.", ignored -> changePage(-1));
        ButtonWidget next = button("Next", width / 2 + 48, pageY, navWidth, 20,
                "Show the next preference page.", ignored -> changePage(1));
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
        add(previous);
        add(next);

        add(button("Back to settings", width / 2 - 72, height - 23, 144, 20,
                "Return to settings. Save there to apply this draft.", ignored -> close()));
    }

    @Override
    public void close() {
        if (client != null) parent.returnFromPreferences();
    }

    private void drawPanels(DrawContext context) {
        context.fill(panelX - 2, 10, panelX + panelWidth + 2, Math.max(16, height - 2), PANEL);
        context.fill(panelX - 2, 10, panelX + panelWidth + 2, 12, CYAN);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            int y = rowTop + (index - start) * ROW_HEIGHT;
            context.fill(panelX + 8, y, panelX + panelWidth - 8, y + ROW_HEIGHT - 1,
                    (index - start) % 2 == 0 ? 0x70344852 : 0x5040525d);
            context.fill(panelX + 8, y, panelX + 10, y + ROW_HEIGHT - 1, CYAN);
        }
    }

    private void drawText(DrawContext context) {
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("ADVANCED NAVIGATION PREFERENCES"),
                width / 2, 17, CYAN);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(entries.size() + " of 128 entries"),
                width / 2, 39, SECONDARY);
        String status = message.isBlank()
                ? "Profile data only; this editor checks entry count and key/value lengths. Save on settings to apply."
                : message;
        int color = message.isBlank() ? (draft.isDirty() ? GREEN : SECONDARY) : ERROR;
        context.drawTextWithShadow(textRenderer, Text.literal(textRenderer.trimToWidth(status, panelWidth - 24)),
                panelX + 12, height - 82, color);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.literal(entries.isEmpty() ? "No preferences saved." : (start + 1) + "–" + end + " of " + entries.size()),
                width / 2, height - 38, SECONDARY);
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

    private void put() {
        try {
            draft.putNavigationPreference(keyField.getText(), valueField.getText());
            message = "Preference added to the draft. Save on the settings screen to apply it.";
            page = Math.min(page, Math.max(0, (draft.navigationPreferences().size() - 1) / pageSize));
            reopen();
        } catch (IllegalArgumentException invalid) {
            message = invalid.getMessage();
        }
    }

    private void remove(String key) {
        if (draft.removeNavigationPreference(key)) message = "Preference removed from the draft.";
        page = Math.min(page, Math.max(0, (draft.navigationPreferences().size() - 1) / pageSize));
        reopen();
    }

    private void changePage(int direction) {
        int count = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page + direction, count - 1));
        reopen();
    }

    private void reopen() {
        if (client != null) client.setScreen(new NavigationPreferencesScreen(parent, draft, page, message));
    }

    private final class PanelDrawable implements Drawable {
        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            drawPanels(context);
            drawText(context);
        }
    }
}

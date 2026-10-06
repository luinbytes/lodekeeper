package dev.lodekeeper.fabric.modern;

import dev.lodekeeper.core.SettingsDraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class NavigationPreferencesScreen extends Screen implements dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier {
    private static final int PANEL = 0xc918242b;
    private static final int CYAN = 0xff55dce8;
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
    private EditBox keyField;
    private EditBox valueField;

    NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft) {
        this(parent, draft, 0, "");
    }

    private NavigationPreferencesScreen(AutomationSettingsScreen parent, SettingsDraft draft, int page, String message) {
        super(Component.literal("Lodekeeper navigation preferences"));
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

        int removeWidth = Math.min(68, Math.max(54, contentWidth / 7));
        int rowButtonWidth = Math.max(1, contentWidth - removeWidth - 8);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            Map.Entry<String, String> entry = entries.get(index);
            int y = rowTop + (index - start) * ROW_HEIGHT;
            add(button(entry.getKey() + " = " + entry.getValue(), panelX + 12, y,
                    rowButtonWidth, 21, "Select this key and value for editing.", ignored -> {
                        keyField.setValue(entry.getKey());
                        valueField.setValue(entry.getValue());
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
        keyField = add(new EditBox(font, editorX, editorY, keyWidth, 21, Component.literal("Preference key")));
        keyField.setMaxLength(64);
        keyField.setHint(Component.literal("Preference key"));
        keyField.setTooltip(Tooltip.create(Component.literal("Key, up to 64 characters. Existing keys update their value.")));
        valueField = add(new EditBox(font, editorX + keyWidth + 4, editorY,
                valueWidth, 21, Component.literal("Preference value")));
        valueField.setMaxLength(128);
        valueField.setHint(Component.literal("Preference value"));
        valueField.setTooltip(Tooltip.create(Component.literal("Value, up to 128 characters.")));
        add(button("Add / update", editorX + keyWidth + valueWidth + 8, editorY, addWidth, 21,
                "Add a key, or update the selected key, in this unsaved draft.", ignored -> put()));

        int pageY = height - 61;
        int navWidth = Math.min(64, Math.max(42, contentWidth / 5));
        Button previous = button("Prev", width / 2 - navWidth - 48, pageY, navWidth, 20,
                "Show the previous preference page.", ignored -> changePage(-1));
        Button next = button("Next", width / 2 + 48, pageY, navWidth, 20,
                "Show the next preference page.", ignored -> changePage(1));
        previous.active = page > 0;
        next.active = page + 1 < pageCount;
        add(previous);
        add(next);

        add(button("Back to settings", width / 2 - 72, height - 23, 144, 20,
                "Return to settings. Save there to apply this draft.", ignored -> onClose()));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        drawPanels(graphics);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        drawText(graphics);
    }

    @Override
    public void onClose() {
        parent.returnFromPreferences();
    }

    private void drawPanels(GuiGraphicsExtractor graphics) {
        graphics.fill(panelX - 2, 10, panelX + panelWidth + 2, Math.max(16, height - 2), PANEL);
        graphics.fill(panelX - 2, 10, panelX + panelWidth + 2, 12, CYAN);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            int y = rowTop + (index - start) * ROW_HEIGHT;
            graphics.fill(panelX + 8, y, panelX + panelWidth - 8, y + ROW_HEIGHT - 1,
                    (index - start) % 2 == 0 ? 0x70344852 : 0x5040525d);
            graphics.fill(panelX + 8, y, panelX + 10, y + ROW_HEIGHT - 1, CYAN);
        }
    }

    private void drawText(GuiGraphicsExtractor graphics) {
        graphics.centeredText(font, "ADVANCED NAVIGATION PREFERENCES", width / 2, 17, CYAN);
        graphics.centeredText(font, entries.size() + " of 128 entries", width / 2, 39, SECONDARY);
        String status = message.isBlank()
                ? "Profile data only; this editor checks entry count and key/value lengths. Save on settings to apply."
                : message;
        int color = message.isBlank() ? (draft.isDirty() ? GREEN : SECONDARY) : ERROR;
        graphics.text(font, font.plainSubstrByWidth(status, panelWidth - 24), panelX + 12, height - 82, color);
        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        graphics.centeredText(font,
                entries.isEmpty() ? "No preferences saved." : (start + 1) + "–" + end + " of " + entries.size(),
                width / 2, height - 38, SECONDARY);
    }

    private Button button(String label, int x, int y, int buttonWidth, int buttonHeight,
                          String help, Button.OnPress action) {
        return Button.builder(Component.literal(label), action)
                .bounds(x, y, buttonWidth, buttonHeight)
                .tooltip(Tooltip.create(Component.literal(help)))
                .build();
    }

    private <T extends AbstractWidget> T add(T widget) { return addRenderableWidget(widget); }

    private void put() {
        try {
            draft.putNavigationPreference(keyField.getValue(), valueField.getValue());
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
        GameApi.setScreen(minecraft, new NavigationPreferencesScreen(parent, draft, page, message));
    }
}

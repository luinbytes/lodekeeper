package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ClaimBox;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

final class ClaimsScreen extends Screen implements dev.lodekeeper.navigation.kernel.api.AutomationInputBarrier {
    private final Screen parent;
    private final WorldProtection protection;
    private final String[][] coordinates = {{"", "", ""}, {"", "", ""}};
    private final dev.lodekeeper.core.WorldScope scope;
    private TextFieldWidget nameField;
    private String name = "";
    private String message = "";
    private String pendingRemoval;
    private boolean preferred;
    private boolean savedPlots;
    private int editorNameY, cornerY, listTop;
    private int page, pageSize, left, panelWidth;
    private List<ClaimBox> rows = List.of();

    ClaimsScreen(Screen parent, WorldProtection protection) {
        super(Text.literal("Lodekeeper protected plots"));
        this.parent = parent;
        this.protection = protection;
        scope = protection.capture().scope();
        for (int row = 0; row < 2; row++) readCorner(row);
    }

    @Override protected void init() {
        rememberName();
        nameField = null;
        editorNameY = height < 180 ? 54 : 62;
        cornerY = height < 180 ? 78 : 88;
        listTop = 60;
        panelWidth = Math.max(1, Math.min(740, width - 24));
        left = (width - panelWidth) / 2 + 12;
        int content = Math.max(1, panelWidth - 24);
        var snapshot = protection.capture();
        rows = snapshot.scope() == null ? List.of() : snapshot.claims().forScope(snapshot.scope());
        pageSize = Math.max(1, Math.min(8, (height - 106) / 38));
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pages - 1));
        addDrawable(new Panel());
        var editTab = addButton("New plot", left, 34, (content - 6) / 2,
                "Select both corners and name a protected plot.", ignored -> { savedPlots = false; reload(); });
        editTab.active = savedPlots;
        var savedTab = addButton("Saved plots (" + rows.size() + ")", left + (content - 6) / 2 + 6, 34,
                content - (content - 6) / 2 - 6, "View, prefer, or remove saved plots.",
                ignored -> { rememberName(); savedPlots = true; reload(); });
        savedTab.active = !savedPlots;
        if (!savedPlots) {
            int nameWidth = Math.max(1, content - 132);
            nameField = addDrawableChild(new TextFieldWidget(textRenderer, left, editorNameY, nameWidth, 20,
                    Text.literal("Plot name")));
            nameField.setMaxLength(ClaimBox.MAX_NAME_LENGTH);
            nameField.setChangedListener(value -> name = value);
            nameField.setPlaceholder(Text.literal("Plot name"));
            nameField.setText(name);
            addButton("Preferred: " + (preferred ? "On" : "Off"), left + nameWidth + 6, editorNameY, 126,
                    "Prefer usable crafting tables and furnaces within this plot.", ignored -> {
                        rememberName();
                        preferred = !preferred;
                        reload();
                    });
            int coordinateWidth = Math.max(1, (content - 128) / 3);
            for (int corner = 0; corner < 2; corner++) {
                int row = corner;
    
                for (int axis = 0; axis < 3; axis++) {
                    var field = new TextFieldWidget(textRenderer, left + 36 + axis * (coordinateWidth + 4),
                            cornerY + corner * (height < 180 ? 22 : 24), coordinateWidth, 20,
                            Text.literal("Corner " + (corner + 1) + " " + "XYZ".charAt(axis)));
                    field.setMaxLength(11);
                    field.setTextPredicate(value -> value.matches("-?\\d*"));
                    field.setPlaceholder(Text.literal(String.valueOf("XYZ".charAt(axis))));
                    int column = axis;
                    field.setChangedListener(value -> coordinates[row][column] = value);
                    field.setText(coordinates[row][axis]);
                    addDrawableChild(field);
                }
                addButton("Use aim " + (corner + 1), left + content - 76, cornerY + corner * (height < 180 ? 22 : 24), 76,
                        "Use the block under your crosshair, or your feet when aiming at air.", ignored -> {
                            rememberName();
                            message = row == 0 ? protection.selectPos1() : protection.selectPos2();
                            readCorner(row);
                            reload();
                        });
            }
            addButton("Add plot", left, height - 24, 78,
                    "Save both corners as an inclusive protected box. Changes save immediately.", ignored -> addClaim());
            addButton("Clear corners", left + 84, height - 24, 96,
                    "Clear the selection. Saved plots stay protected.", ignored -> {
                        rememberName(); message = protection.clearSelection();
                        readCorner(0); readCorner(1); reload();
                    });
        } else {
            int start = page * pageSize;
            for (int i = start; i < Math.min(rows.size(), start + pageSize); i++) {
                ClaimBox claim = rows.get(i);
                int y = listTop + (i - start) * 38;
                addButton(claim.preferredStations() ? "Preferred" : "Prefer", left + content - 160, y + 6, 82,
                        "Toggle preferred station use. The plot remains protected either way.", ignored -> {
                            rememberName();
                            message = protection.setPreferred(claim.id(), !claim.preferredStations());
                            reload();
                        });
                addButton(claim.id().equals(pendingRemoval) ? "Confirm" : "Remove", left + content - 72, y + 6, 72,
                        "Click twice to remove only this plot from the current dimension.", ignored -> {
                            rememberName();
                            if (claim.id().equals(pendingRemoval)) {
                                message = protection.removeClaim(claim.id());
                                pendingRemoval = null;
                            } else {
                                pendingRemoval = claim.id();
                                message = "Click Confirm to remove \"" + claim.name() + "\".";
                            }
                            reload();
                        });
            }
            var previous = addButton("Prev", left, height - 24, 54, "Previous plot page.", ignored -> {
                rememberName(); page--; reload();
            });
            previous.active = page > 0;
            var next = addButton("Next", left + 60, height - 24, 54, "Next plot page.", ignored -> {
                rememberName(); page++; reload();
            });
            next.active = page + 1 < pages;
        }
        addButton("Back", left + content - 72, height - 24, 72, "Return to settings.", ignored -> close());
        if (snapshot.locked()) message = "Plot data unavailable. Automated block edits are paused.";
    }

    private void rememberName() {
        if (nameField != null) name = nameField.getText();
    }

    private void addClaim() {
        rememberName();
        try {
            int[][] parsed = new int[2][3];
            for (int row = 0; row < 2; row++) for (int axis = 0; axis < 3; axis++)
                parsed[row][axis] = Integer.parseInt(coordinates[row][axis]);
            for (int row = 0; row < 2; row++)
                protection.setCorner(row == 0, parsed[row][0], parsed[row][1], parsed[row][2]);
            int previousCount = protection.capture().claims().forScope(scope).size();
            message = protection.createClaim(name, preferred);
            if (protection.capture().claims().forScope(scope).size() > previousCount) savedPlots = true;
            reload();
        } catch (NumberFormatException invalid) {
            message = "Enter whole X, Y, and Z coordinates for both corners.";
        }
    }

    private ButtonWidget addButton(String label, int x, int y, int buttonWidth, String help,
                                   ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
                    if (!java.util.Objects.equals(scope, protection.capture().scope())) { close(); return; }
                    action.onPress(button);
                })
                .dimensions(x, y, buttonWidth, 20).tooltip(Tooltip.of(Text.literal(help))).build());
    }

    private void readCorner(int row) {
        int[] selected = protection.selectionCoordinates(row == 0);
        for (int axis = 0; axis < 3; axis++)
            coordinates[row][axis] = selected == null ? "" : Integer.toString(selected[axis]);
    }

    private void reload() { clearChildren(); init(); }

    @Override public void tick() {
        if (!java.util.Objects.equals(scope, protection.capture().scope())) close();
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    private final class Panel implements Drawable {
        @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(left - 14, 10, left + panelWidth - 10, height - 2, 0xc918242b);
            context.fill(left - 14, 10, left + panelWidth - 10, 12, 0xff55dce8);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("LODEKEEPER · PROTECTED PLOTS"),
                    width / 2, 18, 0xff55dce8);
            if (!savedPlots) {
            for (int row = 0; row < 2; row++)
                context.drawTextWithShadow(textRenderer, "Pos " + (row + 1), left, cornerY + 6 + row * (height < 180 ? 22 : 24), 0xffa8c2df);
            } else {
            int start = page * pageSize;
            for (int i = start; i < Math.min(rows.size(), start + pageSize); i++) {
                ClaimBox row = rows.get(i);
                int y = listTop + (i - start) * 38;
                int textWidth = Math.max(1, panelWidth - 196);
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(row.name(), textWidth),
                        left, y + 4, 0xffedf4f6);
                String bounds = row.minX() + "," + row.minY() + "," + row.minZ()
                        + " to " + row.maxX() + "," + row.maxY() + "," + row.maxZ();
                context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(bounds, textWidth),
                        left, y + 19, 0xffa8c2df);
            }
            if (rows.isEmpty()) context.drawTextWithShadow(textRenderer, "No plots in this dimension.", left, listTop + 4, 0xffa8c2df);
            }
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(message, panelWidth - 24),
                    left, height - 40, 0xffabf49b);
        }
    }
}

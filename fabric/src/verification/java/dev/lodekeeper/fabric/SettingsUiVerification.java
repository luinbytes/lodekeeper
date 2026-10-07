package dev.lodekeeper.fabric;

import dev.lodekeeper.core.WorldScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native child-widget QA driver for the Lodekeeper settings screens. */
public final class SettingsUiVerification {
    public record Bounds(int x, int y, int width, int height) {
        public int centerX() { return x + width / 2; }
        public int centerY() { return y + height / 2; }
        public String toString() { return x + "," + y + "," + width + "x" + height; }
    }

    public record Widget(Object nativeWidget, String message, String text, Bounds bounds,
                         boolean textField, boolean active) {}

    public record Setting(String key, String label, String category, String help, String type, Object defaultValue,
                          String parentKey) {}

    public record Plot(String id, String name, WorldScope scope, int minX, int minY, int minZ,
                       int maxX, int maxY, int maxZ, boolean preferredStations) {}

    public record Corner(int x, int y, int z) {}

    public record PlotState(boolean liveLocked, boolean diskLocked, WorldScope scope,
                            List<Plot> liveClaims, List<Plot> diskClaims, Corner first, Corner second) {
        public PlotState {
            liveClaims = List.copyOf(liveClaims);
            diskClaims = List.copyOf(diskClaims);
        }
    }

    public interface Access {
        List<Widget> visibleChildren(Object screen);
        Object currentScreen();
        void openSettingsScreen();
        boolean isClaimsScreen(Object screen);
        void resizeCurrentScreen(int width, int height);
        String nativeEventReceipt();
        int screenWidth();
        int screenHeight();
        int focusedTextLength();
        void dispatchMouseClick(double x, double y);
        void dispatchKey(int keyCode, int scanCode, int modifiers);
        void dispatchCharacter(int codePoint);
        Object readConfig(String key);
        void writeConfig(String key, Object value) throws Exception;
        void saveConfig() throws Exception;
        void reloadConfig() throws Exception;
        List<Setting> settings();
        Object defaultValue(String key);
        boolean enabled(String key);
        Map<String, String> navigationPreferences();
        PlotState plotState();
        void removePlotClaim(String id);
        void clearPlotSelection();
        void requestCapture(String label);
        void log(String line);
        boolean engineIdle();
    }

    private enum State { READY, RUNNING, RESTORING, COMPLETE, FAILED }

    private interface Action { void run() throws Exception; }

    private interface Check { boolean run() throws Exception; }

    private static final String QA_PLOT_NAME = "zz-lodekeeper-uiqa-plot";

    private final Access access;
    private final Map<String, Object> original = new LinkedHashMap<>();
    private final List<String> steps = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> trace = new ArrayList<>();
    private State state = State.READY;
    private int nextStep;
    private int heldFrames;
    private int captureHoldTicks;
    private boolean initialCaptureComplete;
    private String delayedCaptureLabel;
    private int captureRequests;
    private boolean restored;
    private String activeStep = "begin";
    private Map<String, String> originalNavigation = Map.of();
    private Map<String, String> navigationBaseline = Map.of();
    private boolean plotProbeStarted;
    private boolean plotCleanupComplete;
    private int plotPhase;
    private int plotWidth;
    private int plotHeight;
    private Object plotSettingsParent;
    private String plotReceipt = "not-started";

    private SettingsUiVerification(Access access) {
        this.access = Objects.requireNonNull(access);
    }

    public static SettingsUiVerification begin(Access access) {
        SettingsUiVerification run = new SettingsUiVerification(access);
        run.beginRun();
        return run;
    }

    private void beginRun() {
        try {
            if (!access.engineIdle()) throw new IllegalStateException("engine is active; UI QA is refused");
            PlotState plots = access.plotState();
            require(!plots.liveLocked() && !plots.diskLocked(), "plot data is locked; UI QA is refused");
            require(plots.scope() != null, "world identity is unavailable; plot UI QA is refused");
            require(plots.liveClaims().isEmpty() && plots.diskClaims().isEmpty(),
                    "plot UI QA requires an empty isolated claims store");
            access.clearPlotSelection();
            PlotState cleared = access.plotState();
            require(cleared.first() == null && cleared.second() == null,
                    "plot selection could not be cleared before UI QA");
            plotReceipt = "preflight live=0 disk=0 corners=clear";
            plotProbeStarted = true;
            List<Setting> specs = access.settings();
            if (specs.size() != 48) throw new IllegalStateException("expected 48 settings, found " + specs.size());
            for (Setting setting : specs) original.put(setting.key(), copy(access.readConfig(setting.key())));
            if (original.size() != 48) throw new IllegalStateException("settings keys are not unique");
            originalNavigation = Map.copyOf(access.navigationPreferences());
            access.openSettingsScreen();
            if (access.currentScreen() == null) throw new IllegalStateException("settings screen did not open");
            state = State.RUNNING;
            plan();
            log("settings UI probe started with 48 config snapshots; native events=" + access.nativeEventReceipt());
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void plan() {
        add("allowBreaking-search-filter-off-save-reload", () -> {
            access.writeConfig("allowBreaking", Boolean.TRUE);
            access.saveConfig();
            access.reloadConfig();
            access.openSettingsScreen();
            search("allowBreaking");
            clickMessage("Filter");
            clickSetting("allowBreaking");
            clickMessage("Save");
            access.reloadConfig();
            require(Boolean.FALSE.equals(access.readConfig("allowBreaking")),
                    "allowBreaking Off did not survive config reload");
        });
        add("discard-preserves-saved-value", () -> {
            access.openSettingsScreen();
            search("allowBreaking");
            clickMessage("Filter");
            if (!currentBoolean("allowBreaking")) clickMessage("Off");
            clickMessage("Discard");
            access.reloadConfig();
            require(Boolean.FALSE.equals(access.readConfig("allowBreaking")),
                    "discard changed the saved allowBreaking value");
        });
        add("dependency-disables-child-keeps-stored-value", () -> {
            access.writeConfig("allowParkour", Boolean.TRUE);
            access.writeConfig("allowParkourPlace", Boolean.TRUE);
            access.saveConfig();
            access.reloadConfig();
            require(Boolean.TRUE.equals(access.readConfig("allowParkourPlace")),
                    "could not prepare stored child value for dependency check");
            access.openSettingsScreen();
            search("allowParkour");
            clickMessage("Filter");
            clickSetting("allowParkour");
            search("allowParkourPlace");
            clickMessage("Filter");
            Widget child = settingWidget("allowParkourPlace");
            require(!child.active(), "allowParkourPlace remained enabled when allowParkour was off");
            require("On".equals(child.message()), "disabled child did not retain its stored On value");
            require(Boolean.TRUE.equals(access.readConfig("allowParkourPlace")),
                    "disabling the parent changed the stored child value");
            clickMessage("Discard");
            access.reloadConfig();
        });
        add("invalid-number-refuses-save", () -> {
            Setting numeric = access.settings().stream()
                    .filter(s -> "DECIMAL".equals(s.type()))
                    .filter(s -> s.parentKey() == null || access.enabled(s.parentKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no enabled numeric setting"));
            String key = numeric.key();
            Object before = copy(access.readConfig(key));
            access.openSettingsScreen();
            search(key);
            clickMessage("Filter");
            Widget field = settingWidget(key);
            click(field);
            replaceFocusedText(".");
            clickMessage("Save");
            require(Objects.equals(before, access.readConfig(key)),
                    "invalid numeric text changed saved config for " + key);
            require(access.currentScreen() != null, "invalid numeric save closed the settings screen");
            access.log("invalid numeric entry used native key and character events; field=" + key
                    + " bounds=" + field.bounds());
            clickMessage("Discard");
        });
        add("per-row-reset", () -> {
            Setting candidate = access.settings().stream()
                    .filter(s -> "BOOLEAN".equals(s.type()) && Objects.equals(access.readConfig(s.key()), s.defaultValue()))
                    .filter(s -> access.enabled(s.key()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no non-default boolean available for row reset"));
            access.openSettingsScreen();
            search(candidate.key());
            clickMessage("Filter");
            clickSetting(candidate.key());
            clickMessage("Reset");
            require(Objects.equals(candidate.defaultValue(), displayedBoolean(candidate.key())),
                    "per-row Reset did not restore the draft default for " + candidate.key());
            clickMessage("Discard");
            access.reloadConfig();
        });
        add("navigation-options-open-and-capture", () -> {
            if (!access.engineIdle()) throw new IllegalStateException("engine became active before navigation editing");
            navigationBaseline = new LinkedHashMap<>(access.navigationPreferences());
            navigationBaseline.remove("jumpPenalty");
            navigationBaseline.remove("cutoffAtLoadBoundary");
            access.writeConfig("navigationPreferences", navigationBaseline);
            access.saveConfig();
            access.reloadConfig();
            openNavigationOptions();
            captureHoldTicks = 2;
            delayedCaptureLabel = "navigation-options";
        });
        add("navigation-options-edit-validate-save-reload-reset", () -> {
            typeField("Search advanced options", "jumpPenalty");
            clickMessage("Filter");
            typeField("Jump cost", "3.5");
            clickMessage("Back to settings");
            clickMessage("Save");
            access.reloadConfig();
            require("3.5".equals(access.navigationPreferences().get("jumpPenalty")),
                    "named navigation option did not survive config reload");

            openNavigationOptions();
            typeField("Search advanced options", "jumpPenalty");
            clickMessage("Filter");
            Object editor = access.currentScreen();
            typeField("Jump cost", ".");
            clickMessage("Back to settings");
            require(access.currentScreen() == editor, "invalid navigation number allowed leaving the editor");
            require("3.5".equals(access.navigationPreferences().get("jumpPenalty")),
                    "invalid navigation number changed saved config");
            typeField("Jump cost", "3.5");
            clickMessage("Reset");
            typeField("Search advanced options", "cutoffAtLoadBoundary");
            clickMessage("Filter");
            clickMessage("Off");
            clickMessage("Back to settings");
            clickMessage("Save");
            access.reloadConfig();
            require("true".equals(access.navigationPreferences().get("cutoffAtLoadBoundary")),
                    "navigation toggle did not survive config reload");
            require(!access.navigationPreferences().containsKey("jumpPenalty"),
                    "navigation row Reset did not remove its override");

            openNavigationOptions();
            typeField("Search advanced options", "cutoffAtLoadBoundary");
            clickMessage("Filter");
            clickMessage("Reset");
            typeField("Search advanced options", "allowDownward");
            clickMessage("Filter");
            List<Widget> toggles = widgets().stream()
                    .filter(widget -> "On".equals(widget.message()) || "Off".equals(widget.message())).toList();
            require(toggles.size() == 1, "filtered navigation option did not expose exactly one toggle");
            Widget child = toggles.get(0);
            require(!child.active(), "navigation child remained enabled with block breaking disabled");
            String expected = Boolean.parseBoolean(navigationBaseline.getOrDefault("allowDownward", "true")) ? "On" : "Off";
            require(expected.equals(child.message()), "disabled navigation child lost its stored value");
            clickMessage("Back to settings");
            clickMessage("Save");
            access.reloadConfig();
            require(access.navigationPreferences().equals(navigationBaseline),
                    "navigation options differ from their pre-probe values after Reset");
        });
        addInteractive("protected-plots-native-form-resize-persist-prefer-remove", this::runPlotProbe);
        add("all-original-values-restored", () -> {
            require(original.size() == 48, "snapshot does not cover every setting");
            restore();
            access.reloadConfig();
            for (Map.Entry<String, Object> entry : original.entrySet())
                require(Objects.equals(entry.getValue(), access.readConfig(entry.getKey())),
                        "restored config mismatch for " + entry.getKey());
            require(access.navigationPreferences().equals(originalNavigation), "navigation map was not restored");
            access.openSettingsScreen();
        });
    }

    private void add(String name, Action action) {
        steps.add(name);
        checks.add(() -> {
            action.run();
            return true;
        });
    }

    private void addInteractive(String name, Check action) {
        steps.add(name);
        checks.add(action);
    }

    public void tick() {
        if (state != State.RUNNING) return;
        if (!access.engineIdle()) {
            fail(new IllegalStateException("engine became active during settings UI QA"));
            return;
        }
        if (!initialCaptureComplete) {
            if (access.currentScreen() == null) {
                fail(new IllegalStateException("settings screen closed before initial capture"));
                return;
            }
            if (++heldFrames >= 2) {
                capture("settings-open");
                initialCaptureComplete = true;
                captureHoldTicks = 2;
            }
            return;
        }
        if (captureHoldTicks > 0) {
            if (--captureHoldTicks == 0 && delayedCaptureLabel != null) {
                capture(delayedCaptureLabel);
                delayedCaptureLabel = null;
                captureHoldTicks = 2;
            }
            return;
        }
        if (nextStep >= checks.size()) {
            state = failures.isEmpty() ? State.COMPLETE : State.FAILED;
            return;
        }
        activeStep = steps.get(nextStep);
        try {
            log("step=" + activeStep + " screen=" + screenName() + " size=" + access.screenWidth() + "x" + access.screenHeight());
            if (access.currentScreen() != null) logVisibleBounds();
            if (checks.get(nextStep).run()) {
                trace.add("PASS " + activeStep);
                nextStep++;
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    public boolean completed() { return state == State.COMPLETE || state == State.FAILED; }
    public boolean passed() { return state == State.COMPLETE && failures.isEmpty(); }
    public boolean captureRequested() { return captureRequests > 0; }
    public boolean restorationComplete() { return restored; }

    public String receipt() {
        return "settingsUi=" + (passed() ? "PASS" : state == State.FAILED ? "FAIL" : "RUNNING")
                + "; steps=" + nextStep + "/" + steps.size()
                + "; restored=" + restored
                + "; plots=" + plotReceipt
                + "; framesHeld=" + heldFrames
                + "; captures=" + captureRequests
                + "; trace=" + String.join(" | ", trace)
                + (failures.isEmpty() ? "" : "; failures=" + String.join(" | ", failures));
    }

    public void restore() {
        List<String> restoreErrors = new ArrayList<>();
        if (!plotCleanupComplete) {
            try {
                restorePlotState();
            } catch (Throwable failure) {
                restoreErrors.add("plots: " + failure);
            }
        }
        if (!restored && !original.isEmpty()) {
            for (Map.Entry<String, Object> entry : original.entrySet()) {
                try { access.writeConfig(entry.getKey(), copy(entry.getValue())); }
                catch (Throwable failure) { restoreErrors.add(entry.getKey() + ": " + failure); }
            }
            try { access.saveConfig(); }
            catch (Throwable failure) { restoreErrors.add("save: " + failure); }
            try { access.reloadConfig(); }
            catch (Throwable failure) { restoreErrors.add("reload: " + failure); }
        }
        restored = restoreErrors.isEmpty();
        if (!restored) failures.add("restore failed: " + String.join(", ", restoreErrors));
        log("settings UI restoration " + (restored ? "complete" : "failed: " + String.join(", ", restoreErrors)));
    }

    private void restorePlotState() {
        if (!plotProbeStarted && !access.engineIdle()) {
            plotCleanupComplete = true;
            plotReceipt = "not-started; engine active; no plot state changed";
            return;
        }
        PlotState before = access.plotState();
        if (plotProbeStarted && !before.liveLocked() && !before.diskLocked()) {
            for (Plot claim : before.diskClaims()) {
                if (QA_PLOT_NAME.equals(claim.name())) access.removePlotClaim(claim.id());
            }
        }
        if (plotProbeStarted || access.engineIdle()) access.clearPlotSelection();
        PlotState after = access.plotState();
        if (plotProbeStarted) {
            require(!after.liveLocked() && !after.diskLocked(), "claims are locked after plot cleanup");
            require(after.liveClaims().isEmpty() && after.diskClaims().isEmpty(),
                    "plot cleanup left claims in the live snapshot or isolated store");
        }
        require(after.first() == null && after.second() == null, "plot cleanup left a selected corner");
        plotReceipt = plotProbeStarted ? "cleanup live=0 disk=0 corners=clear"
                : "not-started; preexisting claims preserved; corners=clear";
        plotCleanupComplete = true;
    }

    private void fail(Throwable failure) {
        failures.add(activeStep + ": " + failure);
        trace.add("FAIL " + activeStep + " " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        state = State.RESTORING;
        restore();
        state = State.FAILED;
        log(receipt());
    }

    private void capture(String label) {
        access.requestCapture(label);
        captureRequests++;
    }

    private boolean runPlotProbe() throws Exception {
        if (plotPhase == 0) {
            if (!access.engineIdle()) throw new IllegalStateException("engine became active before plot UI probe");
            PlotState before = access.plotState();
            require(!before.liveLocked() && !before.diskLocked(), "plot data became locked before UI probe");
            require(before.liveClaims().isEmpty() && before.diskClaims().isEmpty(),
                    "plot data is not empty before UI probe");
            require(before.first() == null && before.second() == null, "plot selection is not clear before UI probe");
            access.openSettingsScreen();
            search("allowBreaking");
            clickMessage("Filter");
            clickSetting("allowBreaking");
            require(Boolean.TRUE.equals(displayedBoolean("allowBreaking")),
                    "settings draft could not be prepared before opening protected plots");
            plotSettingsParent = access.currentScreen();
            clickMessage("Protected plots");
            require(access.isClaimsScreen(access.currentScreen()),
                    "Protected plots did not open the native claims screen");
            if (find("New plot", false).active()) clickMessage("New plot");
            plotWidth = access.screenWidth();
            plotHeight = access.screenHeight();
            require(plotWidth > 280 && plotHeight > 160, "native plot screen is too small for resize verification");
            captureHoldTicks = 2;
            delayedCaptureLabel = "actual-plot-editor";
            plotPhase = 1;
            return false;
        }
        if (plotPhase == 1) {
            require(access.isClaimsScreen(access.currentScreen()), "native plot screen closed before form verification");
            typeField("Plot name", QA_PLOT_NAME);
            typeField("Corner 1 X", "-12");
            typeField("Corner 1 Y", "64");
            typeField("Corner 1 Z", "6");
            typeField("Corner 2 X", "-9");
            typeField("Corner 2 Y", "66");
            typeField("Corner 2 Z", "2");
            int resizedWidth = plotWidth - 20;
            int resizedHeight = plotHeight - 20;
            access.resizeCurrentScreen(resizedWidth, resizedHeight);
            requirePlotFields();
            requirePlotFieldBounds(resizedWidth, resizedHeight);
            access.resizeCurrentScreen(plotWidth, plotHeight);
            requirePlotFields();
            require(access.isClaimsScreen(access.currentScreen()), "native plot screen changed during resize verification");
            clickMessage("Add plot");
            Plot claim = requireSingleTestPlot(access.plotState(), false);
            plotReceipt = "created live=1 disk=1; inclusive bounds=-12,64,2..-9,66,6";
            log("native plot input persisted through screen resize and isolated ClaimStore reload; id=" + claim.id()
                    + " bounds=" + claim.minX() + "," + claim.minY() + "," + claim.minZ() + ".."
                    + claim.maxX() + "," + claim.maxY() + "," + claim.maxZ());
            plotPhase = 2;
            return false;
        }
        if (plotPhase == 2) {
            Plot claim = requireSingleTestPlot(access.plotState(), false);
            clickMessage("New plot");
            clickMessage("Clear corners");
            PlotState cleared = access.plotState();
            require(cleared.first() == null && cleared.second() == null, "Clear corners retained a selection");
            requireSingleTestPlot(cleared, false);
            clickMessageStarting("Saved plots (");
            clickMessage("Prefer");
            requireSingleTestPlot(access.plotState(), true);
            clickMessage("Remove");
            Plot pending = requireSingleTestPlot(access.plotState(), true);
            require(claim.id().equals(pending.id()), "first remove click changed the saved plot");
            clickMessage("Confirm");
            PlotState removed = access.plotState();
            require(removed.liveClaims().isEmpty() && removed.diskClaims().isEmpty(),
                    "confirmed remove left a plot in live state or the isolated store");
            require(removed.first() == null && removed.second() == null, "plot removal restored cleared corners");
            clickMessage("Back");
            require(access.currentScreen() == plotSettingsParent, "Back did not return to the settings draft");
            require(Boolean.TRUE.equals(displayedBoolean("allowBreaking")), "Back discarded the settings draft");
            require(Boolean.FALSE.equals(access.readConfig("allowBreaking")), "plot UI saved the settings draft");
            clickMessage("Discard");
            access.reloadConfig();
            PlotState finalState = access.plotState();
            require(finalState.liveClaims().isEmpty() && finalState.diskClaims().isEmpty(),
                    "plot UI left test data in the isolated claims store");
            require(finalState.first() == null && finalState.second() == null, "plot UI left a selected corner");
            plotCleanupComplete = true;
            plotReceipt = "live=0 disk=0 corners=clear; preferred toggle persisted; double remove confirmed";
            log("native plot UI verified preferred toggle persistence, clear-selection retention, two-click removal, and settings-draft preservation");
            plotPhase = 3;
            return true;
        }
        return true;
    }

    private void typeField(String label, String text) throws Exception {
        Widget field = fieldContaining(label);
        require(field.textField(), "input is not a native text field: " + label);
        click(field);
        replaceFocusedText(text);
        require(text.equals(fieldContaining(label).text()), "native input differs after typing: " + label);
    }

    private void requirePlotFields() {
        require(QA_PLOT_NAME.equals(fieldContaining("Plot name").text()), "plot name did not survive native screen resize");
        require("-12".equals(fieldContaining("Corner 1 X").text()), "corner 1 X did not survive native screen resize");
        require("64".equals(fieldContaining("Corner 1 Y").text()), "corner 1 Y did not survive native screen resize");
        require("6".equals(fieldContaining("Corner 1 Z").text()), "corner 1 Z did not survive native screen resize");
        require("-9".equals(fieldContaining("Corner 2 X").text()), "corner 2 X did not survive native screen resize");
        require("66".equals(fieldContaining("Corner 2 Y").text()), "corner 2 Y did not survive native screen resize");
        require("2".equals(fieldContaining("Corner 2 Z").text()), "corner 2 Z did not survive native screen resize");
    }

    private void requirePlotFieldBounds(int width, int height) {
        for (String label : List.of("Plot name", "Corner 1 X", "Corner 1 Y", "Corner 1 Z",
                "Corner 2 X", "Corner 2 Y", "Corner 2 Z")) {
            Bounds bounds = fieldContaining(label).bounds();
            require(bounds.x() >= 0 && bounds.y() >= 0 && bounds.x() + bounds.width() <= width
                    && bounds.y() + bounds.height() <= height, "plot field lies outside resized screen: " + label);
        }
    }

    private Plot requireSingleTestPlot(PlotState state, boolean preferred) {
        require(!state.liveLocked() && !state.diskLocked(), "claims are locked while verifying plot persistence");
        require(state.liveClaims().size() == 1 && state.diskClaims().size() == 1,
                "expected one live and one disk-backed plot after UI action");
        Plot live = state.liveClaims().get(0);
        Plot disk = state.diskClaims().get(0);
        require(live.equals(disk), "live plot differs from reopened ClaimStore data");
        require(QA_PLOT_NAME.equals(live.name()), "unexpected plot name after native input");
        require(state.scope() != null && state.scope().equals(live.scope()), "plot was saved in a different world or dimension");
        require(live.minX() == -12 && live.minY() == 64 && live.minZ() == 2
                && live.maxX() == -9 && live.maxY() == 66 && live.maxZ() == 6,
                "saved inclusive plot bounds differ from the six native inputs");
        require(live.preferredStations() == preferred, "preferred-station state did not persist");
        return live;
    }

    private void search(String text) throws Exception {
        Widget search = fieldContaining("Search settings");
        click(search);
        replaceFocusedText(text);
        String observed = fieldContaining("Search settings").text();
        access.log("typed search=" + text + " observed=" + observed + " via native key/character dispatch field=" + search.bounds());
        require(text.equals(observed), "native search text differs from requested text");
    }

    private void replaceFocusedText(String text) throws Exception {
        access.dispatchKey(269, 0, 0);
        int existingLength = access.focusedTextLength();
        for (int i = 0; i < existingLength; i++) access.dispatchKey(259, 0, 0);
        require(access.focusedTextLength() == 0, "native Backspace did not clear the focused field");
        for (int point : text.codePoints().toArray()) access.dispatchCharacter(point);
    }

    private void clickSetting(String key) throws Exception {
        Widget widget = settingWidget(key);
        if (!widget.active()) throw new IllegalStateException("setting widget is disabled: " + key);
        click(widget);
    }

    private void click(Widget widget) throws Exception {
        Bounds b = widget.bounds();
        if (b == null || b.width() < 1 || b.height() < 1) throw new IllegalStateException("widget has no clickable bounds");
        if (b.x() < 0 || b.y() < 0 || b.x() + b.width() > access.screenWidth()
                || b.y() + b.height() > access.screenHeight())
            throw new IllegalStateException("widget click bounds are outside screen: " + b + " on "
                    + access.screenWidth() + "x" + access.screenHeight());
        int x = b.centerX(), y = b.centerY();
        access.log("native mouse click message='" + widget.message() + "' text='" + widget.text()
                + "' rect=" + b + " screen=" + access.screenWidth() + "x" + access.screenHeight());
        access.dispatchMouseClick(x, y);
    }

    private void clickMessage(String message) throws Exception {
        Widget widget = find(message, false);
        if (!widget.active()) throw new IllegalStateException("widget is disabled: " + message);
        click(widget);
    }

    private void clickMessageStarting(String prefix) throws Exception {
        Widget widget = widgets().stream().filter(w -> w.message() != null && w.message().startsWith(prefix))
                .findFirst().orElseThrow(() -> new IllegalStateException("visible button starts with '" + prefix + "' not found"));
        click(widget);
    }

    private void openNavigationOptions() throws Exception {
        access.openSettingsScreen();
        search("navigationPreferences");
        clickMessage("Filter");
        clickSetting("navigationPreferences");
    }

    private Widget settingWidget(String key) {
        Setting setting = access.settings().stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown setting " + key));
        String query = fieldContaining("Search settings").text().strip().toLowerCase(java.util.Locale.ROOT);
        List<Setting> matching = access.settings().stream().filter(candidate -> containsIgnoreCase(candidate.key(), query)
                || containsIgnoreCase(candidate.label(), query) || containsIgnoreCase(candidate.category(), query)
                || containsIgnoreCase(candidate.help(), query)).toList();
        int row = -1;
        for (int index = 0; index < matching.size(); index++)
            if (matching.get(index).key().equals(key)) { row = index; break; }
        if (row < 0 || row >= 8) throw new IllegalStateException("setting is not on the visible first page: " + key);
        List<Widget> controls = widgets().stream().filter(widget ->
                "On".equals(widget.message()) || "Off".equals(widget.message())
                        || widget.textField() && widget.bounds() != null && widget.bounds().y() > 60
                        || widget.message() != null && widget.message().startsWith("Edit · ")).toList();
        if (row >= controls.size()) throw new IllegalStateException("setting control missing from visible row " + key);
        Widget control = controls.get(row);
        boolean expected = switch (setting.type()) {
            case "BOOLEAN" -> "On".equals(control.message()) || "Off".equals(control.message());
            case "INTEGER", "DECIMAL", "TEXT" -> control.textField();
            case "STRING_MAP" -> control.message() != null && control.message().startsWith("Edit · ");
            default -> false;
        };
        if (!expected) throw new IllegalStateException("visible row control type mismatch for " + key);
        return control;
    }

    private Object displayedBoolean(String key) {
        Widget widget = settingWidget(key);
        if ("On".equals(widget.message())) return Boolean.TRUE;
        if ("Off".equals(widget.message())) return Boolean.FALSE;
        throw new IllegalStateException("boolean value widget is not On or Off for " + key + ": " + widget.message());
    }

    private boolean currentBoolean(String key) { return Boolean.TRUE.equals(access.readConfig(key)); }

    private Widget fieldContaining(String placeholder) {
        return widgets().stream().filter(Widget::textField)
                .filter(w -> contains(w.message(), placeholder) || contains(w.text(), placeholder))
                .findFirst().orElseThrow(() -> new IllegalStateException("visible text field not found: " + placeholder));
    }

    private Widget find(String message, boolean partial) {
        return widgets().stream().filter(w -> partial ? contains(w.message(), message) : message.equals(w.message()))
                .findFirst().orElseThrow(() -> new IllegalStateException("visible widget message not found: " + message));
    }

    private List<Widget> widgets() {
        Object screen = access.currentScreen();
        if (screen == null) throw new IllegalStateException("no screen is open");
        List<Widget> children = access.visibleChildren(screen);
        if (children == null) throw new IllegalStateException("screen child list is unavailable");
        return children;
    }

    private static boolean contains(String value, String part) { return value != null && value.contains(part); }
    private static boolean containsIgnoreCase(String value, String part) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(part);
    }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(new LinkedHashMap<>(map));
        if (value instanceof List<?> list) return List.copyOf(list);
        return value;
    }

    private void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private String screenName() {
        Object screen = access.currentScreen();
        return screen == null ? "<none>" : screen.getClass().getSimpleName();
    }

    private void logVisibleBounds() {
        for (Widget widget : widgets())
            log("visible widget message='" + widget.message() + "' text='" + widget.text()
                    + "' rect=" + widget.bounds() + " active=" + widget.active());
    }

    private void log(String line) { access.log(line); }
}

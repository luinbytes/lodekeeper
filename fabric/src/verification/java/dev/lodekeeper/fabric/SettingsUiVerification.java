package dev.lodekeeper.fabric;
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

    public interface Access {
        List<Widget> visibleChildren(Object screen);
        Object currentScreen();
        void openSettingsScreen();
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
        void requestCapture(String label);
        void log(String line);
        boolean engineIdle();
    }

    private enum State { READY, RUNNING, RESTORING, COMPLETE, FAILED }

    private interface Check { void run() throws Exception; }

    private final Access access;
    private final Map<String, Object> original = new LinkedHashMap<>();
    private final List<String> steps = new ArrayList<>();
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
    private String qaNavigationKey;

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
        add("navigation-map-open-editor-and-capture", () -> {
            if (!access.engineIdle()) throw new IllegalStateException("engine became active before map editing");
            qaNavigationKey = "zz-lodekeeper-uiqa-inert";
            navigationBaseline = new LinkedHashMap<>(access.navigationPreferences());
            if (navigationBaseline.containsKey(qaNavigationKey)) throw new IllegalStateException("reserved QA map key already exists");
            access.openSettingsScreen();
            search("navigationPreferences");
            clickMessage("Filter");
            clickSetting("navigationPreferences");
            captureHoldTicks = 2;
            delayedCaptureLabel = "navigation-map-editor";
        });
        add("navigation-map-add-save-reload-remove", () -> {
            Widget keyField = fieldContaining("Preference key");
            Widget valueField = fieldContaining("Preference value");
            click(keyField);
            replaceFocusedText(qaNavigationKey);
            click(valueField);
            replaceFocusedText("probe-value");
            clickMessage("Add / update");
            clickMessage("Back to settings");
            clickMessage("Save");
            access.reloadConfig();
            require("probe-value".equals(access.navigationPreferences().get(qaNavigationKey)),
                    "navigation map entry did not survive config reload");
            access.openSettingsScreen();
            search("navigationPreferences");
            clickMessage("Filter");
            clickSetting("navigationPreferences");
            clickExactVisibleAcrossPages(qaNavigationKey + " = probe-value");
            clickMessage("Remove");
            clickMessage("Back to settings");
            clickMessage("Save");
            access.reloadConfig();
            require(!access.navigationPreferences().containsKey(qaNavigationKey), "removed map entry returned after reload");
            require(access.navigationPreferences().equals(navigationBaseline), "navigation map differs from its pre-probe value");
        });
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

    private void add(String name, Check action) {
        steps.add(name);
        checks.add(action);
    }

    private final List<Check> checks = new ArrayList<>();

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
            checks.get(nextStep).run();
            trace.add("PASS " + activeStep);
            nextStep++;
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
                + "; framesHeld=" + heldFrames
                + "; captures=" + captureRequests
                + "; trace=" + String.join(" | ", trace)
                + (failures.isEmpty() ? "" : "; failures=" + String.join(" | ", failures));
    }

    public void restore() {
        if (restored) return;
        if (original.isEmpty()) {
            restored = true;
            return;
        }
        List<String> restoreErrors = new ArrayList<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            try { access.writeConfig(entry.getKey(), copy(entry.getValue())); }
            catch (Throwable failure) { restoreErrors.add(entry.getKey() + ": " + failure); }
        }
        try { access.saveConfig(); }
        catch (Throwable failure) { restoreErrors.add("save: " + failure); }
        try { access.reloadConfig(); }
        catch (Throwable failure) { restoreErrors.add("reload: " + failure); }
        restored = restoreErrors.isEmpty();
        if (!restored) failures.add("restore failed: " + String.join(", ", restoreErrors));
        log("settings UI restoration " + (restored ? "complete" : "failed: " + String.join(", ", restoreErrors)));
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

    private void clickExactVisibleAcrossPages(String message) throws Exception {
        for (int page = 0; page < 32; page++) {
            Widget row = widgets().stream().filter(widget -> message.equals(widget.message())).findFirst().orElse(null);
            if (row != null) {
                click(row);
                return;
            }
            Widget next = widgets().stream().filter(widget -> "Next".equals(widget.message())).findFirst().orElse(null);
            if (next == null || !next.active()) break;
            click(next);
        }
        throw new IllegalStateException("navigation row was not found on any visible page: " + message);
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

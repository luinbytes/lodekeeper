package dev.lodekeeper.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutomationSettingsTest {
    @Test
    void parsesOnlyFiniteNumbersAndStrictBooleans() {
        SettingSpec health = AutomationSettings.spec("pauseBelowHealth");
        assertEquals(6.5f, health.parse("6.5"));
        assertThrows(IllegalArgumentException.class, () -> health.parse("NaN"));
        assertThrows(IllegalArgumentException.class, () -> health.parse("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> health.parse("1e100"));
        assertThrows(IllegalArgumentException.class, () -> AutomationSettings.spec("searchRadius").parse("48.5"));
        assertThrows(IllegalArgumentException.class, () -> AutomationSettings.spec("allowBreaking").parse("yes"));
    }

    @Test
    void normalizesMapsWithinEntryAndStringBounds() {
        SettingSpec mapSpec = AutomationSettings.spec("navigationPreferences");
        Map<String, String> input = new LinkedHashMap<>();
        input.put("k".repeat(65), "ignored");
        input.put("long-value", "v".repeat(129));
        for (int i = 0; i < 129; i++) input.put("entry-" + i, "route-" + i);

        Map<String, String> normalized = stringMap(mapSpec.normalize(input));

        assertEquals(128, normalized.size());
        assertTrue(normalized.containsKey("entry-127"));
        assertFalse(normalized.containsKey("entry-128"));
        assertFalse(normalized.containsKey("long-value"));
        assertThrows(IllegalArgumentException.class, () -> mapSpec.validate(input));
    }

    @Test
    void validatedMapsAreImmutableCopies() {
        SettingSpec mapSpec = AutomationSettings.spec("navigationPreferences");
        Map<String, String> input = new LinkedHashMap<>();
        input.put("profile-a", "route-a");

        Map<String, String> validated = stringMap(mapSpec.validate(input));
        input.put("profile-b", "route-b");

        assertEquals(Map.of("profile-a", "route-a"), validated);
        assertThrows(UnsupportedOperationException.class, () -> validated.put("profile-c", "route-c"));
    }

    @Test
    void codecCoversTrustedFieldsResetsDefaultsAndRejectsUntrustedFields() throws ReflectiveOperationException {
        AutomationSettings.Codec<SettingsFixture> codec = AutomationSettings.codec(SettingsFixture.class);
        SettingsFixture config = new SettingsFixture();
        codec.resetAll(config);

        for (SettingSpec spec : AutomationSettings.specs()) {
            Field field = SettingsFixture.class.getField(spec.key());
            assertEquals(spec.defaultValue(), field.get(config), spec.key());
        }
        assertEquals(Boolean.TRUE, codec.read(config, "allowBreaking"));

        assertThrows(IllegalArgumentException.class,
                () -> AutomationSettings.codec(SettingsWithUntrustedField.class));
    }

    @Test
    void codecPreservesFalseAndDefaultsMalformedPersistedBooleans() {
        AutomationSettings.Codec<SettingsFixture> codec = AutomationSettings.codec(SettingsFixture.class);
        SettingsFixture config = new SettingsFixture();
        codec.resetAll(config);

        codec.write(config, "allowBreaking", "false");
        assertEquals(Boolean.FALSE, codec.read(config, "allowBreaking"));
        assertThrows(IllegalArgumentException.class, () -> codec.write(config, "allowBreaking", "false-ish"));

        List<String> warnings = new ArrayList<>();
        codec.importValues(config, Map.of("allowBreaking", false), warnings::add);

        assertEquals(Boolean.FALSE, codec.read(config, "allowBreaking"));
        assertTrue(warnings.isEmpty());

        codec.importValues(config, Map.of("allowBreaking", "false"), warnings::add);

        assertEquals(Boolean.TRUE, codec.read(config, "allowBreaking"));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("allowBreaking")));
    }

    @Test
    void draftKeepsEditsIsolatedAndRestoresActiveSettingsWhenSaveFails() {
        Map<String, Object> active = new LinkedHashMap<>();
        for (SettingSpec spec : AutomationSettings.specs()) active.put(spec.key(), spec.defaultValue());
        active.put("allowBreaking", false);
        active.put("navigationPreferences", Map.of("route-profile", "safe"));
        SettingsDraft draft = new SettingsDraft(AutomationSettings.specs(), active::get);
        draft.setValue("allowBreaking", true);
        draft.putNavigationPreference("route-profile", "quick");

        assertEquals(false, active.get("allowBreaking"));
        assertEquals(Map.of("route-profile", "safe"), active.get("navigationPreferences"));
        assertThrows(java.io.IOException.class,
                () -> draft.saveTo(active::get, active::put, () -> { throw new java.io.IOException("unwritable config"); }));
        assertEquals(false, active.get("allowBreaking"));
        assertEquals(Map.of("route-profile", "safe"), active.get("navigationPreferences"));
        assertTrue(draft.isDirty());

        assertDoesNotThrow(() -> draft.saveTo(active::get, active::put, () -> {}));
        assertEquals(true, active.get("allowBreaking"));
        assertEquals(Map.of("route-profile", "quick"), active.get("navigationPreferences"));
        assertFalse(draft.isDirty());
    }

    @Test
    void disablingAParentPreservesItsDependentStoredOption() {
        SettingsDraft draft = new SettingsDraft(AutomationSettings.specs(), key -> AutomationSettings.spec(key).defaultValue());
        draft.setValue("allowParkour", true);
        assertTrue(draft.enabled("allowParkourPlace"));
        draft.setValue("allowParkour", false);
        assertFalse(draft.enabled("allowParkourPlace"));
        assertEquals(true, draft.value("allowParkourPlace"));
        draft.setValue("allowParkour", true);
        assertTrue(draft.enabled("allowParkourPlace"));
    }

    private static Map<String, String> stringMap(Object value) {
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) value;
        return result;
    }

    public static class SettingsFixture {
        public String prefix;
        public int searchRadius;
        public boolean allowExploration;
        public int explorationAttempts;
        public int explorationDistance;
        public int scanBlocksPerTick;
        public int actionTimeoutTicks;
        public float pauseBelowHealth;
        public boolean allowBreaking;
        public boolean allowBuilding;
        public boolean allowParkour;
        public boolean allowParkourPlace;
        public boolean allowSprint;
        public boolean allowDiagonalAscend;
        public boolean allowDiagonalDescend;
        public int maxFallHeight;
        public boolean allowWaterBucketFall;
        public boolean allowContainers;
        public boolean pauseOnScreen;
        public boolean autoEat;
        public boolean autoEquipArmor;
        public boolean autoDefend;
        public boolean optimizeWoodTools;
        public boolean avoidance;
        public int mobAvoidanceRadius;
        public int spawnerAvoidanceRadius;
        public int mineGoalUpdateTicks;
        public int pathInitialSearchMillis;
        public int pathInitialFailureMillis;
        public int pathContinuationSearchMillis;
        public int pathContinuationFailureMillis;
        public boolean recoverPlacedStations;
        public int stationRecoveryRange;
        public boolean backfill;
        public int backfillPendingLimit;
        public boolean backfillEquivalentStone;
        public boolean showPath;
        public boolean showSearch;
        public boolean showHud;
        public boolean showNextBreak;
        public boolean showNextPlace;
        public boolean showParkour;
        public boolean showClaims;
        public boolean showStations;
        public boolean showBackfill;
        public int visualizationDistance;
        public boolean debugLogging;
        public Map<String, String> navigationPreferences;
    }

    public static class SettingsWithUntrustedField extends SettingsFixture {
        public int untrusted;
    }
}

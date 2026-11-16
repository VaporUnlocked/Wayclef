package dev.soprano.ui;

import dev.soprano.game.Navigation;
import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MovementProfilesTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("soprano-movement-test-");
        try {
            var first = new Scope("singleplayer", "World A", "minecraft:overworld");
            var nether = new Scope("singleplayer", "World A", "minecraft:the_nether");
            var other = new Scope("singleplayer", "World B", "minecraft:overworld");
            var remote = new Scope("example.test", "World A", "minecraft:overworld");
            var cautious = Map.of("diagonal-walking", "false", "max-drop", "0");
            var explore = Map.of("transport", "AUTO", "adaptive-planning", "true");
            var profiles = new MovementProfiles(root);
            check(profiles.names().isEmpty() && profiles.loadDefaults(first).isEmpty(), "first run is empty");
            profiles.savePreset(" Explorer ", explore);
            profiles.savePreset("Cautious", cautious);
            profiles.saveDefaults(first, cautious);
            profiles.saveDefaults(remote, explore);

            var restored = new MovementProfiles(root);
            check(restored.names().equals(List.of("Cautious", "Explorer")), "presets reopen sorted by name");
            check(restored.loadPreset("eXpLoReR").equals(explore), "lookup ignores case");
            check(restored.loadDefaults(nether).equals(cautious), "defaults apply across dimensions in one world");
            check(restored.loadDefaults(other).isEmpty(), "defaults do not spill between local worlds");
            check(restored.loadDefaults(remote).equals(explore), "server identity isolates defaults");
            check(restored.clearDefaults(first) && restored.loadDefaults(nether).isEmpty(), "clear restores fallback");
            restored.savePreset("cautious", explore);
            check(restored.names().equals(List.of("cautious", "Explorer")), "case variants update one preset");
            check(restored.deletePreset("cAuTiOuS") && !restored.deletePreset("cautious"), "deletion persists");
            try {
                restored.savePreset("Invalid", Map.of("unsafe\nkey", "yes"));
                throw new AssertionError("Invalid setting key was accepted.");
            } catch (IllegalArgumentException expected) {
                check(new MovementProfiles(root).names().equals(List.of("Explorer")), "invalid writes preserve existing presets");
            }
            layeredSettings(root.resolve("client.properties"), restored, first, other);
            clipboardRoundTrip(root.resolve("clipboard.properties"));
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        historicalPlanningOptions();
        System.out.println("Soprano movement profile checks passed.");
    }


    private static void clipboardRoundTrip(Path file) {
        var values = Map.of("diagonal-walking", "false", "max-drop", "3", "future-option", "17");
        String encoded = MovementProfiles.encodePreset("Hill routes ✓", values);
        var decoded = MovementProfiles.decodePreset("  " + encoded + "\n");
        check(decoded.equals(new MovementProfiles.Preset("Hill routes ✓", values)), "clipboard round trips settings and Unicode names");
        var preferences = new PlanningSettings.Store(file);
        preferences.toggle("diagonal-walking", "", true);
        preferences.number("max-drop", "", 1, 0, 2);
        var checked = preferences.validated(decoded.settings(), Set.of("diagonal-walking", "max-drop"));
        check(checked.equals(Map.of("diagonal-walking", "false", "max-drop", "2")),
            "clipboard review filters unknown values and reports the supported limit");
        for (String invalid : List.of("normal clipboard text", "SOPRANO-MOVEMENT-1:?", encoded + "aaaa")) {
            try {
                MovementProfiles.decodePreset(invalid);
                throw new AssertionError("Invalid clipboard data was accepted.");
            } catch (IllegalArgumentException expected) {
                check(true, "invalid clipboard data is rejected");
            }
        }
    }

    private static void historicalPlanningOptions() {
        var fixed = HistorySettings.navigation("1,1,1,1,96,12000,0,OPTIMAL,36,1,1,2,3,32,WALK,3,12,0,0,0,1,0");
        check(!fixed.adaptivePlanning(), "legacy route history retains fixed planning");
        var adaptive = new Navigation.Options(fixed.search(), fixed.expansionsPerTick(), fixed.steerView(), fixed.autoReplan(),
            fixed.planningBudgetMillis(), fixed.replanLimit(), fixed.partialSegmentLimit(), fixed.transportMode(),
            fixed.elytraReserves(), fixed.avoidMobs(), fixed.permissions(), fixed.vitality(), true);
        check(HistorySettings.navigation(HistorySettings.navigation(adaptive)).equals(adaptive),
            "adaptive planning survives a task history round trip");
    }

    private static void layeredSettings(Path file, MovementProfiles profiles, Scope first, Scope other) {
        var settings = new PlanningSettings.Store(file);
        var diagonals = settings.toggle("diagonal-walking", "", true);
        var adaptive = settings.toggle("adaptive-planning", "", false);
        var renderQuality = settings.number("map-render-quality", "", 100, 50, 150);
        renderQuality.set(75);
        var movementKeys = Set.of("diagonal-walking", "adaptive-planning");
        profiles.savePreset("Movement check", Map.of("diagonal-walking", "false", "adaptive-planning", "true"));
        settings.openScope(profiles.loadDefaults(first), values -> profiles.saveDefaults(first, values));
        settings.editScope(true);
        settings.apply(profiles.loadPreset("Movement check"), movementKeys);
        check(!diagonals.get() && adaptive.get() && renderQuality.get() == 75, "scope preset merges with unrelated global values");
        check(profiles.loadDefaults(first).equals(profiles.loadPreset("Movement check")), "scope captures only movement changes");

        settings.openScope(profiles.loadDefaults(other), values -> profiles.saveDefaults(other, values));
        check(diagonals.get() && !adaptive.get() && renderQuality.get() == 75, "world change removes previous overrides");
        settings.openScope(Map.of(), null);
        check(!settings.editingScope(), "disconnect clears the scoped editing target");
        settings.apply(profiles.loadPreset("Movement check"), movementKeys);
        var reopened = new PlanningSettings.Store(file);
        check(!reopened.toggle("diagonal-walking", "", true).get()
            && reopened.number("map-render-quality", "", 100, 50, 150).get() == 75,
            "global preset persists without discarding unrelated settings");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

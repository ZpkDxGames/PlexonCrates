package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnimationPreviewArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/antondev/crates/gui/SimulationAdminListener.java");

    @Test
    void animationPreviewUsesDrySelectionAndProfileDrivenPresentationOnly() throws Exception {
        String source = Files.readString(SOURCE);
        String method = section(source, "private void previewAnimation", "private void openExactItemAudit");
        assertTrue(method.contains("simulations.dryRun"));
        assertTrue(method.contains("animationProfiles.resolve(crate.id(), crate.animation())"));
        assertTrue(method.contains("switch (profile.style())"));
        assertTrue(method.contains("case ROULETTE, SPIN, CASCADE"));
        assertTrue(method.contains("case CHARGE_REVEAL, SPIRAL_BURST, ORB_REVEAL, FIREWORK_STYLE"));
        assertTrue(method.contains("plugin.menus().animate"));
        assertTrue(method.contains("plugin.menus().reveal"));
        assertTrue(method.contains("case INSTANT -> showDry"));
        assertFalse(method.contains("plugin.openings()"));
        assertFalse(method.contains("plugin.database()"));
        assertFalse(method.contains("plugin.rewardStates()"));
        assertFalse(method.contains("plugin.keys().consume"));
        assertFalse(method.contains("plugin.statistics()"));
        assertFalse(method.contains("deliver("));
    }

    @Test
    void previewOnlyReturnsToLabWhenOpeningViewIsStillVisible() throws Exception {
        String source = Files.readString(SOURCE);
        String method = section(source, "private void previewAnimation", "private void openExactItemAudit");
        assertTrue(method.contains("menu.kind() == MenuHolder.Kind.OPENING"));
        assertTrue(method.contains("openHub(player, crate.id(), source.snapshot.mode())"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}

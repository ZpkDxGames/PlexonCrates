package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OpeningProfilePresentationArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/antondev/crates/gui/OpeningProfilePresentationService.java");

    @Test
    void serviceUsesOneSharedCoordinatorAndExplicitProfile() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("static synchronized OpeningProfilePresentationService shared"));
        assertTrue(source.contains("private final OpeningAnimationCoordinator coordinator"));
        assertTrue(source.contains("coordinator.start(player, holder, inventory, rail, visuals, selected"));
        assertTrue(source.contains("profile, completed"));
        assertTrue(source.contains("profile.style() == OpeningAnimationStyle.INSTANT"));
        assertFalse(source.contains("runTaskTimer"));
        assertFalse(source.contains("new BukkitRunnable"));
    }

    @Test
    void rendererAcceptsAuthoritativeSelectionButOwnsNoTransactionCalls() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("CrateReward selected"));
        assertFalse(source.contains("plugin.openings()"));
        assertFalse(source.contains("plugin.keys()"));
        assertFalse(source.contains("plugin.database()"));
        assertFalse(source.contains("plugin.statistics()"));
        assertFalse(source.contains("RewardSelector."));
        assertFalse(source.contains("journal("));
        assertFalse(source.contains("pity("));
        assertFalse(source.contains("deliver("));
    }

    @Test
    void presentationUsesExistingOpeningSurfaceAndSessionAuthority() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("MenuHolder.Kind.OPENING"));
        assertTrue(source.contains("menus.size(\"opening\")"));
        assertTrue(source.contains("opening.marker-top-slot"));
        assertTrue(source.contains("opening.rail-slots"));
        assertTrue(source.contains("plugin.guiSessions().activate"));
    }
}

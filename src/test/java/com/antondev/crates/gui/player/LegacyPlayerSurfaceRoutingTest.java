package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LegacyPlayerSurfaceRoutingTest {
    private static final Path ROUTER = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerCrateCommandRouter.java");
    private static final Path UX = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerCrateMenuService.java");

    @Test
    void registeredRouterForwardsInventoryOpenToPhase3Ux() throws IOException {
        String router = Files.readString(ROUTER);
        assertTrue(router.contains("import org.bukkit.event.inventory.InventoryOpenEvent;"));
        assertTrue(router.contains("public void open(InventoryOpenEvent event)"));
        assertTrue(router.contains("menus.redirectLegacyPlayerSurface(event);"));
    }

    @Test
    void redirectIsLimitedToLegacyPlayerBrowserAndPreview() throws IOException {
        String source = Files.readString(UX);
        int start = source.indexOf("public void redirectLegacyPlayerSurface");
        int end = source.indexOf("public void pendingRewardsCommand", start);
        assertTrue(start >= 0 && end > start);
        String redirect = source.substring(start, end);
        assertTrue(redirect.contains("MenuHolder.Kind.BROWSER"));
        assertTrue(redirect.contains("MenuHolder.Kind.PREVIEW"));
        assertFalse(redirect.contains("MenuHolder.Kind.PORTABLE_PREVIEW"));
        assertTrue(redirect.contains("holder.adminOrigin()"));
    }
}

package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiDesignSystemArchitectureTest {
    @Test void sharedDesignSystemExistsAndLegacyPlayerFillersAreGone() throws Exception {
        Path root = Path.of("src/main/java/com/antondev/crates/gui");
        assertTrue(Files.exists(root.resolve("GuiTheme.java")));
        assertTrue(Files.exists(root.resolve("GuiChromeRenderer.java")));
        assertTrue(Files.exists(root.resolve("GuiLayout.java")));
        assertTrue(Files.exists(root.resolve("GuiItemFactory.java")));
        for (String file : java.util.List.of(
                "player/PlayerCrateMenuService.java", "player/PlayerKeyMenuService.java", "player/PlayerHistoryMenuService.java")) {
            String source = Files.readString(root.resolve(file));
            assertFalse(source.contains("Material.GRAY_STAINED_GLASS_PANE"), file + " must use GuiTheme");
            assertTrue(source.contains("GuiChromeRenderer.render"), file + " must use shared chrome");
        }
    }

    @Test void centralRouterRemainsTheOnlyRegisteredMenuAuthority() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/com/antondev/crates/PlexonCrates.java"));
        assertTrue(plugin.contains("new CrateMenuEventRouter"));
        assertFalse(plugin.contains("registerEvents(menus"));
        assertFalse(plugin.contains("registerEvents(adminMenus"));
    }
}

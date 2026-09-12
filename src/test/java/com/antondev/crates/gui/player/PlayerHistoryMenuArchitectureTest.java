package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlayerHistoryMenuArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerHistoryMenuService.java");
    private static final Path ROUTER = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerCrateCommandRouter.java");
    private static final Path EVENT_ROUTER = Path.of(
            "src/main/java/com/antondev/crates/gui/CrateMenuEventRouter.java");

    @Test
    void historyLoadsDurableRecordsAsynchronouslyAndPaginatesByLayoutCapacity() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("plugin.database().historyAsync"));
        assertTrue(source.contains("PAGE_SIZE + 1"));
        assertTrue(source.contains("PlayerCrateLayout.contentSlots()"));
        assertTrue(source.contains("case \"previous\""));
        assertTrue(source.contains("case \"next\""));
        assertTrue(source.contains("case \"refresh\""));
        assertFalse(source.contains("plugin.database().history("));
        assertFalse(source.contains(".join()"));
        assertFalse(source.contains(".get()"));
    }

    @Test
    void historyIsReadOnlyAndUsesExistingCratePreviewNavigation() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("PLAYER_HISTORY"));
        assertTrue(source.contains("crateMenus.openPreview"));
        assertFalse(source.contains("plugin.openings()"));
        assertFalse(source.contains("plugin.keys().consume"));
        assertFalse(source.contains("plugin.statistics().record"));
        assertFalse(source.contains("deliver("));
        assertFalse(source.contains("implements Listener"));
    }

    @Test
    void commandHallAndCentralRouterExposeHistorySurface() throws Exception {
        String router = Files.readString(ROUTER);
        String eventRouter = Files.readString(EVENT_ROUTER);
        assertTrue(router.contains("action.equals(\"history\")"));
        assertTrue(router.contains("historyMenus.openHistory(player, page - 1)"));
        assertTrue(router.contains("historyMenus.decorateHall(player)"));
        assertTrue(router.contains("historyMenus.routeClick(event)"));
        assertTrue(eventRouter.contains("PLAYER_HISTORY -> true"));
    }
}

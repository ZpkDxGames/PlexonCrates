package com.antondev.crates.gui.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlayerKeyMenuArchitectureTest {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerKeyMenuService.java");
    private static final Path ROUTER = Path.of(
            "src/main/java/com/antondev/crates/gui/player/PlayerCrateCommandRouter.java");
    private static final Path EVENT_ROUTER = Path.of(
            "src/main/java/com/antondev/crates/gui/CrateMenuEventRouter.java");

    @Test
    void myKeysUsesAuthoritativeKeyAndWalletSourcesWithoutConsumingAnything() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("plugin.keys().definitions()"));
        assertTrue(source.contains("plugin.keys().count(player, definition.id())"));
        assertTrue(source.contains("plugin.keys().usesPlexonKeysWallet(keyId)"));
        assertTrue(source.contains("plugin.keys().plexonKeysBalance(player.getUniqueId(), keyId)"));
        assertTrue(source.contains("plugin.database().loadVirtualKeyBalance(player.getUniqueId(), keyId)"));
        assertTrue(source.contains("crate.acceptedKeyIds().contains(keyId)"));
        assertFalse(source.contains("plugin.keys().consume("));
        assertFalse(source.contains("plugin.keys().give("));
        assertFalse(source.contains("plugin.openings().open("));
    }

    @Test
    void myKeysIsPaginatedAndDoesNotRegisterAnotherInventoryListener() throws Exception {
        String source = Files.readString(SERVICE);
        assertTrue(source.contains("PlayerCrateLayout.clampPage"));
        assertTrue(source.contains("PlayerCrateLayout.contentSlots()"));
        assertTrue(source.contains("PLAYER_KEYS"));
        assertTrue(source.contains("PLAYER_KEY_CRATES"));
        assertFalse(source.contains("implements Listener"));
        assertFalse(source.contains("@EventHandler"));
        assertFalse(source.contains(".limit(10)"));
    }

    @Test
    void commandAndCentralRouterOwnBothKeySurfaces() throws Exception {
        String commandRouter = Files.readString(ROUTER);
        String eventRouter = Files.readString(EVENT_ROUTER);
        assertTrue(commandRouter.contains("action.equals(\"keys\")"));
        assertTrue(commandRouter.contains("keyMenus.openKeys(player, page - 1)"));
        assertTrue(commandRouter.contains("keyMenus.routeClick(event)"));
        assertTrue(eventRouter.contains("PLAYER_KEYS, PLAYER_KEY_CRATES"));
    }
}

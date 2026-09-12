package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LiveReloadIoBoundaryArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java/com/antondev/crates/PlexonCrates.java");
    private static final Path ADMIN_GUI = Path.of("src/main/java/com/antondev/crates/gui/AdminMenuService.java");
    private static final Path LEGACY_GUI = Path.of("src/main/java/com/antondev/crates/gui/MenuService.java");
    private static final Path ADMIN_COMMAND = Path.of("src/main/java/com/antondev/crates/command/CratesAdminCommand.java");

    @Test
    void liveReloadEntryOnlyCoordinatesAsyncDatabaseAndIoStages() throws Exception {
        String source = Files.readString(MAIN);
        String live = section(source, "public void requestReload(CommandSender sender, Runnable onSuccess)",
                "private ReloadPreparation prepareReload(");
        assertTrue(live.contains("definitionRepository.loadPublished()"));
        assertTrue(live.contains("definitionRepository.loadDrafts()"));
        assertTrue(live.contains("definitionRepository.loadKeys()"));
        assertTrue(live.contains("CompletableFuture.allOf"));
        assertTrue(live.contains("io.submit(() -> prepareReload("));
        assertTrue(live.contains("getServer().getScheduler().runTask(this"));
        assertFalse(live.contains("PluginSettings.load("));
        assertFalse(live.contains("Messages.load("));
        assertFalse(live.contains("MenuConfig.load("));
        assertFalse(live.contains("CrateRegistry.load("));
        assertFalse(live.contains("loadKeySnapshot("));
        assertFalse(live.contains("mergeKeyCaches("));
        assertFalse(live.contains(".join()"));
    }

    @Test
    void reloadPreparationOwnsEveryFileAndSynchronousCacheRead() throws Exception {
        String source = Files.readString(MAIN);
        String prepare = section(source, "private ReloadPreparation prepareReload(",
                "private boolean applyReload(");
        assertTrue(prepare.contains("PluginSettings.load(file(\"config.yml\"))"));
        assertTrue(prepare.contains("Messages.load(file(\"messages.yml\"))"));
        assertTrue(prepare.contains("MenuConfig.load(file(\"menus.yml\"))"));
        assertTrue(prepare.contains("CrateRegistry.load(crateDirectory)"));
        assertTrue(prepare.contains("KeyService.fromDatabase(keyRows"));
        assertTrue(prepare.contains("loadKeySnapshot(nextSettings.fallbackFile(), canonicalKeys)"));
        assertTrue(prepare.contains("mergeKeyCaches(canonicalKeys)"));
        assertFalse(prepare.contains("getServer().getScheduler().runTask"));
    }

    @Test
    void primaryReloadApplicationContainsNoFileOrBlockingDatabaseReads() throws Exception {
        String source = Files.readString(MAIN);
        String apply = section(source, "private boolean applyReload(",
                "private record ReloadPreparation(");
        assertTrue(apply.contains("crates.apply(nextCrates)"));
        assertTrue(apply.contains("keys.apply(nextKeys, nextKeyCache)"));
        assertTrue(apply.contains("menus.closeAll()"));
        assertTrue(apply.contains("displays.refresh()"));
        assertFalse(apply.contains("PluginSettings.load("));
        assertFalse(apply.contains("Messages.load("));
        assertFalse(apply.contains("MenuConfig.load("));
        assertFalse(apply.contains("CrateRegistry.load("));
        assertFalse(apply.contains("loadKeySnapshot("));
        assertFalse(apply.contains("mergeKeyCaches("));
        assertFalse(apply.contains(".join()"));
        assertFalse(apply.contains("Files."));
    }

    @Test
    void everyLiveAdminReloadSurfaceUsesAsyncRequestWithGuiReopenCallback() throws Exception {
        String adminGui = Files.readString(ADMIN_GUI);
        String legacyGui = Files.readString(LEGACY_GUI);
        String command = Files.readString(ADMIN_COMMAND);
        assertTrue(adminGui.contains("plugin.requestReload(player, () ->"));
        assertTrue(legacyGui.contains("plugin.requestReload(player, () ->"));
        assertTrue(command.contains("case \"reload\" -> plugin.requestReload(sender)"));
        assertFalse(adminGui.contains("plugin.reloadFor(player)"));
        assertFalse(legacyGui.contains("plugin.reloadFor(player)"));
        assertFalse(command.contains("plugin.reloadFor(sender)"));
    }

    @Test
    void successCallbackRunsOnlyAfterSuccessfulPrimaryApplication() throws Exception {
        String source = Files.readString(MAIN);
        String live = section(source, "public void requestReload(CommandSender sender, Runnable onSuccess)",
                "private ReloadPreparation prepareReload(");
        assertTrue(live.contains("if (applyReload(sender, prepared) && onSuccess != null && isEnabled()) onSuccess.run()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

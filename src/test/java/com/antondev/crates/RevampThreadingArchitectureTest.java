package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RevampThreadingArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java/com/antondev/crates/PlexonCrates.java");
    private static final Path ADMIN = Path.of("src/main/java/com/antondev/crates/command/CratesAdminCommand.java");
    private static final Path ROUTER = Path.of("src/main/java/com/antondev/crates/gui/CrateMenuEventRouter.java");

    @Test
    void liveReloadPreloadsCanonicalDatabaseStateWithoutPrimaryThreadJoins() throws Exception {
        String source = Files.readString(MAIN);
        String liveReload = section(source, "public void requestReload(CommandSender sender)",
                "private boolean applyReload(");
        assertTrue(liveReload.contains("definitionRepository.loadPublished()"));
        assertTrue(liveReload.contains("definitionRepository.loadDrafts()"));
        assertTrue(liveReload.contains("definitionRepository.loadKeys()"));
        assertTrue(liveReload.contains("CompletableFuture.allOf"));
        assertTrue(liveReload.contains("getServer().getScheduler().runTask(this"));
        assertTrue(liveReload.contains("getNow("));
        assertFalse(liveReload.contains(".join()"));
    }

    @Test
    void liveReloadEntryPointsUseAsyncPreloadPath() throws Exception {
        String admin = Files.readString(ADMIN);
        String router = Files.readString(ROUTER);
        assertTrue(admin.contains("case \"reload\" -> plugin.requestReload(sender)"));
        assertTrue(router.contains("plugin.requestReload(player)"));
    }

    @Test
    void diagnosticsCollectDatabaseHealthOffPrimaryThreadAndRenderBackOnPrimary() throws Exception {
        String source = Files.readString(MAIN);
        String diagnose = section(source, "public void diagnoseFor(CommandSender sender)",
                "private DiagnosticDatabaseSnapshot collectDiagnosticDatabaseSnapshot()");
        assertTrue(diagnose.contains("runTaskAsynchronously(this"));
        assertTrue(diagnose.contains("getServer().getScheduler().runTask(this"));
        assertFalse(diagnose.contains("database.pendingJournalCount()"));
        assertFalse(diagnose.contains(".join()"));

        String collect = section(source, "private DiagnosticDatabaseSnapshot collectDiagnosticDatabaseSnapshot()",
                "private void renderDiagnostics(");
        assertTrue(collect.contains("database.pendingJournalCount()"));
        assertTrue(collect.contains("database.journalHealth()"));
        assertTrue(collect.contains("database.journalDiagnostics(5)"));
        assertTrue(collect.contains("database.claimCounts().join()"));
        assertTrue(collect.contains("database.portableIssueCounts().join()"));
    }

    @Test
    void postReloadCoreHealthProbeDoesNotBlockStateSwap() throws Exception {
        String source = Files.readString(MAIN);
        String apply = section(source, "private boolean applyReload(",
                "private static Exception completionException");
        assertTrue(apply.contains("updateCoreHealthAsync()"));
        assertFalse(apply.contains("pendingJournalCount()"));

        String asyncHealth = section(source, "private void updateCoreHealthAsync()",
                "private void applyCoreHealth(");
        assertTrue(asyncHealth.contains("runTaskAsynchronously(this"));
        assertTrue(asyncHealth.contains("database.pendingJournalCount()"));
        assertTrue(asyncHealth.contains("getServer().getScheduler().runTask(this"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

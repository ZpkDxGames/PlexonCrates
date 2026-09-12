package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PhoenixMigrationThreadingArchitectureTest {
    private static final Path COMMAND = Path.of(
            "src/main/java/com/antondev/crates/command/PhoenixMigrationCommand.java");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/antondev/crates/migration/phoenix/PhoenixMigrationService.java");

    @Test
    void liveReadOnlyCommandsUseBoundedIoAndReturnToPrimaryThreadForRendering() throws Exception {
        String source = Files.readString(COMMAND);
        assertTrue(source.contains("plugin.io().submit(task)"));
        assertTrue(source.contains("Bukkit.getScheduler().runTask(plugin"));
        assertTrue(source.contains("service::scan"));
        assertTrue(source.contains("service.plan(scan, state)"));
        assertTrue(source.contains("service.validate(plan, state)"));
        assertTrue(source.contains("service.writeReport(scan, plan)"));
    }

    @Test
    void planningStateIsCapturedOnlyOnPrimaryThread() throws Exception {
        String source = Files.readString(SERVICE);
        String capture = section(source, "public PlanningState capturePlanningState()",
                "/** Parses the supplied live fixture");
        assertTrue(capture.contains("if (!Bukkit.isPrimaryThread())"));
        assertTrue(capture.contains("plugin.keys().definitions()"));
        assertTrue(capture.contains("plugin.crates().snapshot()"));
        assertTrue(capture.contains("plugin.locations().all()"));
    }

    @Test
    void workerPlanningConsumesSnapshotInsteadOfLivePluginState() throws Exception {
        String source = Files.readString(SERVICE);
        String plan = section(source, "public PlanResult plan(ScanResult scan, PlanningState state)",
                "/**\n     * Live destructive import coordinator");
        assertTrue(plan.contains("state.phoenixEnabled()"));
        assertTrue(plan.contains("state.keys()"));
        assertTrue(plan.contains("state.crateFingerprints()"));
        assertTrue(plan.contains("state.locations()"));
        assertFalse(plan.contains("plugin."));
        assertFalse(plan.contains("Bukkit."));
    }

    @Test
    void workerValidationReusesCapturedPlanningState() throws Exception {
        String source = Files.readString(SERVICE);
        String validation = section(source,
                "public ValidationResult validate(PlanResult plan, PlanningState state)",
                "public Path writeReport(");
        assertTrue(validation.contains("PlanResult fresh = plan(current, state)"));
        assertFalse(validation.contains("plugin."));
        assertFalse(validation.contains("Bukkit."));
    }

    @Test
    void destructiveImportAlternatesBoundedIoAndPrimaryMutationStages() throws Exception {
        String source = Files.readString(SERVICE);
        String coordinator = section(source,
                "public CompletableFuture<ImportResult> importToDraftsAsync(",
                "private AsyncImportPreparation prepareAsyncImport(");
        assertTrue(coordinator.contains("plugin.io().submit(() -> prepareAsyncImport(planningState))"));
        assertTrue(coordinator.contains("thenCompose(prepared -> primary("));
        assertTrue(coordinator.contains("plugin.io().submit(() -> buildDraftPayloads(keyed))"));
        assertTrue(coordinator.contains("primary(() -> prepareDraftBatch(payloads, actor))"));
        assertTrue(coordinator.contains("plugin.io().submit(() -> writeDraftBatch(batch))"));
        assertTrue(coordinator.contains("primary(() -> installDraftBatch(batch))"));
        assertTrue(coordinator.contains("plugin.io().submit(() -> persistAsyncImport(installed, actorId, actor))"));
        assertTrue(coordinator.contains("primary(() -> activatePersistedImport(persisted))"));
    }

    @Test
    void draftPreparationAndActivationRemainPrimaryWhileFileWriteIsIsolated() throws Exception {
        String source = Files.readString(SERVICE);
        String prepare = section(source, "private PreparedDraftBatch prepareDraftBatch(",
                "private PreparedDraftBatch writeDraftBatch(");
        String write = section(source, "private PreparedDraftBatch writeDraftBatch(",
                "private InstalledBatch installDraftBatch(");
        String install = section(source, "private InstalledBatch installDraftBatch(",
                "private PersistedImport persistAsyncImport(");
        assertTrue(prepare.contains("Bukkit.isPrimaryThread()"));
        assertTrue(prepare.contains("prepareImportedDraft("));
        assertFalse(prepare.contains("Files."));
        assertTrue(write.contains("writeImportedDraft(prepared)"));
        assertFalse(write.contains("Bukkit."));
        assertFalse(write.contains("installImportedDraft("));
        assertTrue(install.contains("Bukkit.isPrimaryThread()"));
        assertTrue(install.contains("installImportedDraft(prepared)"));
        assertTrue(install.contains("Bukkit.getWorld("));
        assertFalse(install.contains("Files."));
    }

    @Test
    void persistenceStageDoesNotTouchBukkitWorldOrRegistryMutationApis() throws Exception {
        String source = Files.readString(SERVICE);
        String persist = section(source, "private PersistedImport persistAsyncImport(",
                "private ImportResult activatePersistedImport(");
        assertTrue(persist.contains("plugin.database().audit("));
        assertTrue(persist.contains("importAggregateHistoryAsync("));
        assertTrue(persist.contains("importLocationsAsync("));
        assertTrue(persist.contains("writeReport("));
        assertTrue(persist.contains("Files.writeString(marker"));
        assertFalse(persist.contains("Bukkit."));
        assertFalse(persist.contains("installImportedDraft("));
        assertFalse(persist.contains("plugin.locations().apply("));
        assertFalse(persist.contains("plugin.statistics().record("));
    }

    @Test
    void commandUsesAsyncImportAndReturnsRenderingToPrimaryThread() throws Exception {
        String source = Files.readString(COMMAND);
        String importData = section(source, "private static void importData(",
                "private static void renderImport(");
        assertTrue(importData.contains("service.capturePlanningState()"));
        assertTrue(importData.contains("service.importToDraftsAsync(state, actorId, actorName)"));
        assertTrue(importData.contains("Bukkit.getScheduler().runTask(plugin"));
        assertFalse(importData.contains("service.scan()"));
        assertFalse(importData.contains("service.importToDrafts(plan"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

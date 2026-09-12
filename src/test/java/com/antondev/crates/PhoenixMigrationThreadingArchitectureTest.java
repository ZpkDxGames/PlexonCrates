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
                "/** Compatibility overload; live imports");
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

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

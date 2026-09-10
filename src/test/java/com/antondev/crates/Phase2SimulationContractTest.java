package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Phase2SimulationContractTest {
    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/antondev/crates").resolve(relative));
    }

    @Test
    void analyticalServiceHasNoBukkitDatabaseOrGrantPipelineDependency() throws Exception {
        String simulation = source("service/CrateSimulationService.java");
        assertFalse(simulation.contains("org.bukkit"));
        assertFalse(simulation.contains("DatabaseService"));
        assertFalse(simulation.contains("OpeningService"));
        assertFalse(simulation.contains("ClaimService"));
        assertFalse(simulation.contains("Milestone"));
        assertFalse(simulation.contains("Reroll"));
        assertFalse(simulation.contains("dispatchCommand"));
        assertFalse(simulation.contains("addItem("));
        assertTrue(simulation.contains("MAX_SAMPLES = 100_000"));
        assertTrue(simulation.contains("ArrayBlockingQueue"));
        assertTrue(simulation.contains("ChanceAllocator.normalize"));
        assertTrue(simulation.contains("ChanceAllocator.selectTicket"));
    }

    @Test
    void editorIntegrationIsAdditiveAndRejectsStaleAsyncOutput() throws Exception {
        String bootstrap = source("PlexonCratesPremium.java");
        String listener = source("gui/SimulationAdminListener.java");
        assertTrue(bootstrap.contains("extends PlexonCrates"));
        assertTrue(bootstrap.contains("new SimulationAdminListener"));
        assertTrue(listener.contains("holder.action") == false);
        assertTrue(listener.contains("simulateAsync(snapshot"));
        assertTrue(listener.contains("CrateSimulationService.isCurrent"));
        assertTrue(listener.contains("Simulation result discarded: the crate revision changed"));
        assertTrue(listener.contains("reward.displayCopy()"));
        assertFalse(listener.contains("plugin.openings()."));
        assertFalse(listener.contains("plugin.database()."));
        assertFalse(listener.contains("plugin.claims()."));
        assertFalse(listener.contains("plugin.milestoneProgress()."));
    }

    @Test
    void distributionVerificationTargetsTheRcArtifactAndNonShadedContracts() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/build.yml"));
        assertTrue(workflow.contains("PlexonCrates-5.0.0-rc.1.jar"));
        assertTrue(workflow.contains("major version: 69"));
        assertTrue(workflow.contains("com/zpkdxgames/plexoncore/"));
        assertTrue(workflow.contains("com/antondev/keys/"));
        assertTrue(workflow.contains("sha256sum --check SHA256SUMS.txt"));
    }
}

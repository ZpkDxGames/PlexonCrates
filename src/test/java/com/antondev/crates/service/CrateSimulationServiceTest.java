package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrateSimulationServiceTest {
    @Test
    void deterministicSimulationMatchesExpectedDistributionWithinBroadTolerance() {
        try (CrateSimulationService service = new CrateSimulationService()) {
            var snapshot = snapshot(CrateSimulationService.Mode.CONFIGURED, List.of(
                    new CrateSimulationService.Probability("common", 7_000, true, true),
                    new CrateSimulationService.Probability("rare", 3_000, true, true)));

            var first = service.simulate(snapshot, 50_000, 123456789L);
            var second = service.simulate(snapshot, 50_000, 123456789L);
            assertEquals(first.outcomes(), second.outcomes());
            assertEquals(first.drySelection(), second.drySelection());

            var common = first.outcomes().stream().filter(value -> value.id().equals("common")).findFirst().orElseThrow();
            var rare = first.outcomes().stream().filter(value -> value.id().equals("rare")).findFirst().orElseThrow();
            assertTrue(Math.abs(common.observedPercent() - 70.0) < 1.0);
            assertTrue(Math.abs(rare.observedPercent() - 30.0) < 1.0);
            assertEquals(7_000, common.expectedBasisPoints());
            assertEquals(3_000, rare.expectedBasisPoints());
        }
    }

    @Test
    void sampleCountIsHardBounded() {
        try (CrateSimulationService service = new CrateSimulationService()) {
            var snapshot = snapshot(CrateSimulationService.Mode.CONFIGURED, List.of(
                    new CrateSimulationService.Probability("only", 10_000, true, true)));
            assertThrows(IllegalArgumentException.class, () -> service.simulate(snapshot, 0, 1L));
            assertThrows(IllegalArgumentException.class,
                    () -> service.simulate(snapshot, CrateSimulationService.MAX_SAMPLES + 1, 1L));
        }
    }

    @Test
    void disabledAndPlayerIneligibleRewardsAreExcludedByMode() {
        try (CrateSimulationService service = new CrateSimulationService()) {
            var probabilities = List.of(
                    new CrateSimulationService.Probability("eligible", 5_000, true, true),
                    new CrateSimulationService.Probability("restricted", 3_000, true, false),
                    new CrateSimulationService.Probability("disabled", 2_000, false, true));

            var configured = service.simulate(snapshot(CrateSimulationService.Mode.CONFIGURED, probabilities), 2_000, 99L);
            var disabledConfigured = configured.outcomes().stream()
                    .filter(value -> value.id().equals("disabled")).findFirst().orElseThrow();
            var restrictedConfigured = configured.outcomes().stream()
                    .filter(value -> value.id().equals("restricted")).findFirst().orElseThrow();
            assertEquals(0, disabledConfigured.expectedBasisPoints());
            assertTrue(restrictedConfigured.expectedBasisPoints() > 0);

            var player = service.simulate(snapshot(CrateSimulationService.Mode.PLAYER_CONTEXT, probabilities), 2_000, 99L);
            var restrictedPlayer = player.outcomes().stream()
                    .filter(value -> value.id().equals("restricted")).findFirst().orElseThrow();
            var eligiblePlayer = player.outcomes().stream()
                    .filter(value -> value.id().equals("eligible")).findFirst().orElseThrow();
            assertEquals(0, restrictedPlayer.expectedBasisPoints());
            assertEquals(10_000, eligiblePlayer.expectedBasisPoints());
        }
    }

    @Test
    void snapshotIsImmutableAndRevisionGateRejectsStaleOutput() {
        var mutableRewards = new ArrayList<CrateSimulationService.Probability>();
        mutableRewards.add(new CrateSimulationService.Probability("safe", 10_000, true, true));
        var mutableIssues = new ArrayList<String>();
        var snapshot = new CrateSimulationService.Snapshot("basic", 7,
                CrateSimulationService.Mode.CONFIGURED, mutableRewards, mutableIssues, "configured");
        mutableRewards.clear();
        mutableIssues.add("late mutation");

        assertEquals(1, snapshot.rewards().size());
        assertTrue(snapshot.publicationIssues().isEmpty());
        assertTrue(CrateSimulationService.isCurrent(snapshot, 7));
        assertFalse(CrateSimulationService.isCurrent(snapshot, 8));
    }

    @Test
    void asyncPathReturnsTheSamePureAnalyticalReportShape() {
        try (CrateSimulationService service = new CrateSimulationService()) {
            var snapshot = snapshot(CrateSimulationService.Mode.CONFIGURED, List.of(
                    new CrateSimulationService.Probability("one", 2_500, true, true),
                    new CrateSimulationService.Probability("two", 7_500, true, true)));
            var report = service.simulateAsync(snapshot, 10_000, 77L).join();
            assertEquals(10_000, report.samples());
            assertEquals(2, report.outcomes().size());
            assertEquals(10_000, report.outcomes().stream().mapToInt(CrateSimulationService.Outcome::expectedBasisPoints).sum());
        }
    }

    private static CrateSimulationService.Snapshot snapshot(
            CrateSimulationService.Mode mode, List<CrateSimulationService.Probability> rewards) {
        return new CrateSimulationService.Snapshot("basic", 4, mode, rewards, List.of(), "test");
    }
}

package com.antondev.crates.migration.phoenix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PhoenixMigrationServiceTest {
    @TempDir
    Path temp;

    @Test
    void scanFingerprintsSourceWithoutModifyingItAndPlanFailsClosed() throws Exception {
        PhoenixMigrationService service = new PhoenixMigrationService(temp.resolve("PlexonCrates"));
        Path fixture = service.sourceRoot().resolve("crates.yml");
        Files.createDirectories(fixture.getParent());
        byte[] original = "operator-owned-phoenix-fixture\n".getBytes(StandardCharsets.UTF_8);
        Files.write(fixture, original);
        long modifiedBefore = Files.getLastModifiedTime(fixture).toMillis();

        PhoenixMigrationService.ScanResult first = service.scan();
        PhoenixMigrationService.ScanResult second = service.scan();

        assertEquals(1, first.files().size());
        assertEquals("crates.yml", first.files().getFirst().relativePath());
        assertEquals(64, first.files().getFirst().sha256().length());
        assertEquals(first.fingerprint(), second.fingerprint());
        assertArrayEquals(original, Files.readAllBytes(fixture));
        assertEquals(modifiedBefore, Files.getLastModifiedTime(fixture).toMillis());

        PhoenixMigrationService.PlanResult plan = service.plan(first);
        assertFalse(plan.importEnabled());
        assertEquals(PhoenixMigrationService.MigrationStatus.MANUAL_REVIEW,
                plan.entries().getFirst().status());
        assertFalse(service.validate(plan).validForImport());
        assertThrows(IllegalStateException.class, () -> service.importToDrafts(plan));

        Path report = service.writeReport(first, plan);
        assertTrue(report.startsWith(service.reportRoot()));
        assertFalse(report.startsWith(service.sourceRoot()));
        assertTrue(Files.readString(report).contains("No crate definitions were imported"));
        assertArrayEquals(original, Files.readAllBytes(fixture));
    }

    @Test
    void emptySourceIsSafeAndStillProducesAStableFingerprint() throws Exception {
        PhoenixMigrationService service = new PhoenixMigrationService(temp.resolve("empty"));
        PhoenixMigrationService.ScanResult scan = service.scan();

        assertTrue(scan.files().isEmpty());
        assertEquals(64, scan.fingerprint().length());
        assertFalse(scan.warnings().isEmpty());
    }
}

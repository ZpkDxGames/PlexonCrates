package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleasePromotionGateArchitectureTest {
    private static final Path BUILD = Path.of(".github/workflows/build.yml");
    private static final Path PRERELEASE = Path.of(".github/workflows/prerelease.yml");
    private static final Path RELEASE = Path.of(".github/workflows/release.yml");

    @Test
    void buildProvenanceUsesCurrentStableRollbackBoundaryAndProvesAncestry() throws Exception {
        String source = Files.readString(BUILD);
        assertTrue(source.contains("ROLLBACK_TAG: 'v5.0.0'"));
        assertTrue(source.contains("ROLLBACK_SHA: 'f24e3f7f942f7c886352e71c7105af499828096f'"));
        assertTrue(source.contains("test \"$(git rev-list -n 1 \"$ROLLBACK_TAG\")\" = \"$ROLLBACK_SHA\""));
        assertTrue(source.contains("git merge-base --is-ancestor \"$ROLLBACK_SHA\" HEAD"));
    }

    @Test
    void prereleasePublisherRequiresMatchingRcBranchVersionAndImmutableTag() throws Exception {
        String source = Files.readString(PRERELEASE);
        assertTrue(source.contains("'release/*-rc.*'"));
        assertTrue(source.contains("Prerelease version must contain an -rc.* suffix."));
        assertTrue(source.contains("test \"$GITHUB_REF_NAME\" = \"release/$version\""));
        assertTrue(source.contains("ROLLBACK_TAG: 'v5.0.0'"));
        assertTrue(source.contains("ROLLBACK_SHA: 'f24e3f7f942f7c886352e71c7105af499828096f'"));
        assertTrue(source.contains("git merge-base --is-ancestor \"$ROLLBACK_SHA\" HEAD"));
        assertTrue(source.contains("already exists; refusing to move a prerelease tag"));
        assertTrue(source.contains("already exists; refusing replacement"));
    }

    @Test
    void prereleasePublisherRebuildsVerifiesPublishesAndRedownloadsExactArtifact() throws Exception {
        String source = Files.readString(PRERELEASE);
        assertTrue(source.contains("mvn -B -ntp clean verify"));
        assertTrue(source.contains("PlexonCrates-${PLUGIN_VERSION}.jar"));
        assertTrue(source.contains("sha256sum --check SHA256SUMS.txt"));
        assertTrue(source.contains("echo 'runtime_certification=PENDING'"));
        assertTrue(source.contains("gh release create \"$RELEASE_TAG\""));
        assertTrue(source.contains("--prerelease"));
        assertTrue(source.contains("gh release download \"$RELEASE_TAG\""));
        assertTrue(source.contains(".isPrerelease == true"));
        assertTrue(source.contains("grep -Fx 'runtime_certification=PENDING' published/PROVENANCE.txt"));
    }

    @Test
    void stablePublisherRequiresExactFinalVersionMainBoundaryAndAcceptedGuiBase() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("test \"$version\" = '6.5.0'"));
        assertTrue(source.contains("test \"$GITHUB_SHA\" = \"$main_sha\""));
        assertTrue(source.contains("GUI_BASE_TAG: 'v6.0.0-rc.1'"));
        assertTrue(source.contains("GUI_BASE_SHA: 'cb3ba1aed8ab2b89bdbaec39271ad30ee07e1c28'"));
        assertTrue(source.contains("git merge-base --is-ancestor \"$GUI_BASE_SHA\" HEAD"));
        assertTrue(source.contains("git merge-base --is-ancestor \"$GUI_SOURCE_EQUIVALENT_HEAD\" HEAD"));
    }

    @Test
    void stablePublisherRejectsAuthorityOrOutOfScopeDrift() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("git diff --name-only \"$GUI_BASE_SHA\"..HEAD"));
        assertTrue(source.contains("6.5 GUI-only stable scope violation"));
        assertTrue(source.contains("6.5 GUI-only stable authority changed"));
        assertTrue(source.contains("src/main/java/com/antondev/crates/service/OpeningService.java"));
        assertTrue(source.contains("src/main/java/com/antondev/crates/service/ClaimService.java"));
        assertTrue(source.contains("src/main/java/com/antondev/crates/database/DatabaseService.java"));
        assertTrue(source.contains("src/main/java/com/antondev/crates/item/ItemSnapshotCodec.java"));
    }

    @Test
    void stablePublisherRebuildsPublishesAndRedownloadsExactArtifact() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("mvn -B -ntp clean verify"));
        assertTrue(source.contains("PlexonCrates-${PLUGIN_VERSION}.jar"));
        assertTrue(source.contains("sha256sum --check SHA256SUMS.txt"));
        assertTrue(source.contains("gh release create \"$RELEASE_TAG\""));
        assertTrue(source.contains("--latest"));
        assertTrue(source.contains("gh release download \"$RELEASE_TAG\""));
        assertTrue(source.contains(".isPrerelease == false"));
    }

    @Test
    void stableProvenanceTruthfullyRecordsGuiOnlyVerificationWithoutInventingRuntimePass() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("echo 'release_kind=STABLE'"));
        assertTrue(source.contains("echo \"gui_base_tag=$GUI_BASE_TAG\""));
        assertTrue(source.contains("echo \"gui_base_sha=$GUI_BASE_SHA\""));
        assertTrue(source.contains("echo 'runtime_certification=NOT_EXECUTED_GUI_ONLY_RELEASE'"));
        assertTrue(source.contains("grep -Fx 'runtime_certification=NOT_EXECUTED_GUI_ONLY_RELEASE' published/PROVENANCE.txt"));
        assertFalse(source.contains("RUNTIME_CERTIFICATION=PASS"));
        assertFalse(source.contains("runtime_certification=PASS"));
        assertFalse(source.contains("CERTIFIED_RC_TAG"));
    }
}

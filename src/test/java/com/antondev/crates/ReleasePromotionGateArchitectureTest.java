package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReleasePromotionGateArchitectureTest {
    private static final Path BUILD = Path.of(".github/workflows/build.yml");
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
    void stablePublisherCannotRunForPrereleaseVersionOrWithoutRuntimePassEvidence() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("Stable version must not contain a prerelease suffix."));
        assertTrue(source.contains("releases/${PLUGIN_VERSION}.runtime-certification.env"));
        assertTrue(source.contains("grep -Fx 'RUNTIME_CERTIFICATION=PASS' \"$runtime_file\""));
        assertTrue(source.contains("grep -Fx 'PAPER_VERSION=26.2.build.121-stable' \"$runtime_file\""));
        assertTrue(source.contains("CERTIFIED_RC_TAG="));
        assertTrue(source.contains("CERTIFIED_RC_SHA="));
        assertTrue(source.contains("CERTIFIED_RC_JAR_SHA256="));
    }

    @Test
    void stablePublisherVerifiesPublishedRcIdentityAndExactBinaryDigest() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("git rev-list -n 1 \"$certified_rc_tag\""));
        assertTrue(source.contains("git merge-base --is-ancestor \"$certified_rc_sha\" HEAD"));
        assertTrue(source.contains("gh release view \"$certified_rc_tag\""));
        assertTrue(source.contains(".isPrerelease == true"));
        assertTrue(source.contains("gh release download \"$certified_rc_tag\""));
        assertTrue(source.contains("certified_rc_jar_sha256"));
        assertTrue(source.contains("sha256sum --check"));
    }

    @Test
    void stablePublisherRejectsProductionCodeDriftAfterCertifiedRc() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("git diff --name-only \"$certified_rc_sha\"..HEAD"));
        assertTrue(source.contains("pom.xml|\"releases/${PLUGIN_VERSION}.md\"|\"releases/${PLUGIN_VERSION}.runtime-certification.env\"|docs/REVAMP_5_1_IMPLEMENTATION_STATUS.md"));
        assertTrue(source.contains("Stable candidate changed non-promotion path after certified RC"));
    }

    @Test
    void stableProvenanceRecordsCertifiedRcAndRuntimePass() throws Exception {
        String source = Files.readString(RELEASE);
        assertTrue(source.contains("echo \"certified_rc_tag=$CERTIFIED_RC_TAG\""));
        assertTrue(source.contains("echo \"certified_rc_sha=$CERTIFIED_RC_SHA\""));
        assertTrue(source.contains("echo \"certified_rc_jar_sha256=$CERTIFIED_RC_JAR_SHA256\""));
        assertTrue(source.contains("echo 'runtime_certification=PASS'"));
        assertFalse(source.contains("echo 'runtime_certification=NOT_EXECUTED'"));
        assertTrue(source.contains("grep -Fx 'runtime_certification=PASS' published/PROVENANCE.txt"));
    }
}

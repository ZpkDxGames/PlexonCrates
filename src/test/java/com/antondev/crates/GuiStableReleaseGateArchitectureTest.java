package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiStableReleaseGateArchitectureTest {
    @Test
    void stablePublisherIsFailClosedAndNeverInventsRuntimeCertification() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/release.yml"));
        assertTrue(workflow.contains("GUI_BASE_TAG: 'v6.0.0-rc.1'"));
        assertTrue(workflow.contains("GUI_BASE_SHA: 'cb3ba1aed8ab2b89bdbaec39271ad30ee07e1c28'"));
        assertTrue(workflow.contains("6.5 GUI-only stable scope violation"));
        assertTrue(workflow.contains("6.5 GUI-only stable authority changed"));
        assertTrue(workflow.contains("src/main/java/com/antondev/crates/service/OpeningService.java"));
        assertTrue(workflow.contains("src/main/java/com/antondev/crates/database/DatabaseService.java"));
        assertTrue(workflow.contains("runtime_certification=NOT_EXECUTED_GUI_ONLY_RELEASE"));
        assertTrue(workflow.contains("gh release create \"$RELEASE_TAG\""));
        assertFalse(workflow.contains("RUNTIME_CERTIFICATION=PASS"));
        assertFalse(workflow.contains("CERTIFIED_RC_TAG"));
        assertFalse(workflow.contains("runtime_certification=PASS"));
    }
}

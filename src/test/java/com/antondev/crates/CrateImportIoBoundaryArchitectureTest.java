package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CrateImportIoBoundaryArchitectureTest {
    private static final Path REGISTRY = Path.of(
            "src/main/java/com/antondev/crates/service/CrateRegistry.java");

    @Test
    void preparationDoesNotTouchFilesystem() throws Exception {
        String source = Files.readString(REGISTRY);
        String prepare = section(source, "public PreparedDraftImport prepareImportedDraft(",
                "public void writeImportedDraft(");
        assertTrue(prepare.contains("yaml.loadFromString"));
        assertTrue(prepare.contains("return new PreparedDraftImport"));
        assertFalse(prepare.contains("Files."));
        assertFalse(prepare.contains("AtomicFiles."));
    }

    @Test
    void mirrorWriteIsIsolatedFromRegistryActivation() throws Exception {
        String source = Files.readString(REGISTRY);
        String write = section(source, "public void writeImportedDraft(",
                "public Crate installImportedDraft(");
        String install = section(source, "public Crate installImportedDraft(",
                "public Crate importAsDraft(");
        assertTrue(write.contains("AtomicFiles.write"));
        assertFalse(write.contains("fireChange("));
        assertTrue(install.contains("install(id, prepared.file(), prepared.crate())"));
        assertTrue(install.contains("fireChange("));
        assertFalse(install.contains("Files."));
        assertFalse(install.contains("AtomicFiles."));
    }

    @Test
    void compatibilityImportComposesTheThreeStages() throws Exception {
        String source = Files.readString(REGISTRY);
        String compatibility = section(source, "public Crate importAsDraft(",
                "public Path exportDefinition(");
        assertTrue(compatibility.contains("prepareImportedDraft("));
        assertTrue(compatibility.contains("writeImportedDraft(prepared)"));
        assertTrue(compatibility.contains("installImportedDraft(prepared)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

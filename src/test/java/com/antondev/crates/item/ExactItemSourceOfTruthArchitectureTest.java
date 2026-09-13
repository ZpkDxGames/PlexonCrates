package com.antondev.crates.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExactItemSourceOfTruthArchitectureTest {
    private static final Path CODEC = Path.of("src/main/java/com/antondev/crates/item/ItemSnapshotCodec.java");
    private static final Path INSPECTOR = Path.of("src/main/java/com/antondev/crates/item/ExactItemInspector.java");

    @Test
    void nativePaperBytesRemainCanonical() throws Exception {
        String codec = Files.readString(CODEC);
        assertTrue(codec.contains("serializeAsBytes()"));
        assertTrue(codec.contains("ItemStack.deserializeBytes"));
        assertTrue(codec.contains("sha256(bytes)"));
        assertFalse(codec.contains("new ItemStack(Material"));
        assertFalse(codec.contains("matchMaterial(snapshot"));
    }

    @Test
    void diagnosticsDelegateToExactSnapshotInsteadOfRebuildingItems() throws Exception {
        String inspector = Files.readString(INSPECTOR);
        assertTrue(inspector.contains("codec.capture(source)"));
        assertTrue(inspector.contains("codec.restoreTemplate(snapshot)"));
        assertFalse(inspector.contains("new ItemStack"));
        assertFalse(inspector.contains("Material.matchMaterial"));
    }
}

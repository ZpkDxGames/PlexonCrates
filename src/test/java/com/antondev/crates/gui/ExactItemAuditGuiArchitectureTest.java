package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExactItemAuditGuiArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/antondev/crates/gui/SimulationAdminListener.java");

    @Test
    void testLabExposesExactItemAuditAsAReadOnlySurface() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("Exact Item Audit"));
        assertTrue(source.contains("exactItems.inspect(held)"));
        assertTrue(source.contains("ItemStack exactDisplay = held.clone()"));
        assertTrue(source.contains("inventory.setItem(13, exactDisplay)"));
        assertTrue(source.contains("diagnostics.shortFingerprint()"));
        assertTrue(source.contains("diagnostics.serializedBytes()"));
        assertTrue(source.contains("diagnostics.customDataPresent()"));
        assertTrue(source.contains("diagnostics.containerContentsPresent()"));
    }

    @Test
    void exactCloneIsNotDecoratedWithPlexonLore() throws Exception {
        String source = Files.readString(SOURCE);
        String method = section(source, "private void openExactItemAudit", "private void runDry");
        assertFalse(method.contains("appendLore(exactDisplay"));
        assertFalse(method.contains("exactDisplay.editMeta"));
        assertFalse(method.contains("exactDisplay.setItemMeta"));
        assertTrue(method.indexOf("inventory.setItem(13, exactDisplay)")
                < method.indexOf("player.openInventory(inventory)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate " + start);
        return source.substring(from, to);
    }
}

package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiExactInputSafetyTest {
    @Test void chromeDoesNotBindActionsOrCanonicalizeItems() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/antondev/crates/gui/GuiChromeRenderer.java"));
        assertFalse(source.contains("holder.bind("));
        assertFalse(source.contains("PersistentDataContainer"));
        assertTrue(source.contains("protectedInputSlots"));
        assertTrue(source.contains("inventory.setItem(slot, null)"));
    }
}

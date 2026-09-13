package com.antondev.crates.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ExactItemInspectorTest {
    private ExactItemInspector inspector;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        inspector = new ExactItemInspector();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void diagnosticsComeFromNativeSnapshotWithoutMutatingSource() {
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD, 1);
        source.editMeta(meta -> {
            meta.displayName(Component.text("Foreign exact item"));
            meta.lore(List.of(Component.text("Do not normalize"), Component.text("Unknown plugin data stays")));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(new NamespacedKey("foreign_plugin", "identity"),
                    PersistentDataType.STRING, "synthetic:blade");
        });
        byte[] before = source.serializeAsBytes();

        ExactItemInspector.Diagnostics diagnostics = inspector.inspect(source);

        assertArrayEquals(before, source.serializeAsBytes());
        assertEquals(1, diagnostics.capturedAmount());
        assertTrue(diagnostics.serializedBytes() > 0);
        assertEquals(64, diagnostics.sha256().length());
        assertEquals(12, diagnostics.shortFingerprint().length());
        assertTrue(diagnostics.customDataPresent());
        assertFalse(diagnostics.containerContentsPresent());
        assertTrue(diagnostics.maximumStackSize() >= 1);
    }

    @Test
    void quantityIsDiagnosticMetadataNotARewriteOfTemplateIdentity() {
        ItemStack source = new ItemStack(Material.DIAMOND, 37);
        source.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey("itemsadder_like", "id"), PersistentDataType.STRING, "crystal"));

        ExactItemInspector.Diagnostics diagnostics = inspector.inspect(source);

        assertEquals(37, source.getAmount());
        assertEquals(37, diagnostics.capturedAmount());
        assertTrue(diagnostics.customDataPresent());
    }
}

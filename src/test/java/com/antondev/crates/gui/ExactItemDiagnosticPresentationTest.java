package com.antondev.crates.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ExactItemDiagnosticPresentationTest {
    private ExactItemDiagnosticPresentation presentation;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        presentation = new ExactItemDiagnosticPresentation();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void singleDiagnosticsNeverMutateExactSource() {
        ItemStack source = new ItemStack(Material.DIAMOND_PICKAXE);
        source.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey("third_party", "tool_id"), PersistentDataType.STRING, "legendary"));
        byte[] before = source.serializeAsBytes();

        List<String> lines = presentation.single(source, "Resolved exact key template").stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList();

        assertArrayEquals(before, source.serializeAsBytes());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("SHA-256: ")));
        assertTrue(lines.stream().anyMatch(line -> line.equals("Custom data: yes")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Native bytes: ")));
    }

    @Test
    void bundleIsBoundedAndLeavesEverySourceUntouched() {
        ItemStack first = new ItemStack(Material.DIAMOND, 7);
        ItemStack second = new ItemStack(Material.EMERALD, 9);
        ItemStack third = new ItemStack(Material.GOLD_INGOT, 11);
        ItemStack fourth = new ItemStack(Material.IRON_INGOT, 13);
        List<ItemStack> items = List.of(first, second, third, fourth);
        List<byte[]> before = items.stream().map(ItemStack::serializeAsBytes).toList();

        List<String> lines = presentation.bundle(items).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList();

        assertEquals("Stacks: 4", lines.get(1));
        assertTrue(lines.stream().anyMatch(line -> line.equals("+1 more exact stack template(s)")));
        for (int index = 0; index < items.size(); index++) {
            assertArrayEquals(before.get(index), items.get(index).serializeAsBytes());
        }
    }
}

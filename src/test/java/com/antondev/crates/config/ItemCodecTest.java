package com.antondev.crates.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemCodecTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void exactBase64SnapshotIgnoresCosmeticAndEnchantmentOverlays() {
        ItemStack source = new ItemStack(Material.DIAMOND, 7);
        source.editMeta(meta -> {
            meta.displayName(Component.text("Foreign custom ingredient"));
            meta.lore(List.of(Component.text("Identity-bearing item")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(
                    new NamespacedKey("foreign_plugin", "identity"),
                    PersistentDataType.STRING,
                    "compressed_oak_equivalent");
        });

        ItemStack canonical = source.clone();
        canonical.setAmount(1);
        String encoded = ItemCodec.capture(source, true);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item.base64", encoded);
        yaml.set("item.name", "<red>DO NOT APPLY</red>");
        yaml.set("item.lore", List.of("<red>DO NOT APPLY</red>"));
        yaml.set("item.glow", false);
        yaml.set("item.unbreakable", true);
        yaml.set("item.enchantments.minecraft:unbreaking", 10);

        ItemStack restored = ItemCodec.read(yaml.getConfigurationSection("item"));

        assertEquals(1, restored.getAmount());
        assertArrayEquals(canonical.serializeAsBytes(), restored.serializeAsBytes());
    }

    @Test
    void exactCaptureDoesNotConsumeOrMutateTheSourceStack() {
        ItemStack source = new ItemStack(Material.EMERALD, 19);
        source.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey("foreign_plugin", "opaque"), PersistentDataType.INTEGER, 42));
        byte[] before = source.serializeAsBytes();

        ItemCodec.capture(source, true);

        assertEquals(19, source.getAmount());
        assertArrayEquals(before, source.serializeAsBytes());
    }
}

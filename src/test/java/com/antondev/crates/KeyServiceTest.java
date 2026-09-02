package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.config.ItemCodec;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;
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
import org.mockbukkit.mockbukkit.ServerMock;

class KeyServiceTest {
    private ServerMock server;
    private PlexonCrates plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
        plugin = MockBukkit.load(PlexonCrates.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void exactMatchingIgnoresAmountButRejectsAnyPdcDifference() {
        ItemStack exact = plugin.keys().template("basic").orElseThrow();
        exact.setAmount(exact.getMaxStackSize());
        assertTrue(plugin.keys().matches(exact, "basic"));

        ItemStack forged = exact.clone();
        forged.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "forged"), PersistentDataType.STRING, "different"));

        assertFalse(plugin.keys().matches(forged, "basic"));
    }

    @Test
    void deterministicConsumptionUsesStorageSlotsInAscendingOrder() {
        var player = server.addPlayer("KeyHolder");
        ItemStack first = plugin.keys().template("basic").orElseThrow();
        ItemStack second = first.clone();
        first.setAmount(2);
        second.setAmount(3);
        player.getInventory().setItem(0, first);
        player.getInventory().setItem(5, second);

        assertEquals(5, plugin.keys().count(player, "basic"));
        assertTrue(plugin.keys().consume(player, "basic", 4));

        assertNull(player.getInventory().getItem(0));
        assertEquals(1, player.getInventory().getItem(5).getAmount());
        assertEquals(1, plugin.keys().count(player, "basic"));
    }

    @Test
    void failedConsumptionNeverMutatesAnyStack() {
        var player = server.addPlayer("KeyHolder");
        ItemStack key = plugin.keys().template("rare").orElseThrow();
        key.setAmount(2);
        player.getInventory().setItem(3, key);

        assertFalse(plugin.keys().consume(player, "rare", 3));

        assertEquals(2, player.getInventory().getItem(3).getAmount());
    }

    @Test
    void collisionDetectionIncludesLegacyTemplates() throws Exception {
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        ItemStack emerald = new ItemStack(Material.EMERALD);
        ItemStack stone = new ItemStack(Material.STONE);
        plugin.keys().createCaptured("legacy_owner", Component.text("Legacy owner"), diamond, "TEST");
        plugin.keys().replaceCaptured("legacy_owner", Component.text("Legacy owner"), stone, true, "TEST");
        plugin.keys().createCaptured("current_owner", Component.text("Current owner"), emerald, "TEST");
        plugin.keys().replaceCaptured("current_owner", Component.text("Current owner"), diamond, false, "TEST");

        assertEquals(Set.of("legacy_owner", "current_owner"), plugin.keys().collisions());
    }

    @Test
    void rotatingACapturedKeyCanRetainTheOldExactTemplate() throws Exception {
        ItemStack oldTemplate = new ItemStack(Material.PRISMARINE_SHARD, 17);
        oldTemplate.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "generation"), PersistentDataType.INTEGER, 1));
        ItemStack replacement = new ItemStack(Material.ECHO_SHARD, 9);
        replacement.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "generation"), PersistentDataType.INTEGER, 2));
        plugin.keys().createCaptured("rotating", Component.text("Rotating"), oldTemplate, "TEST");

        plugin.keys().replaceCaptured("rotating", Component.text("Rotating"), replacement, true, "TEST");

        assertTrue(plugin.keys().matches(oldTemplate, "rotating"));
        assertTrue(plugin.keys().matches(replacement, "rotating"));
        assertEquals(1, plugin.keys().template("rotating").orElseThrow().getAmount());
    }

    @Test
    void validatedKeyRegistryImportPreservesAnExactTemplate() throws Exception {
        ItemStack template = new ItemStack(Material.AMETHYST_SHARD, 32);
        template.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "imported"), PersistentDataType.STRING, "exact"));
        YamlConfiguration yaml = new YamlConfiguration();
        String path = "keys.imported_key";
        yaml.set("config-version", 2);
        yaml.set(path + ".enabled", true);
        yaml.set(path + ".display-name", "<light_purple>Imported Key</light_purple>");
        yaml.set(path + ".source", "CAPTURED");
        yaml.set(path + ".external-id", "");
        yaml.set(path + ".match-mode", "EXACT");
        yaml.set(path + ".cache-last-known-good", false);
        yaml.set(path + ".item.base64", ItemCodec.capture(template, true));
        yaml.set(path + ".legacy-templates", java.util.List.of());
        yaml.set(path + ".created-at", Instant.parse("2026-09-01T00:00:00Z").toString());
        yaml.set(path + ".updated-at", Instant.parse("2026-09-01T00:00:00Z").toString());
        var source = plugin.getDataFolder().toPath().resolve("imports/imported-keys.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, yaml.saveToString());

        assertEquals(java.util.List.of("imported_key"), plugin.keys().importDefinitions(source, "TEST"));
        assertTrue(plugin.keys().matches(template, "imported_key"));

        ItemStack forged = template.clone();
        forged.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "imported"), PersistentDataType.STRING, "forged"));
        assertFalse(plugin.keys().matches(forged, "imported_key"));
    }
}

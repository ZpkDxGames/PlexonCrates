package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.crate.CrateState;
import java.nio.file.Files;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PluginIntegrationTest {
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
    void bundledConfigurationLoadsFourReadyCrates() {
        assertEquals(List.of("basic", "rare", "epic", "legendary"),
                plugin.crates().ordered().stream().map(crate -> crate.id()).toList());
        assertEquals(32, plugin.crates().rewardCount());
        assertEquals("exact cached/configured templates", plugin.keys().sourceLabel());
        for (var crate : plugin.crates().ordered()) {
            assertTrue(crate.enabled());
            assertEquals(100.0, crate.rewards().values().stream().mapToDouble(reward -> reward.weight()).sum(), 0.00001);
            assertTrue(plugin.keys().template(crate.keyId()).isPresent());
        }
        var shulkers = plugin.crates().find("epic").orElseThrow().rewards().get("shulker_pack").itemCopies();
        assertEquals(4, shulkers.stream().mapToInt(item -> item.getAmount()).sum());
        assertTrue(shulkers.stream().allMatch(item -> item.getAmount() <= item.getMaxStackSize()));
    }

    @Test
    void successfulOpenConsumesOneFallbackKeyBeforeAnimation() {
        var player = server.addPlayer("Tonim");
        player.setOp(false);
        World world = server.getWorld("Survival_World");
        assertTrue(world != null);
        player.teleport(world.getSpawnLocation());
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 3);
        assertEquals(3, plugin.keys().count(player, crate.keyId()));

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitOpeningCommit();

        assertEquals(2, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertFalse(plugin.openings().open(player, crate, 2, false));
        assertEquals(2, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
    }

    @Test
    void capturedRewardRoundTripsCompleteItemData() throws Exception {
        ItemStack original = new ItemStack(Material.DIAMOND, 7);
        original.editMeta(meta -> {
            meta.displayName(Component.text("External custom reward"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "capture_test"),
                    PersistentDataType.STRING, "preserved");
        });

        plugin.crates().addCapturedReward("basic", "external_reward", 10, original);

        var stored = plugin.crates().find("basic").orElseThrow().rewards().get("external_reward").itemCopies();
        assertEquals(1, stored.size());
        assertEquals(7, stored.getFirst().getAmount());
        assertTrue(stored.getFirst().isSimilar(original));
    }

    @Test
    void crateExportImportsAsAValidatedDraftWithoutChangingTheSource() throws Exception {
        var exportDirectory = plugin.getDataFolder().toPath().resolve("exports");
        var exported = plugin.crates().exportDefinition("basic", exportDirectory);

        assertTrue(Files.isRegularFile(exported));
        var imported = plugin.crates().importAsDraft(exported, "basic_copy", "TEST");

        assertEquals("basic_copy", imported.id());
        assertEquals(CrateState.DRAFT, imported.state());
        assertEquals(8, imported.rewards().size());
        assertEquals(CrateState.PUBLISHED, plugin.crates().find("basic").orElseThrow().state());
        assertThrows(IllegalArgumentException.class,
                () -> plugin.crates().importAsDraft(exported, "../unsafe", "TEST"));
    }

    private void awaitOpeningCommit() {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
    }
}

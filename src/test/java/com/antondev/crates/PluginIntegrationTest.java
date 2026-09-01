package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.World;
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
        assertEquals("keys.yml fallback", plugin.keys().sourceLabel());
        for (var crate : plugin.crates().ordered()) {
            assertTrue(crate.enabled());
            assertEquals(100.0, crate.rewards().values().stream().mapToDouble(reward -> reward.weight()).sum(), 0.00001);
            assertTrue(plugin.keys().template(crate.keyId()).isPresent());
        }
    }

    @Test
    void successfulOpenConsumesOneFallbackKeyBeforeAnimation() {
        var player = server.addPlayer("Tonim");
        player.setOp(false);
        World world = server.getWorld("Survival_World");
        assertTrue(world != null);
        player.teleport(world.getSpawnLocation());
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 1);
        assertEquals(1, plugin.keys().count(player, crate.keyId()));

        assertTrue(plugin.openings().open(player, crate, 1, false));

        assertEquals(0, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
    }
}

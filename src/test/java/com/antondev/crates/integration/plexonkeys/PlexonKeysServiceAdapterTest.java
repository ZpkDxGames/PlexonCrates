package com.antondev.crates.integration.plexonkeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.antondev.crates.PlexonCrates;
import com.antondev.keys.api.PlexonKeysAPI;
import com.antondev.keys.model.KeyTier;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

final class PlexonKeysServiceAdapterTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void providerPrefersStablePlexonKeys12ServiceAndReturnsExactDefensiveTemplates() {
        var keysPlugin = MockBukkit.createMockPlugin("PlexonKeys");
        PlexonKeysAPI api = mock(PlexonKeysAPI.class);
        when(api.isTierEnabled(any(KeyTier.class))).thenReturn(true);
        when(api.keyTemplate(KeyTier.BASIC)).thenReturn(Optional.of(named(Material.TRIPWIRE_HOOK, "basic")));
        when(api.keyTemplate(KeyTier.RARE)).thenReturn(Optional.of(named(Material.PRISMARINE_SHARD, "rare")));
        when(api.keyTemplate(KeyTier.EPIC)).thenReturn(Optional.of(named(Material.AMETHYST_SHARD, "epic")));
        when(api.keyTemplate(KeyTier.LEGENDARY)).thenReturn(Optional.of(named(Material.NETHER_STAR, "legendary")));
        server.getServicesManager().register(PlexonKeysAPI.class, api, keysPlugin, ServicePriority.Normal);

        PlexonCrates crates = MockBukkit.load(PlexonCrates.class);
        PlexonKeysKeyProvider provider = new PlexonKeysKeyProvider(crates, "PlexonKeys");
        var discovered = provider.discover();

        assertEquals(4, discovered.size());
        assertEquals(Material.TRIPWIRE_HOOK, discovered.get("basic").template().getType());
        assertEquals(Material.NETHER_STAR, discovered.get("legendary").template().getType());
        assertEquals(1, discovered.get("basic").template().getAmount());
        assertEquals("plexonkeys", discovered.get("epic").providerId());
        assertTrue(provider.diagnostic().contains("1.2 Bukkit service API"));
        assertFalse(discovered.get("rare").template().getItemMeta().displayName() == null);
    }

    private static ItemStack named(Material material, String id) {
        ItemStack item = new ItemStack(material, 17);
        var meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Exact " + id));
        meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("test", id),
                org.bukkit.persistence.PersistentDataType.STRING,
                "preserved");
        item.setItemMeta(meta);
        return item;
    }
}

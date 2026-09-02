package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.key.ResolvedKey;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PlexonKeysIntegrationTest {
    private ServerMock server;
    private FakePlexonKeys plexonKeys;
    private PlexonCrates plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
        PluginDescriptionFile description = new PluginDescriptionFile(
                "PlexonKeys", "1.1.0", FakePlexonKeys.class.getName());
        plexonKeys = MockBukkit.loadWith(FakePlexonKeys.class, description);
        ItemStack captured = new ItemStack(Material.TRIPWIRE_HOOK);
        captured.editMeta(meta -> {
            meta.displayName(Component.text("Captured PlexonKeys Basic"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plexonKeys, "captured_token"),
                    PersistentDataType.STRING, "server-secret-item-data");
        });
        plexonKeys.template("basic", captured);
        plugin = MockBukkit.load(PlexonCrates.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void liveCapturedPlexonKeysItemOpensWithoutRecaptureAndLookalikeFails() throws Exception {
        assertEquals(ResolvedKey.ResolutionSource.LIVE, plugin.keys().resolve("basic").orElseThrow().source());
        ItemStack live = plexonKeys.template("basic");
        ItemStack lookalike = live.clone();
        lookalike.editMeta(meta -> meta.getPersistentDataContainer().remove(
                new NamespacedKey(plexonKeys, "captured_token")));
        assertTrue(plugin.keys().matches(live, "basic"));
        assertFalse(plugin.keys().matches(lookalike, "basic"));

        var player = server.addPlayer("LiveKeyUser");
        player.setOp(false);
        player.getInventory().addItem(live);
        assertTrue(plugin.openings().open(player, plugin.crates().find("basic").orElseThrow(), 1, false));
        awaitOpeningCommit();

        assertEquals(0, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
    }

    @Test
    void disabledProviderUsesTheLastKnownGoodExactTemplateBeforeFallback() {
        ItemStack live = plugin.keys().resolve("basic").orElseThrow().template();
        plugin.database().awaitIdle().join();

        server.getPluginManager().disablePlugin(plexonKeys);
        plugin.keys().syncDiscovery();

        var resolved = plugin.keys().resolve("basic").orElseThrow();
        assertEquals(ResolvedKey.ResolutionSource.LAST_KNOWN_GOOD, resolved.source());
        assertTrue(resolved.template().isSimilar(live));
    }

    private void awaitOpeningCommit() {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
    }

    public static class FakePlexonKeys extends JavaPlugin {
        private final Map<Category, Definition> categories = new LinkedHashMap<>();

        public FakeSettings settings() {
            return new FakeSettings(Map.copyOf(categories));
        }

        public void template(String id, ItemStack item) {
            categories.put(new Category(id), new Definition(item));
        }

        public ItemStack template(String id) {
            return categories.entrySet().stream().filter(entry -> entry.getKey().id().equals(id))
                    .findFirst().orElseThrow().getValue().itemCopy();
        }
    }

    public record FakeSettings(Map<Category, Definition> categories) {}
    public record Category(String id) {}

    public static final class Definition {
        private final ItemStack item;

        Definition(ItemStack item) {
            this.item = item.clone();
            this.item.setAmount(1);
        }

        public ItemStack itemCopy() {
            return item.clone();
        }
    }
}

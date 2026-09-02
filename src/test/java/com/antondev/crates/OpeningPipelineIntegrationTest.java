package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.api.event.CratePreOpenEvent;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class OpeningPipelineIntegrationTest {
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
    void cancelledPreOpenConsumesNoKeyAndCreatesNoHistory() throws Exception {
        var player = server.addPlayer("Cancelled");
        player.setOp(false);
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 1);
        server.getPluginManager().registerEvents(new Canceller(), plugin);

        assertFalse(plugin.openings().open(player, crate, 1, false));

        assertEquals(1, plugin.keys().count(player, crate.keyId()));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
        assertFalse(plugin.openings().isOpening(player.getUniqueId()));
    }

    @Test
    void fullInventoryFailureConsumesNoKey() throws Exception {
        plugin.crates().createDraft("capacity_test", "TEST");
        plugin.crates().setAcceptedKeys("capacity_test", List.of("basic"), 1, "TEST");
        plugin.crates().addCapturedReward("capacity_test", "diamond", 1, new ItemStack(Material.DIAMOND));
        plugin.crates().publish("capacity_test", plugin.keys(), "TEST");
        var crate = plugin.crates().find("capacity_test").orElseThrow();
        var player = server.addPlayer("FullInventory");
        player.setOp(false);

        ItemStack[] contents = new ItemStack[36];
        Arrays.setAll(contents, ignored -> new ItemStack(Material.COBBLESTONE, 64));
        contents[0] = plugin.keys().template("basic").orElseThrow();
        player.getInventory().setStorageContents(contents);
        setDropOverflow(false);

        assertFalse(plugin.openings().open(player, crate, 1, false));

        assertEquals(1, plugin.keys().count(player, "basic"));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
    }

    @Test
    void overlappingRequestsCommitExactlyOneOpening() throws Exception {
        var player = server.addPlayer("DoubleClick");
        player.setOp(false);
        var crate = plugin.crates().find("rare").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 5);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        assertFalse(plugin.openings().open(player, crate, 1, false));
        awaitOpeningCommit();

        assertEquals(4, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
    }

    @Test
    void keyBypassCannotAccidentallyMassOpen() throws Exception {
        var player = server.addPlayer("Operator");
        player.setOp(true);
        var crate = plugin.crates().find("epic").orElseThrow();

        assertTrue(plugin.openings().open(player, crate, 10, false));
        awaitOpeningCommit();

        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(1, history.getFirst().openingCount());
        assertEquals(0, history.getFirst().keyAmount());
    }

    @Test
    void draftCannotPublishWithAnEmptyPoolOrUnresolvedKey() throws Exception {
        plugin.crates().createDraft("unfinished", "TEST");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> plugin.crates().publish("unfinished", plugin.keys(), "TEST"));

        assertTrue(error.getMessage().contains("Select at least one key"));
        assertTrue(error.getMessage().contains("enabled deliverable reward"));
        assertFalse(plugin.crates().find("unfinished").orElseThrow().enabled());
    }

    private void setDropOverflow(boolean enabled) throws Exception {
        var file = new java.io.File(plugin.getDataFolder(), "config.yml");
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        config.set("settings.drop-overflow-items", enabled);
        config.save(file);
        assertTrue(plugin.reloadFor(server.getConsoleSender()));
    }

    private void awaitOpeningCommit() {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
    }

    private static final class Canceller implements Listener {
        @EventHandler
        public void cancel(CratePreOpenEvent event) {
            event.setCancelled(true);
        }
    }
}

package com.antondev.crates.migration.phoenix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

final class PhoenixFixtureAdapterTest {
    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void parsesObservedPhoenixSchemaWithExactCustomItemsLocationsAndAggregateHistory() throws Exception {
        Path root = temp.resolve("PhoenixCratesLite");
        Files.createDirectories(root.resolve("crates"));
        Files.createDirectories(root.resolve("keys"));

        ItemStack exactKey = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemStack exactReward = new ItemStack(Material.DIAMOND, 3);
        ItemStack rewardDisplay = new ItemStack(Material.DIAMOND);

        YamlConfiguration key = new YamlConfiguration();
        key.set("identifier", "basic_key");
        key.set("enabled", true);
        key.set("item.material", "custom:key_item");
        key.set("internal-storage.items.key_item", exactKey);
        key.save(root.resolve("keys/basic_key.yml").toFile());

        YamlConfiguration crate = new YamlConfiguration();
        crate.set("identifier", "Basic_Crate");
        crate.set("enabled", true);
        crate.set("display-name", "&fBasic Crate");
        crate.set("item.material", "CHEST");
        crate.set("item.lore", java.util.List.of("&7Imported exact fixture"));
        crate.set("key-required", true);
        crate.set("linked-keys-ids", java.util.List.of("basic_key"));
        crate.set("open-cooldown", 0);
        crate.set("permission.required", false);
        crate.set("opening-animation.open", "CSGO_GUI");
        crate.set("hologram.lines", java.util.List.of("&fBasic Crate", "&7Open me"));
        crate.set("rewards.0.enabled", true);
        crate.set("rewards.0.identifier", "diamond_reward");
        crate.set("rewards.0.weight", 100.0D);
        crate.set("rewards.0.display-item.material", "custom:reward_display");
        crate.set("rewards.0.win-items.0.material", "custom:reward_item");
        crate.set("rewards.0.win-commands", java.util.List.of());
        crate.set("internal-storage.items.reward_display", rewardDisplay);
        crate.set("internal-storage.items.reward_item", exactReward);
        crate.save(root.resolve("crates/Basic_Crate.yml").toFile());

        YamlConfiguration locations = new YamlConfiguration();
        locations.set("locations.Basic_Crate", java.util.List.of("Survival_World;-10;70;4;NORTH"));
        locations.save(root.resolve("locations.yml").toFile());

        UUID player = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("database.db").toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE player_table (
                      Id INTEGER PRIMARY KEY AUTOINCREMENT,
                      UniqueId TEXT NOT NULL,
                      PlayerName TEXT NOT NULL,
                      OpenedCratesAmount TEXT NOT NULL,
                      OpenedPouchesAmount TEXT NOT NULL DEFAULT '{}',
                      CratesCooldown TEXT NOT NULL,
                      RewardsWinAmount TEXT NOT NULL,
                      RewardsCooldown TEXT NOT NULL,
                      VirtualKeys TEXT NOT NULL,
                      Rerolls TEXT NOT NULL
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO player_table(UniqueId, PlayerName, OpenedCratesAmount, CratesCooldown,
                      RewardsWinAmount, RewardsCooldown, VirtualKeys, Rerolls)
                    VALUES(?, ?, ?, '{}', ?, '{}', '{}', '{}')
                    """)) {
                insert.setString(1, player.toString());
                insert.setString(2, "FixturePlayer");
                insert.setString(3, "{\"Basic_Crate\":7}");
                insert.setString(4, "{\"diamond_reward\":6,\"retired_reward\":1}");
                insert.executeUpdate();
            }
            statement.executeUpdate("CREATE TABLE reward_global_stats (RewardId TEXT, Wins INTEGER)");
        }

        PhoenixFixtureAdapter.Fixture fixture = new PhoenixFixtureAdapter().load(root);

        assertEquals(1, fixture.crates().size());
        assertEquals("basic_crate", fixture.crates().getFirst().targetId());
        assertEquals(10_000, fixture.crates().getFirst().rewards().getFirst().basisPoints());
        assertTrue(fixture.crates().getFirst().rewards().getFirst().items().getFirst().isSimilar(exactReward));
        assertEquals(3, fixture.crates().getFirst().rewards().getFirst().items().getFirst().getAmount());
        assertEquals("basic", fixture.keys().get("basic_key").targetId());
        assertTrue(fixture.keys().get("basic_key").template().isSimilar(exactKey));
        assertEquals(1, fixture.locations().size());
        assertEquals("Survival_World", fixture.locations().getFirst().worldName());
        assertEquals(7L, fixture.players().getFirst().openedCrates().get("Basic_Crate"));
        assertEquals(6L, fixture.players().getFirst().rewardWins().get("diamond_reward"));
        assertEquals(1L, fixture.orphanRewardWins().get("retired_reward"));
    }
}

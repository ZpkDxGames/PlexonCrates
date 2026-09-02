package com.antondev.crates.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.KeyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class LegacyMigrationTest {
    @TempDir
    Path temporary;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void versionOneFilesLocationsAndStatisticsMigrateExactlyOnce() throws Exception {
        Path data = temporary.resolve("PlexonCrates");
        Files.createDirectories(data.resolve("crates"));
        UUID player = UUID.randomUUID();
        writeLegacyFixtures(data, player);

        try (DatabaseService database = new DatabaseService(Logger.getLogger("LegacyMigrationTest"),
                data.resolve("data/plexoncrates.db"), 64)) {
            LegacyMigration.Result first = LegacyMigration.migrate(data, database);

            assertTrue(first.migrated());
            assertEquals(1, first.locations());
            assertEquals(9, first.openings());
            assertTrue(Files.isRegularFile(first.backupDirectory().resolve("config.yml")));
            assertTrue(Files.isRegularFile(first.backupDirectory().resolve("locations.yml")));
            assertEquals(2, YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile())
                    .getInt("config-version"));
            assertEquals("PLEXONKEYS", YamlConfiguration.loadConfiguration(data.resolve("keys.yml").toFile())
                    .getString("keys.basic.source"));
            assertTrue(KeyService.load(data.resolve("keys.yml")).definitions().containsKey("basic"));
            var crate = CrateRegistry.load(data.resolve("crates")).crates().get("basic");
            assertTrue(crate.enabled());
            assertEquals("say migrated", crate.rewards().get("legacy_reward").commands().getFirst());
            assertEquals(1, database.loadLocations().size());
            assertEquals(9L, database.loadStatistics().global().get("basic"));
            assertEquals(4L, database.loadStatistics().players().get(player).get("basic"));

            LegacyMigration.Result second = LegacyMigration.migrate(data, database);

            assertFalse(second.migrated());
            assertEquals(1, database.loadLocations().size());
            assertEquals(9L, database.loadStatistics().global().get("basic"));
            assertEquals(4L, database.loadStatistics().players().get(player).get("basic"));
        }
    }

    private static void writeLegacyFixtures(Path data, UUID player) throws Exception {
        Files.writeString(data.resolve("config.yml"), """
                config-version: 1
                settings:
                  enabled: true
                  worlds: []
                  excluded-worlds: []
                  drop-overflow-items: true
                  maximum-bulk-open: 64
                  statistics-save-seconds: 300
                plexonkeys:
                  enabled: true
                  plugin-name: PlexonKeys
                  mode: LIVE_FIRST
                  fallback-file: keys.yml
                interaction:
                  left-click-preview: true
                  right-click-open: true
                  sneak-right-click-bulk: true
                  cancel-vanilla-block-use: true
                  maximum-target-distance: 8
                opening:
                  animation-enabled: true
                  animation-duration-ticks: 54
                  animation-period-ticks: 2
                  opening-sound: minecraft:block.note_block.hat
                  finish-sound: minecraft:entity.player.levelup
                  sound-volume: 0.8
                  sound-pitch: 1.1
                holograms:
                  enabled: true
                  vertical-offset: 1.65
                  view-range: 32.0
                  line-width: 240
                  shadowed: true
                  see-through: false
                particles:
                  enabled: true
                  type: END_ROD
                  interval-ticks: 20
                  count: 3
                  horizontal-spread: 0.32
                  vertical-spread: 0.18
                  view-range: 32.0
                logging:
                  console: true
                  file: false
                  date-format: dd/MM/yyyy HH:mm:ss
                """);
        Files.writeString(data.resolve("keys.yml"), """
                config-version: 1
                keys:
                  basic:
                    material: TRIPWIRE_HOOK
                    name: <white>Basic Key</white>
                    lore: [<gray>Legacy exact key</gray>]
                    glow: true
                """);
        Files.writeString(data.resolve("crates/basic.yml"), """
                config-version: 1
                id: basic
                enabled: true
                display-name: <white>Basic Crate</white>
                key-id: basic
                permission: ''
                open-cooldown-seconds: 1
                icon: {material: CHEST, name: <white>Basic Crate</white>, lore: []}
                hologram:
                  lines: [<white>Basic Crate</white>]
                rewards:
                  legacy_reward:
                    enabled: true
                    display-name: <green>Legacy Reward</green>
                    weight: 1
                    items:
                      item: {material: DIAMOND, amount: 2}
                    commands: [/say migrated]
                """);
        Files.writeString(data.resolve("locations.yml"), """
                locations:
                  - world: Survival_World
                    x: 10
                    y: 70
                    z: -4
                    crate: basic
                """);
        Files.writeString(data.resolve("statistics.yml"), """
                global:
                  basic: 9
                players:
                  %s:
                    basic: 4
                """.formatted(player));
    }
}

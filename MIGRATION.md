# Migrating PlexonCrates 1.0.0 to 2.0.0

PlexonCrates 2.0 performs a one-time, reversible migration when it finds `config-version: 1`. It preserves crate/key IDs and exact item data, converts definitions to version 2, and imports mutable runtime data into SQLite.

## Before upgrading

1. Stop Paper cleanly.
2. Copy the entire `plugins/PlexonCrates/` directory to storage outside the server directory.
3. Keep the `PlexonCrates-1.0.0.jar` available for rollback.
4. Confirm the copied `config.yml`, `keys.yml`, every `crates/*.yml`, `locations.yml`, and `statistics.yml` are readable.
5. Replace only the plugin JAR with `PlexonCrates-2.0.0.jar`. Do not delete or pre-convert the data folder.
6. Verify the downloaded checksum before starting Paper.

Do not run two Paper instances against the same plugin directory during migration.

## What the first 2.0 start does

1. Creates `data/plexoncrates.db` and the version 2 schema.
2. Detects the version 1 configuration.
3. Creates `backups/migration-1.0.0-<UTC timestamp>/` containing the existing YAML tree.
4. Parses and validates all legacy configuration, keys, crates, locations, and statistics before activation.
5. Converts crate lifecycle/access/key/opening fields and adds default rarity, experience, money, limits, pity, and audit fields without changing reward weights or captured item payloads.
6. Converts legacy keys into live PlexonKeys-backed definitions with their exact version 1 items retained as fallbacks.
7. Removes a leading `/` from otherwise valid legacy console reward commands because 2.0 stores console commands without it.
8. Imports locations and global/player statistics into SQLite.
9. Commits the SQLite data, migration marker, and atomically written version 2 YAML as one migration boundary.
10. Loads and validates the complete 2.0 runtime snapshot.

If converted-file commit fails, the YAML is restored from the timestamped backup and the SQLite import plus marker are rolled back. The plugin then refuses to enable and logs the exact recovery path. A later retry cannot duplicate imported locations or statistics.

`locations.yml` and `statistics.yml` are not deleted. After success they remain legacy reference files and are also present in the migration backup; SQLite becomes authoritative.

## Field mapping

| 1.0 field/data | 2.0 destination |
|---|---|
| `config-version: 1` | `config-version: 2` plus database/editing/integration defaults |
| crate `enabled: true/false` | `state: PUBLISHED/DISABLED` |
| crate `key-id` | `keys.accepted: [id]` with `keys.cost: 1` |
| crate `permission` | `access.permission` |
| crate `open-cooldown-seconds` | `opening.cooldown-seconds` |
| existing rewards and exact base64 items | same IDs, order, weights, items, commands, permissions, and broadcasts |
| version 1 key item | live PlexonKeys definition plus exact fallback |
| `locations.yml` | SQLite `locations` |
| `statistics.yml` | SQLite global/player statistics |
| migration completion | SQLite `migration_history` marker |

New fields receive conservative defaults: unlimited reward limits, pity disabled, `ROULETTE` animation, bulk enabled up to 64, and no Vault money or experience unless explicitly configured later.

## Verify the upgrade

After the first successful start:

1. Read the enable line and note the migration backup directory.
2. Run `/pcrates validate` and `/pcrates diagnose`.
3. Confirm schema `2`, zero unresolved default keys, zero unexpected key collisions, and zero pending journals.
4. Open `/pcrates` and inspect every migrated crate, key, reward count, and world link.
5. Run `/crates history 1` for a player with prior activity and compare aggregate statistics with the version 1 files.
6. Restart once more. Counts and links must remain unchanged; no second migration backup or duplicate hologram should appear.
7. Complete the real-server upgrade cases in [TESTING.md](TESTING.md) before upgrading the production copy.

Keep both the external full backup and automatic migration backup until the upgraded server has been observed through normal openings and at least one restart.

## If migration is rejected

The startup log identifies the invalid file/path. Leave the generated migration backup in place, stop Paper, correct the problem in the original version 1 data, and retry. Common causes are:

- an invalid or duplicate crate/key/reward ID;
- malformed YAML or unreadable captured item data;
- a location missing world/crate/coordinate fields;
- a negative statistic;
- an empty, multiline, or otherwise unsafe legacy command;
- a filesystem permission failure while writing the database, backup, or converted files.

Because the migration transaction rolls back on failure, do not manually merge values into the SQLite database.

## Roll back to 1.0.0

Rollback discards any openings, links, drafts, keys, limits, pity, or edits created only after the 2.0 upgrade.

1. Stop Paper.
2. Archive the current 2.0 plugin directory for diagnosis.
3. Restore the complete pre-upgrade `plugins/PlexonCrates/` directory from the external backup.
4. Restore `PlexonCrates-1.0.0.jar` and remove the 2.0 JAR.
5. Start Paper and verify the original links/statistics.

If the external full backup is unavailable, restore the YAML files from `backups/migration-1.0.0-<timestamp>/`, restore the 1.0 JAR, and move `data/plexoncrates.db` outside the plugin directory before starting 1.0. The automatic backup does not contain post-migration 2.0 data and should not be treated as a downgrade converter.

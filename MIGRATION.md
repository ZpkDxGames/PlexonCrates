# PlexonCrates migrations

PlexonCrates keeps its internal version migrations separate from external PhoenixCratesLite import. Internal PlexonCrates data is migrated first; external Phoenix data is only considered after the canonical PlexonCrates runtime is healthy.

## PlexonCrates 1.0.0 to 2.0.0

PlexonCrates performs a one-time, reversible migration when it finds `config-version: 1`. It preserves crate/key IDs and exact item data, converts definitions to version 2, and imports mutable runtime data into SQLite.

### Before upgrading

1. Stop Paper cleanly.
2. Copy the entire `plugins/PlexonCrates/` directory to storage outside the server directory.
3. Keep the previous PlexonCrates JAR available for rollback.
4. Confirm the copied `config.yml`, `keys.yml`, every `crates/*.yml`, `locations.yml`, and `statistics.yml` are readable.
5. Replace only the plugin JAR. Do not delete or pre-convert the data folder.
6. Verify the downloaded checksum before starting Paper.

Do not run two Paper instances against the same plugin directory during migration.

### What the internal migration does

1. Creates `data/plexoncrates.db` and the required schema.
2. Detects the version 1 configuration.
3. Creates `backups/migration-1.0.0-<UTC timestamp>/` containing the existing YAML tree.
4. Parses and validates all legacy configuration, keys, crates, locations, and statistics before activation.
5. Converts crate lifecycle/access/key/opening fields and adds conservative defaults without changing reward weights or captured item payloads.
6. Converts legacy keys into live PlexonKeys-backed definitions with their exact version 1 items retained as fallbacks.
7. Removes a leading `/` from otherwise valid legacy console reward commands because newer PlexonCrates stores console commands without it.
8. Imports locations and global/player statistics into SQLite.
9. Commits the SQLite data, migration marker, and atomically written converted YAML as one migration boundary.
10. Loads and validates the complete runtime snapshot.

If converted-file commit fails, the YAML is restored from the timestamped backup and the SQLite import plus marker are rolled back. The plugin then refuses to enable and logs the exact recovery path. A later retry cannot duplicate imported locations or statistics.

`locations.yml` and `statistics.yml` are not deleted. After success they remain legacy reference files and are also present in the migration backup; SQLite becomes authoritative.

### Internal field mapping

| 1.0 field/data | Current destination |
|---|---|
| `config-version: 1` | current config version plus database/editing/integration defaults |
| crate `enabled: true/false` | `state: PUBLISHED/DISABLED` |
| crate `key-id` | `keys.accepted: [id]` with `keys.cost: 1` |
| crate `permission` | `access.permission` |
| crate `open-cooldown-seconds` | `opening.cooldown-seconds` |
| existing rewards and exact base64 items | same IDs, order, weights/items/commands/permissions/broadcasts, then normal current validation |
| version 1 key item | live PlexonKeys definition plus exact fallback |
| `locations.yml` | SQLite locations |
| `statistics.yml` | SQLite global/player statistics |
| migration completion | SQLite migration-history marker |

New fields receive conservative defaults. No migration invents Vault money, XP, premium behavior, or new reward semantics that were not represented by the source.

### Verify the internal upgrade

After the first successful start:

1. Read the enable line and note the migration backup directory.
2. Run `/pcrates validate` and `/pcrates diagnose`.
3. Confirm the current schema is healthy, there are no unexpected unresolved keys/collisions, and no unexplained pending journals.
4. Open `/pcrates` and inspect every migrated crate, key, reward count, and world link.
5. Run `/crates history 1` for a player with prior activity and compare aggregate statistics with the legacy files.
6. Restart once more. Counts and links must remain unchanged; no second migration backup or duplicate hologram should appear.
7. Complete the real-server upgrade cases in [TESTING.md](TESTING.md) before upgrading the production copy.

Keep both the external full backup and automatic migration backup until the upgraded server has been observed through normal openings and multiple restarts.

### If internal migration is rejected

The startup log identifies the invalid file/path. Leave the generated migration backup in place, stop Paper, correct the problem in the original legacy data, and retry. Common causes include:

- an invalid or duplicate crate/key/reward ID;
- malformed YAML or unreadable captured item data;
- a location missing world/crate/coordinate fields;
- a negative statistic;
- an empty, multiline, or otherwise unsafe legacy command;
- a filesystem permission failure while writing the database, backup, or converted files.

Because the migration transaction rolls back on failure, do not manually merge values into the SQLite database.

## 2.x / existing 3.0-development data into 3.0.0

The first production 3.0.0 release must preserve the repository's existing internal schema/version migration path. PlexonCore adoption does not move crate data into Core and does not replace the canonical PlexonCrates database:

```text
plugins/PlexonCrates/data/plexoncrates.db
```

When a server contains both older PlexonCrates data and Phoenix data, use this order:

```text
1. Start/migrate the PlexonCrates internal schema.
2. Validate the PlexonCrates runtime and database.
3. Confirm the public API/event and key registry are healthy.
4. Only then scan/plan the external Phoenix source.
5. Import Phoenix definitions as new drafts only after a reviewed source adapter exists.
```

Do not combine internal schema migration and external Phoenix import into one ambiguous transaction.

## PhoenixCratesLite external migration

PhoenixCratesLite is an external operator-owned data source only. It is not a PlexonCrates runtime dependency.

The current 3.0 finalization branch intentionally does **not** claim field-complete Phoenix import because the actual current PlexonCraft Phoenix data folder or sanitized exact fixture has not been supplied to the builder. The framework therefore scans/hashes safely and keeps import blocked rather than guessing undocumented fields.

See [docs/PHOENIX_MIGRATION.md](docs/PHOENIX_MIGRATION.md) for the current framework and [docs/CUTOVER.md](docs/CUTOVER.md) for the production sequence.

### Source boundary

Use an operator-owned copy under:

```text
plugins/PlexonCrates/imports/phoenix/
```

The scanner:

- confines reads to that boundary;
- rejects symbolic links/path escape;
- computes per-file SHA-256 hashes and a deterministic source fingerprint;
- does not rename, delete, rewrite, normalize, or move source files;
- never executes migration-time reward commands;
- writes PlexonCrates-owned reports under `migration-reports/`.

Do not point the scanner at the only production copy.

### Required adapter behavior once the real fixture exists

A reviewed adapter must map only semantics proven by the supplied data. Depending on fields actually present, this may include crate/reward IDs, exact items, console commands, money/XP, percentages or relative weights, key templates/mappings, world locations, permissions, cooldowns, presentation settings and statistics.

Rules:

- preserve complete Paper/Bukkit ItemStack data when the source stores it;
- never claim exact item fidelity if the source only stored partial material/name/lore data;
- preserve explicit percentages where representable;
- convert relative weights deterministically through the existing 10,000-basis-point largest-remainder allocator;
- report source values, converted values and rounding deltas;
- mark ambiguous or invalid fields `MANUAL_REVIEW`, `UNSUPPORTED`, or `CONFLICT` instead of inventing meaning;
- never silently overwrite an existing PlexonCrates crate, world link, or ambiguous exact key template;
- never mutate PlexonKeys configuration automatically;
- keep already-issued Phoenix keys only through explicit exact legacy-template compatibility where the operator reviews the mapping.

### Import state

External imported definitions must enter:

```text
DRAFT
```

They cannot bypass normal draft persistence, leases, exact-item validation, chance validation, command safety, normalized SQLite publication, administrative audit, or atomic runtime snapshot activation.

Until a reviewed source adapter exists for the actual fixture, `IMPORT_TO_DRAFTS` remains disabled and any plan/report remains `MANUAL_REVIEW`.

### Production coexistence

Do not run PhoenixCratesLite and PlexonCrates as active crate engines simultaneously in normal production gameplay. They can conflict on `/crates`, world-block listeners, key consumption, displays, and reward delivery.

Use a cloned staging server for parity validation. Production removes the Phoenix JAR only after required crate/key/reward/chance/location tests, Core/Quests/Keys diagnostics, restart tests, Phoenix-absent tests and rollback preparation all pass.

The Phoenix data directory is archived, not deleted.

## Rollback

### Roll back an internal PlexonCrates migration

Rollback discards openings, links, drafts, keys, limits, pity, or edits created only after the upgrade.

1. Stop Paper.
2. Archive the current plugin directory for diagnosis.
3. Restore the complete pre-upgrade `plugins/PlexonCrates/` directory from the external backup.
4. Restore the previous PlexonCrates JAR and remove the newer JAR.
5. Start Paper and verify the original links/statistics.

If the external full backup is unavailable, use the applicable automatic migration backup only for the source files it actually contains; do not treat it as a downgrade converter for post-upgrade runtime data.

### Roll back a Phoenix replacement cutover

1. Stop Paper.
2. Remove the active PlexonCrates cutover JAR/data only as required by the prepared rollback plan.
3. Restore the archived PhoenixCratesLite JAR.
4. Restore Phoenix source data only if an operator action modified it; the PlexonCrates importer must never do so.
5. Start Paper and verify the previous engine before reopening gameplay.

Because the external importer is read-only toward Phoenix source data, rollback should not require reversing importer mutations to the Phoenix directory.

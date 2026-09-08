# PhoenixCratesLite Migration

PlexonCrates treats PhoenixCratesLite only as an operator-owned data source for migration. Phoenix is not a runtime dependency and PlexonCrates does not copy, decompile or derive implementation details from the Phoenix JAR.

## Current support state

The 3.0 finalization branch contains the safe migration framework, but **field-complete Phoenix import is intentionally gated** until the current PlexonCraft `plugins/PhoenixCratesLite/` data folder, or sanitized exact fixtures from it, is supplied and tested.

No undocumented Phoenix field layout is guessed.

Current framework phases are:

```text
SCAN
PLAN
IMPORT_TO_DRAFTS
VALIDATE
CUTOVER_REPORT
```

`IMPORT_TO_DRAFTS` currently fails closed because no reviewed source-schema adapter is available yet.

## Supplying migration data

Place an operator-owned copy of the current Phoenix data under:

```text
plugins/PlexonCrates/imports/phoenix/
```

Use a copy, not the only production data directory. Keep the original Phoenix directory backed up separately.

The migration scanner confines reads to this directory. It rejects symbolic links, does not follow them, and places its own reports under:

```text
plugins/PlexonCrates/migration-reports/
```

## Read-only source guarantee

The scanner never automatically:

- renames Phoenix files;
- deletes Phoenix files;
- rewrites or normalizes Phoenix files;
- moves Phoenix files;
- executes Phoenix reward commands;
- treats the Phoenix JAR as source material.

Each regular source file is hashed with SHA-256. The scan records relative path, size and file hash, then derives a deterministic source fingerprint. If source data changes between planning and a future import implementation, a new plan must be generated.

## Current planning behavior

Until a real fixture establishes the schema, every discovered source file is reported as:

```text
MANUAL_REVIEW
```

The generated Markdown report explicitly states that import is disabled and that no crate definitions were created.

Supported migration status vocabulary is already reserved for the future reviewed adapter:

```text
EXACT
CONVERTED
MANUAL_REVIEW
UNSUPPORTED
SKIPPED
CONFLICT
```

## Adapter requirements before import can be enabled

A reviewed Phoenix source adapter must be implemented only after examining the operator-provided data. Tests must then establish the actual mappings for the fields present in that data, including where applicable:

- crate IDs and display identity;
- exact serialized item rewards;
- console command rewards;
- money/XP semantics;
- explicit percentages or relative weights;
- physical key templates and PlexonKeys mapping;
- world/block links;
- permissions and cooldowns;
- presentation/effects when semantics are equivalent;
- statistics only when source meaning is unambiguous.

Unknown or ambiguous data remains `MANUAL_REVIEW` or `UNSUPPORTED`; it must not be silently reinterpreted.

## Chance conversion

If the supplied source proves that Phoenix uses relative weights, the reviewed adapter must convert them deterministically into PlexonCrates' existing 10,000-basis-point model using the existing largest-remainder allocator. Reports must retain source weight, converted basis points/percentage and rounding delta.

If the source stores explicit percentages, representable percentages should be preserved. Invalid or ambiguous pools must remain drafts requiring administrator correction.

## Draft-only import rule

When the source adapter is eventually enabled, imported crate definitions must enter `DRAFT`, never `PUBLISHED`. They must pass the normal PlexonCrates validation and atomic publication path before becoming active.

A Phoenix import must never bypass draft leases, exact-item validation, chance validation, command safety, normalized SQLite publication, audit, or runtime snapshot swapping.

## Key compatibility

The migration plan should prefer explicit mapping to current PlexonKeys categories where the real source supports it. Recoverable Phoenix-era exact key templates may later be retained as explicitly configured legacy accepted templates so already-issued player keys remain usable during transition.

PlexonCrates must not mutate PlexonKeys configuration automatically.

## Production cutover

Do not run PhoenixCratesLite and PlexonCrates as simultaneous production crate engines. Complete migration and parity testing on a cloned staging server first.

Do not remove PhoenixCratesLite until every required crate, key mapping, reward, chance pool, location and opening path has been validated; Core/Quests/Keys diagnostics pass; restart testing passes; and a rollback backup exists.

After cutover, keep the Phoenix data directory archived. PlexonCrates never deletes it automatically.

# PlexonCore Integration

PlexonCrates 3.0 keeps its crate engine independent while participating in PlexonCore 1.x lifecycle and diagnostics when Core is installed.

## Ownership boundaries

- **PlexonCore** owns module registration, module health, integration discovery and shared diagnostic infrastructure.
- **PlexonCrates** owns crate definitions, SQLite persistence, draft publication, opening transactions, reward selection/delivery, world links, displays, Claim Inbox, milestones, rerolls, portable crates and the public crate API/events.
- **PlexonKeys** owns physical key definitions. PlexonCrates consumes exact templates through its key provider/fallback contract.
- **PlexonQuests** consumes the public `com.antondev.crates.api.event.CrateOpenEvent` contract directly.

PlexonCore does not become the canonical crate database and is never queried on the opening hot path.

## Dependency model

PlexonCore 1.0.0 is a Maven `provided` dependency and a Bukkit `softdepend`.

Supported Core API range:

```text
>=1.0 <2.0
```

Runtime modes:

```text
Core present + compatible -> CORE
Core absent               -> STANDALONE
Core incompatible         -> STANDALONE safe fallback
```

The packaged PlexonCrates JAR must not contain `com/zpkdxgames/plexoncore/**`. SQLite JDBC remains bundled.

## Module identity

```text
module id: crates
display name: PlexonCrates
```

Published capabilities include:

```text
crate-engine
crate-api
crate-open-event
physical-keys
plexonkeys-integration
exact-item-rewards
percentage-chances
durable-drafts
sqlite-definitions
world-crates
portable-crates
claim-inbox
milestones
rerolls
virtual-keys
opening-journal
phoenix-migration
```

Capabilities are diagnostic metadata, not permissions.

## Lifecycle

At enable, PlexonCrates resolves Core through Bukkit ServicesManager only when the PlexonCore plugin is present and enabled. The Core-backed bridge is loaded reflectively so standalone startup does not link Core API classes.

A compatible Core registration starts as `STARTING`. After the crate database, runtime snapshot, key registry, API service, commands/listeners and opening coordinator are available, the module becomes `READY`. Pending opening journals requiring manual review produce `DEGRADED` while keeping the safe crate runtime available. A critical startup exception is published as `FAILED` before safe plugin disable.

On disable, PlexonCrates unregisters only the `crates` module registration it owns. It never modifies provider-owned PlexonKeys module state.

## Reload behavior

`/pcrates reload` retains PlexonCrates' existing atomic candidate validation/activation behavior. Core metadata may be refreshed, but Core integration does not create a second database writer, opening coordinator, API service, listener set or display set.

A rejected candidate configuration leaves the previous active snapshot in service. It is not treated as a Core module failure merely because the candidate was rejected.

## Public API and Quests contract

The Bukkit ServicesManager registration remains:

```text
com.antondev.crates.api.PlexonCratesApi
```

The Quests-compatible event remains:

```text
com.antondev.crates.api.event.CrateOpenEvent
```

It exposes `player()` and `plan()`. `OpeningPlan` exposes `transactionId()`, `crateId()`, `keyId()`, `openingCount()`, `rewardIds()` and `source()`.

`CrateOpenEvent` is a post-success event and is not retroactively cancellable. `CratePreOpenEvent` remains the cancellable pre-open extension point.

## Diagnostics

`/pcrates diagnose` reports:

- plugin/Paper/Java versions;
- CORE or STANDALONE mode;
- Core plugin/API version and supported API range;
- Core module state/detail;
- `PlexonCratesApi` registration state;
- `CrateOpenEvent` availability;
- crate, reward, draft, key, world-link and runtime counts;
- key-provider diagnostics;
- SQLite schema/writer queue/pending journals;
- Claim Inbox and portable-crate health.

With a compatible Core installed, `/plexon modules` should report PlexonCrates as `READY` after successful initialization. Without Core, crate gameplay and the public API/event contract continue in standalone mode.

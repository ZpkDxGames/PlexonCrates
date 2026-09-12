# PlexonCrates 5.1 Full-Revamp Audit

## Status

- Baseline: `v5.0.0`
- Baseline source: `f24e3f7f942f7c886352e71c7105af499828096f`
- Rollback release: `v5.0.0`
- Target line: `5.1.0`
- Revamp branch: `revamp/5.1.0-full-source-hardening`
- Paper target: `26.2.build.121-stable`
- Java target: `25`
- Database schema: retain schema `4` unless a later implementation slice proves a migration is necessary

This document applies the Plexon Plugin Full Revamp Standard to the complete PlexonCrates repository. The objective is a product/architecture hardening pass, not a cosmetic rewrite.

## Executive decision

PlexonCrates 5.0 already has a strong transaction and persistence core. The 5.1 revamp should preserve the authoritative opening pipeline, exact-item custody, durable claims, immutable published runtime snapshots, draft/publication model, PlexonKeys integration, and public lifecycle API.

The primary problems are concentrated in the UI/control plane and administrative I/O boundaries:

1. player menu session cleanup is not explicit/reliable on disconnect;
2. menu transitions are invoked directly from inventory event handlers even though Paper requires view-changing operations to be deferred;
3. inventory events are routed through overlapping listeners instead of one clear inventory click/drag authority;
4. `GuiSessionService` performs hidden listener registration from its constructor;
5. animated openings create a repeating task per opening despite the stated no-per-opening-task performance boundary;
6. `/pcrates reload` blocks the primary thread on database futures;
7. Phoenix migration scan/plan/import/report performs potentially large filesystem work from the command thread;
8. optional YAML mirror writes can occur on the primary thread during publication;
9. several very large classes (`DatabaseService`, `OpeningService`, `CrateRegistry`, `MenuService`, `AdminMenuService`) make boundaries harder to verify and maintain.

The revamp therefore keeps the product model and hardens lifecycle, routing, scheduling, I/O, and test coverage around it.

## Architecture map

### Lifecycle / composition

`PlexonCrates`

- resolves PlexonCore bridge;
- loads configuration and migrations;
- starts SQLite-backed `DatabaseService`;
- creates repositories/services;
- builds immutable runtime registry;
- creates GUI/session/editor/opening/display services;
- registers Bukkit services, listeners, and commands;
- tears down sessions/tasks/database on disable.

Risk: construction and listener registration are partially hidden inside `GuiSessionService`, which makes lifecycle ownership non-obvious.

### Runtime opening authority

`OpeningService`

`validate -> plan -> journal -> revalidate -> payment -> grant -> durable completion`

Supporting services include key planning/consumption, reward selection/state, milestones, rerolls, claims, portable crates, runtime snapshots, and opening log/audit state.

Decision: **KEEP / HARDEN**, not rewrite.

### Persistence

- SQLite schema 4 is canonical for mutable definitions/recovery/player state.
- `DefinitionRepository` wraps definition persistence.
- YAML files are configuration, bootstrap/mirror, or migration material.
- `DatabaseService` owns the bounded worker and durable operations.

Decision: **KEEP**, but remove avoidable primary-thread waits/writes.

### GUI / command surfaces

Current player-facing routing is split between:

- `MenuService` legacy/compatibility/player/admin/opening surfaces;
- `PlayerCrateMenuService` Phase 3 Crate Hall/player flow;
- `PlayerCrateCommandRouter` command + inventory event delegation;
- `AdminMenuService` administration;
- `SimulationAdminListener` test-lab interception;
- `GuiSessionService` session authority plus hidden listener registration.

Decision: **REDESIGN / CONSOLIDATE** into explicit composition and a single inventory click/drag routing authority while retaining compatibility entry points.

## Feature viability matrix

| Feature | Player/admin value | Runtime / risk | Decision | 5.1 direction |
| --- | --- | --- | --- | --- |
| Crate Hall / preview | High | Medium GUI churn | KEEP + REDESIGN | One canonical player navigation model, explicit states, safe deferred transitions |
| Random Open 1 | Core | High transaction value | KEEP | Preserve `OpeningService` authority and submit guard |
| Open More / bulk | High | High transaction value | KEEP | Preserve capacity authority and explicit confirmation; evaluate Dialog quantity input after lifecycle hardening |
| Selective opening | High | High transaction value | KEEP | Preserve explicit confirm and eligibility revalidation |
| Physical linked crates | High | Hot interaction path | KEEP | Retain lightweight location lookup and authority split |
| Physical + virtual keys / PlexonKeys | High | High payment risk | KEEP | Preserve exact payment planning/revalidation and provider isolation |
| Claim / Pending Rewards | High reliability value | High recovery risk | KEEP | Preserve durable reservation/fail-closed semantics |
| Opening history / stats | Medium-high | Low-medium | KEEP | Keep read paths bounded/paginated |
| Pity / limits / cooldowns | High fairness value | Medium | KEEP | Keep shared eligibility authority between preview and opening |
| Milestones | High | Medium | KEEP | Preserve durable progress and publication validation |
| Rerolls | High | High post-payment risk | KEEP | Preserve locked transaction state; harden scheduler/UI lifecycle only |
| Portable crates | High | High replay/security risk | KEEP | Preserve HMAC + issuance ledger + fail-closed verification |
| Durable draft editor | High admin value | Medium-high complexity | KEEP + REDESIGN | Preserve leases/revisions; improve routing and input surfaces incrementally |
| Simulation / test lab | High admin safety value | CPU-heavy by nature | KEEP | Preserve bounded async analysis; explicit lifecycle registration |
| Phoenix migration | Transitional but important | Potentially heavy filesystem I/O | KEEP + REDESIGN | Move scan/hash/parse/report/backup work off primary thread with controlled sync snapshots |
| Roulette animation | Cosmetic | Scheduler/allocation cost | REDESIGN | Replace per-opening repeating tasks with one shared coordinator or degrade to lightweight reveal |
| YAML published mirror | Operational convenience | Main-thread file-write risk | KEEP AS OPTIONAL MIRROR | Persist asynchronously after canonical SQLite/runtime commit; never make mirror the authority |
| Legacy browser/preview implementation | Low incremental value | Duplicated GUI logic | MERGE | Keep compatibility entry points but converge rendering/navigation on canonical player surfaces |

## Confirmed findings

### F1 — Player menu quit cleanup is ineffective

`PlayerCrateMenuService` stores `views` by session UUID. Its current quit handler removes entries only when `views.get(id) == null`, which cannot clear a normal live entry. `InventoryCloseEvent` normally helps, but disconnect cleanup must not depend on event ordering or an implicit close.

Severity: **Medium reliability / retained-state risk**.

Required fix:

- track session ownership explicitly;
- remove view/submission/owner state on close, quit, disable, and supersession;
- add regression coverage for disconnect with an active player menu and pending async completion.

### F2 — Unsafe inventory view transitions inside inventory events

Player and compatibility menu click handlers call `openInventory`, `closeInventory`, or methods that do so directly from `InventoryClickEvent`. Paper 26.2 documents view-changing operations as unsafe during inventory click/close modification and requires deferring them through the scheduler.

Severity: **High API-correctness / interaction reliability**.

Required fix:

- event handler accepts/cancels intent;
- validate current holder/session/action;
- schedule view transition or close for the next server execution point;
- revalidate session/revision before any value-mutating operation;
- keep pure in-place slot patches synchronous only when they do not replace the view.

### F3 — Overlapping inventory routing

`MenuService` receives every `MenuHolder` click/drag, while `PlayerCrateCommandRouter` separately delegates player menu clicks/close/quit to `PlayerCrateMenuService`. `SimulationAdminListener` also intercepts editor clicks for its domain. Although current handlers mostly avoid double mutation, this is harder to reason about than one router.

Severity: **Medium maintainability / future exploit regression risk**.

Required fix:

- make `MenuService` (or a dedicated `InventoryRouter`) the single click/drag router;
- delegate typed actions to player/admin/simulation handlers;
- keep command preprocessing separate from inventory routing;
- remove listener registration side effects from service constructors.

### F4 — Hidden listener registration in `GuiSessionService`

The session service constructor discovers `PlexonCrates` through Bukkit and registers `SimulationAdminListener` and `PlayerCrateCommandRouter` as side effects.

Severity: **Medium lifecycle / testability**.

Required fix:

- make the session service a pure explicit dependency;
- construct/register listeners in `PlexonCrates#onEnable`;
- close owned executors/services explicitly in `onDisable`.

### F5 — Per-opening repeating animation task

`MenuService#animate` creates a new repeating `BukkitRunnable` for each animated opening. This contradicts the stated 5.0 performance boundary and scales task count with concurrent openings.

Severity: **Medium performance / scalability**.

Required fix:

- introduce one shared animation coordinator for all active opening animations, or replace the roulette with a bounded one-shot reveal;
- no repeating task per player/opening;
- stop coordinator when no animations exist.

### F6 — Synchronous reload waits on database futures

`PlexonCrates#reloadFor` calls `loadPublished().join()`, `loadDrafts().join()`, and `loadKeys().join()` from the command path.

Severity: **High main-thread latency risk**.

Required fix:

- split reload into async load/validation and primary-thread atomic activation;
- guard concurrent reloads;
- preserve rollback to the previous in-memory snapshot on activation failure;
- return immediate feedback that reload validation started, then report completion/failure on the primary thread.

### F7 — Phoenix migration performs large file work on command thread

Migration scan/plan/import/report walks directories, hashes files up to 64 MiB, parses YAML, copies backups, writes reports, and waits on database operations from the command path.

Severity: **High if used on a live server; low frequency**.

Required fix:

- serialize one migration operation at a time;
- capture Bukkit/runtime data needed for validation on the primary thread;
- run filesystem/hash/parse/backup/database work asynchronously;
- marshal only Bukkit state access and final messages/activation back to the primary thread.

### F8 — Published YAML mirror can write on primary thread

Publication correctly treats SQLite as canonical, but `DefinitionPublisher#activate` invokes `CrateRegistry#installPublished`, which performs `AtomicFiles.write` while activation requires the primary thread.

Severity: **Medium main-thread I/O**.

Required fix:

- split in-memory install from mirror persistence;
- install immutable runtime/in-memory state on the primary thread;
- queue the optional YAML mirror write asynchronously;
- mirror failure remains warning-only and never rolls back a successful canonical publication.

### F9 — Oversized boundary classes

Largest concentration points include approximately:

- `DatabaseService` ~210 KiB;
- `AdminMenuService` ~127 KiB;
- `OpeningService` ~111 KiB;
- `CrateRegistry` ~92 KiB;
- `MenuService` ~87 KiB;
- `PlayerCrateMenuService` ~54 KiB.

Size alone is not a defect. The revamp should extract only cohesive responsibilities where this directly improves correctness, scheduling boundaries, or testability. No rewrite-for-style work.

## What is already strong and should be preserved

- custom `MenuHolder` identity rather than title parsing;
- slot/action binding instead of lore parsing;
- exact-item snapshots and server-side validation;
- explicit one-opening-per-player transaction authority;
- immutable published runtime revisions;
- stale async GUI update guards using holder/session/revision;
- durable draft leases/revisions/publication;
- bounded async crate simulation;
- Claim Inbox reservation and review states;
- portable issuance replay protection;
- separate optional integrations (PlexonCore, PlexonKeys, Vault, PlaceholderAPI);
- non-empty CI test enforcement, exact dependency checksums, provenance and rollback metadata;
- extensive integration/regression coverage already present in the repository.

## Product / UX redesign

### Player navigation

Canonical hierarchy:

`/crates -> Crate Hall -> Preview -> Quantity/Selection -> Confirmation -> Opening -> Result`

Secondary player destinations:

`Crate Hall -> Pending Rewards`

Physical and portable interactions may enter at Preview/Confirmation, but should use the same state semantics and safe routing rules.

### Surface choices

- Inventory GUI remains appropriate for Hall, reward preview, claim browsing, reward editor collections, keys, milestones, and visual comparisons.
- Paper Dialog API should be evaluated for numeric custom bulk quantity, destructive admin confirmations, and text/numeric settings once the routing layer is stable.
- Action bar/chat should handle lightweight success/failure/status instead of reopening a menu solely for a message.
- Existing chest menus should not be converted to dialogs merely for novelty.

### Required dynamic states

Every player/admin dynamic menu must explicitly render:

- loading;
- empty;
- error/unavailable;
- no results where filtering exists;
- first/last page behavior;
- stale/invalidated session state.

## Architecture target

### UI

`InventoryRouter -> typed menu controller -> application service -> repository/storage`

- one inventory click router;
- one drag router;
- explicit holder/session identity;
- per-viewer session owner and lifecycle;
- safe-next-tick navigation helper;
- stable slot-to-action map;
- partial slot updates for async data;
- no presentation parsing as authority.

### Scheduling

Introduce a small scheduler abstraction with:

- primary/global execution;
- player/entity execution where applicable;
- async execution;
- delayed/repeating execution.

Do not declare Folia support in 5.1 unless every service touching world/entity/inventory state is migrated and runtime-tested under Folia.

### Persistence

Preserve:

`UI -> service -> repository -> storage`

SQLite remains canonical. YAML remains configuration/bootstrap/mirror, not high-frequency player state.

## Implementation slices

### Slice 1 — GUI correctness and lifecycle

- fix player session ownership/quit cleanup;
- defer view-changing click actions;
- consolidate click/drag routing;
- make listener composition explicit;
- add click/drag/quit/stale-session regression tests.

### Slice 2 — Scheduler and animation

- add scheduler abstraction;
- replace per-opening repeating roulette tasks with a shared coordinator;
- retain one-shot reveal as lightweight fallback;
- prove no task leak after close/quit/disable.

### Slice 3 — Administrative I/O

- asynchronous reload load/validation + atomic sync activation;
- async Phoenix filesystem/database work with sync snapshots;
- async optional YAML mirror persistence;
- test concurrent/rejected operations and shutdown behavior.

### Slice 4 — Maintainability extraction

Extract only proven responsibility boundaries from oversized services, prioritizing:

- database definition/recovery repositories from `DatabaseService`;
- opening validation/payment/grant orchestration seams from `OpeningService`;
- menu renderer/controller boundaries from `MenuService` / `AdminMenuService`.

Public behavior and schema must remain compatible unless an explicit migration is justified.

### Slice 5 — UX modernization

- evaluate Paper Dialog API for custom bulk quantity and admin destructive/numeric/text input;
- normalize navigation slots and wording across admin/player surfaces;
- reduce duplicate legacy player rendering paths;
- preserve compatibility commands and physical/portable entry points.

## Verification gates

Before merge/release:

1. `mvn -B -ntp clean verify` succeeds from a clean checkout.
2. Existing tests remain non-empty with zero failures/errors/skips.
3. Add regression tests for every fixed defect.
4. No inventory view transition occurs directly inside click/close handlers.
5. No retained player menu/session state after close/quit/disable.
6. No per-opening repeating scheduler task.
7. Reload and migration heavy I/O does not execute on the primary thread.
8. Publication mirror persistence is non-authoritative and off-thread.
9. Existing schema 4 data upgrades/restarts without loss.
10. `/crates`, physical crate, portable crate, selective, bulk, claims, reroll, admin editor, publish, reload, backup, validate and diagnose receive runtime smoke tests.
11. spark evidence is captured during repeated crate opening and admin/menu activity and compared with the 5.0 baseline.
12. Generated JAR, SHA-256, source SHA and rollback release are recorded.

## Release policy

5.1.0 is not eligible for stable publication solely because CI passes. Use a release candidate for live PlexonCraft runtime certification when the implementation slices alter event routing, scheduling, or I/O boundaries. Promote the exact tested source/artifact only after startup, interaction, persistence/restart, and active-use performance gates pass.

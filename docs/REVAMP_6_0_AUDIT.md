# PlexonCrates 6.0 Full GUI / Animation / Exact-Item Revamp Audit

## Baseline decision

- Target: `6.0.0`
- Implementation branch: `revamp/6.0.0-gui-animation-overhaul`
- Exact chosen base: `a709e5821516fe674b2b7c26c39e1a78851737ff`
- Published 5.1 runtime boundary contained by base: `v5.1.0-rc.1` / `015bc8fae37c0f6ebf7fc4161a33b3f103b46a7b`
- Base delta after RC1: one documentation-only commit (`docs/REVAMP_5_1_IMPLEMENTATION_STATUS.md`)
- Base Build: `34701673022` — PASS
- Current stable / rollback: `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`
- Runtime: Paper `26.2.build.121-stable`, Java `25`
- Current database schema: `4`

`main` still points at stable 5.0.0. PR #14 remains open/draft. The 6.0 branch therefore starts from the newest green 5.1 hardening head, not from old stable, so GUI lifecycle, bounded I/O, shared opening-animation scheduling, release safety and migration hardening are retained.

## Executive decision

PlexonCrates already has a mature transaction and recovery core. 6.0 is a product/control-plane revamp, not a transaction rewrite. Preserve `OpeningService`, journal/idempotency, payment planning, pending claims, exact-item custody, draft/publication revisions, PlexonKeys boundaries, SQLite durability, portable replay protection, selective/random/bulk semantics, pity/limits/milestones/rerolls, bounded I/O and fail-closed release promotion.

The primary 6.0 work is:

1. consolidate player/admin journeys around explicit screens and states;
2. turn existing cosmetic particle/roulette behavior into reusable bounded animation profiles;
3. make reward/key editing expose exact-item diagnostics without mutating canonical items;
4. add first-class key/reward/claim/history management surfaces;
5. substantially expand exact-item, GUI-routing, animation and performance regression coverage.

## Architecture map

### Lifecycle / composition

`PlexonCrates` explicitly composes configuration, SQLite, registries, services, menus, listeners and commands. 5.1 removed hidden GUI listener ownership and centralizes inventory click/drag routing through `CrateMenuEventRouter`.

Decision: **KEEP / EXTEND**. New 6.0 services must have explicit start/stop or remain pure value services.

### Transaction authority

`OpeningService`

`validate -> plan -> journal -> revalidate -> payment -> reward grant/claim -> durable completion`

Animations remain presentation only. No animation callback may independently consume payment, select another reward, grant delivery, increment pity/limits, or finalize a journal.

Decision: **KEEP**.

### Persistence map

- SQLite schema 4: canonical mutable definitions, claims/recovery, player state and durable runtime data.
- `DefinitionRepository`: definition/draft/key persistence boundary.
- YAML: bootstrap/configuration/mirror/migration material only.
- `AsyncIoService` / database workers: bounded off-thread I/O.
- `RuntimeRegistry`: immutable published runtime view.

Decision: **KEEP**. Add schema only for genuinely durable 6.0 state; do not migrate for cosmetic GUI/animation work.

### GUI map

Current authorities:

- `CrateMenuEventRouter`: centralized inventory click/drag authority.
- `GuiSessionService`: session ownership.
- `MenuHolder`: explicit kind/session/crate/reward/revision identity.
- `PlayerCrateMenuService`: current Crate Hall/player experience.
- `AdminMenuService`: large administration surface.
- `SimulationAdminListener`: Test Lab.
- `EditSessionService` + `DraftSessionService`: durable editor/revision control.

Decision: **REDESIGN IN PLACE**. Retain one router and holder identity; split new screens/controllers where doing so reduces the very large menu classes.

### Physical crate presentation

`DisplayService` currently owns holograms plus one shared, bounded particle task. `LocationStore` already maintains exact-block, chunk and crate indexes and exposes `inChunk` / `nearbyChunks`, so a 6.0 particle system does not need a second global location registry or a task per crate.

Decision: **HARDEN / EXTRACT** particle behavior into a profile-driven shared coordinator while preserving indexed candidate selection, loaded-chunk checks, receiver distance filtering and global budgets.

### Opening presentation

`OpeningAnimationCoordinator` already replaced per-opening repeating tasks with one shared task and main-thread-confined active sessions.

Decision: **KEEP / EXTEND** to profile/stage semantics. Do not create an alternate opening engine.

## Exact-item fidelity map

`ItemSnapshotCodec` is the canonical lossless mechanism:

- clone source without consuming it;
- record quantity separately;
- canonicalize the stored template to amount 1;
- persist native `ItemStack#serializeAsBytes()` bytes;
- validate payload size;
- SHA-256 fingerprint stored bytes;
- round-trip verify on the current Paper runtime;
- restore with `ItemStack#deserializeBytes()`;
- split delivery using the restored item's real max stack size.

It also identifies foreign PDC/custom-model data and nested bundle/block-state contents for diagnostics.

Decision: **KEEP / EXPAND TESTS AND DIAGNOSTICS**. Material/name/lore/plugin IDs may be display hints only and must never replace native bytes as reward authority.

## Transaction map

Player/physical/portable entry points ultimately converge on existing opening services. Key payment planning, eligibility, reward selection, recovery, pending claims, pity, limits, milestones and rerolls must remain service-layer authorities. GUI and command paths may only submit intents to those services.

Decision: **KEEP**.

## Scheduler / task inventory

Known repeating/coordinated runtime work:

- shared opening roulette coordinator;
- one shared display particle task;
- statistics / service maintenance tasks where already configured.

6.0 rule:

- no repeating task per crate;
- no repeating task per player GUI;
- no repeating task per opening;
- no repeating task per animation profile;
- coordinators stop when inactive and on plugin disable.

## Synchronous I/O inventory

5.1 moved live reload preparation, migration filesystem work, optional definition mirrors and other large administrative I/O away from the primary thread. 6.0 must not reintroduce file/database/network calls from GUI clicks or animation ticks.

Configuration/profile parsing is preparation work. Runtime animation loops consume immutable validated profile data only.

## Performance hot paths

1. physical crate interaction (`CrateListener` + exact indexed location lookup);
2. particle candidate selection near online viewers;
3. opening animation ticks and changed inventory slots;
4. player/admin inventory click routing;
5. opening eligibility/payment/reward transaction path;
6. claim delivery / inventory capacity handling.

6.0 optimization rules: bounded candidates/particles, receiver scoping, loaded-chunk filtering, partial slot updates, immutable prepared profiles, no blocking I/O, no unbounded executors, no menu reopen loops.

## Migration risks

- Existing schema-4 exact item payloads must remain readable.
- Do not mass re-encode native item bytes.
- Any new durable player preference/profile assignment must use an explicit idempotent schema migration with backup/upgrade/restart tests.
- Phoenix migration remains a separate import path; do not couple 6.0 UI code to proprietary Phoenix structures.
- Existing YAML crate/key compatibility must remain unless migration is explicit.

## Duplicate/dead GUI paths

The largest remaining duplication risk is between older compatibility menu methods and newer `PlayerCrateMenuService` / admin surfaces. 6.0 should keep compatibility entry points but route them into canonical screen/service behavior instead of creating a third parallel menu system.

## Feature viability matrix

| Feature | Decision | 6.0 direction |
| --- | --- | --- |
| `OpeningService` transaction core | KEEP | No rewrite; add regression coverage around presentation boundaries |
| Opening journal/idempotency | KEEP | Animation cannot bypass/finalize independently |
| Pending claims/recovery | KEEP + REDESIGN UI | First-class bounded player/admin surfaces |
| Exact item snapshots | KEEP + HARDEN | More fixtures, diagnostics, copy/move/claim path tests |
| Crate Hall | REDESIGN | Canonical hall -> detail -> preview/open path |
| Reward preview | REDESIGN | Exact restored icon; companion info items, never canonical lore mutation |
| My Keys | ADD | First-class player overview backed by existing key service |
| Opening history | ADD/KEEP | Bounded/paginated over existing history data |
| Player animation preferences | ADD only if durable safely | Presentation-only; no selection/payment semantic changes |
| Admin dashboard | REDESIGN | Crates / Rewards / Keys / Physical / Animations / Test Lab / Recovery / Diagnostics |
| Reward manager | REDESIGN | Explicit exact-item capture/replace, status/filter/sort/bulk tools |
| Key manager | REDESIGN | Preserve PlexonKeys provenance and dependency safety |
| Physical crate particles | HARDEN | Shared profile-driven coordinator over indexed nearby chunks |
| Opening roulette | KEEP + EXTEND | Shared profile/stage presentation coordinator |
| GUI decorative animation | ADD carefully | Shared cadence, changed slots only, stop on close |
| Test Lab | KEEP + REDESIGN UI | Non-granting simulation + animation/item diagnostics |
| Global reward cap | ADD only if no equivalent | Must be atomic/durable/concurrency-safe or omitted |
| Phoenix migration | KEEP | Clean-room migration support; bounded I/O remains |
| YAML published mirrors | KEEP optional | Non-authoritative and off-thread |
| Legacy duplicate player GUI rendering | MERGE | Compatibility entry points only; no new duplicated authority |

## 6.0 implementation order

1. animation/profile and exact-item foundations with tests;
2. physical particle coordinator extraction and budget tests;
3. player flow + exact reward preview;
4. reward/key/admin management hierarchy;
5. opening profile/stage integration and preview-only Test Lab behavior;
6. documentation, migration validation, full CI;
7. publish immutable `v6.0.0-rc.1` and verify assets;
8. runtime-certify the exact published JAR on PlexonCraft;
9. only then guarded stable promotion.

## Release boundary

CI success is not runtime certification. Stable `v6.0.0` is forbidden until the exact published RC passes startup, player/admin flows, custom-item persistence/restart/claim, animation/load and soak gates on the real Paper 26.2 PlexonCraft host.

If host evidence is unavailable, the maximum truthful state is `RC RELEASED / RUNTIME PENDING`.

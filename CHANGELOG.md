# Changelog

All notable PlexonCrates changes are documented here.

## 3.0.0 — In development

### Foundation

- Continued the existing unreleased `3.0-Update` implementation rather than restarting from the 2.0 release line.
- Added the normalized schema-3 definition graph for crates, rewards, exact item BLOBs, typed actions, keys, profiles, milestones, ledgers, claims, portable issuances, versioned drafts, and migrations.
- Added versioned durable drafts with ordered compare-and-update saves, writable leases, audited takeover, bounded revision history, stale-action rejection, and forward undo revisions.
- Added the integer `ChanceAllocator` with exact basis-point totals, stable largest-remainder migration/normalization, predictable new-reward allocation, locked edits, balancing tools, and integer ticket boundaries.
- Added `ItemSnapshotCodec` for non-destructive amount-one Paper byte snapshots, SHA-256 verification, diagnostic metadata, payload bounds, and exact maximum-stack-size delivery splitting.

### Percentage runtime

- Replaced normal reward weights with exact base-chance percentages and `chance-basis-points` in bundled version 3 definitions.
- Added proportional add/edit/remove redistribution, disabled-reward zeroing, lock support, equal/relative/unlocked/rarity balance modes, and exact publication validation.
- Converted legacy version 2 weights deterministically while retaining deprecated API and command aliases for 3.x compatibility.
- Switched runtime selection to unbiased integer tickets and clearly separated configured base chance from the player's current eligible chance.

### Durable editor sessions

- Connected GUI and compatibility-command mutations to the schema-3 draft repository instead of the legacy last-writer-wins snapshot path.
- Added asynchronous draft loading, visible saving/saved/failure/read-only states, ordered per-draft saves, stale mutation guards, retry, forward undo, and bounded shutdown flushing.
- Added a single-writer lease across administrators, an explicit read-only view for additional editors, and permission-gated confirmed takeover that immediately invalidates the previous lease.
- Added server-owned GUI session UUIDs plus draft UUID/revision/lease stamps, rejecting superseded inventories and pre-takeover actions before they reach the router.
- Made crate deletion discard its durable draft transactionally so recreating an ID cannot resume stale editor data.

### Published runtime isolation

- Added a canonical definition repository, atomic `DefinitionPublisher`, and immutable revisioned runtime snapshots loaded from SQLite.
- Normalized each publication into crate, reward, typed-action, exact-item, key-template, and crate-key-link rows in the same transaction as its audit entry and draft close.
- Routed player browsing, commands, linked blocks, displays, forced openings, and public API crate queries through published snapshots while administration continues to render the current durable draft.
- Added frozen draft publication, stale base/lease/revision rejection, the cancellable `CrateDraftPublishEvent`, runtime revision API accessors, and preview revision checks.
- Prevented cross-crate key replacement from bypassing draft leases and kept active keys undeletable until every replacement draft is published.

### Advanced recovery and feature primitives

- Added deterministic milestone progression with one-time/repeating thresholds, stable earned keys, and durable SQLite progress counters.
- Added reroll offer state with exclusion/timeout semantics and audited, non-negative reroll-token ledger mutations.
- Added bounded mass-opening planning and one-edge alternative-reward validation/resolution.
- Added player-facing `/crates milestones` and `/crates rerolls` views plus admin reroll grant/take/set commands.

### Portable crate pipeline

- Added persistent HMAC signing secrets, durable issuance rows, owner binding, latest/pinned revision policies, distinct bounded batch issuance, offline Claim Inbox delivery, and the granular `plexoncrates.admin.portable` permission.
- Added right-click reward previews with explicit confirmation; closing, changing the held item, failing a precondition, or cancelling `PortableCrateUseEvent` consumes nothing.
- Routed confirmed portable use through the ordinary frozen reward plan, journal, limits, pity, delivery, statistics, history, and event pipeline while treating the item as the default full opening cost.
- Added reservation release on normal failure/shutdown/restart, single-use consume transitions, duplicate/replay rejection, deterministic tamper tests, and refusal to silently rotate a lost signing secret while issuances remain outstanding.
- Extended `/pcrates diagnose` with signer health and unused/reserved/consumed/review issuance counts.

### PlexonCore 1.x integration

- Added an isolated `com.antondev.crates.integration.core` bridge with safe reflective loading so Core remains optional at runtime.
- Added `CORE` and `STANDALONE` operation, supported Core API range `>=1.0 <2.0`, module ID `crates`, and diagnostic capabilities without moving crate persistence or gameplay ownership into Core.
- Added Core lifecycle publication for `STARTING`, `READY`, `DEGRADED`, and startup `FAILED`, with clean module unregister on disable.
- Added `PLEXON_CRATES` integration publication while leaving PlexonKeys provider-owned Core state untouched.
- Extended `/pcrates diagnose` with Core plugin/API versions, supported range, mode, module state/detail, public API registration, runtime revision and crate-event availability.
- Kept `/pcrates reload` on the existing atomic crate reload path; Core metadata refresh does not recreate SQLite, opening coordinators, listeners, displays, API services, or writer tasks.
- Added real-Core-registry MockBukkit coverage for compatible `STARTING -> READY`, module identity/capabilities, `PLEXON_CRATES READY`, and incompatible-Core standalone fallback.

### Public API and PlexonQuests 3.1 contract

- Preserved `com.antondev.crates.api.PlexonCratesApi` and its Bukkit ServicesManager registration in both Core and standalone modes.
- Preserved the existing public Bukkit event package rather than introducing Core-specific replacements.
- Locked the exact `com.antondev.crates.api.event.CrateOpenEvent` reflection contract expected by PlexonQuests 3.1: `player()`, `plan()`, and `OpeningPlan` metadata `transactionId`, `crateId`, `keyId`, `openingCount`, `rewardIds`, and `source`.
- Kept `CrateOpenEvent` as one post-success logical transaction event; `CratePreOpenEvent` remains the cancellable pre-open extension point.

### PlexonKeys 1.2 integration

- Audited the released PlexonKeys 1.2 source and adopted its stable Bukkit `PlexonKeysAPI` service as the preferred live physical-key template source.
- Isolated direct PlexonKeys API linkage behind `PlexonKeysServiceAdapter`, loaded only when the provider is present, while retaining the previous settings-surface reflection adapter as compatibility fallback.
- Preserved live-first resolution, last-known-good templates, exact configured fallback and fail-closed unresolved behavior.
- Added service-first discovery tests with exact defensive ItemStack metadata and amount normalization.
- Added PlexonKeys 1.2 as a `provided` compile dependency only; distribution checks reject any shaded `com/antondev/keys/**` runtime classes.

### PhoenixCratesLite replacement framework

- Added a clean-room, read-only `PhoenixMigrationService` boundary under `imports/phoenix/` with explicit `SCAN`, `PLAN`, `IMPORT_TO_DRAFTS`, `VALIDATE`, and `CUTOVER_REPORT` phases.
- Added deterministic per-file SHA-256 hashing and a source fingerprint, path confinement, symbolic-link rejection and a bounded source-file size limit.
- Added migration status vocabulary `EXACT`, `CONVERTED`, `MANUAL_REVIEW`, `UNSUPPORTED`, `SKIPPED`, and `CONFLICT`.
- Kept schema-dependent import deliberately blocked until the actual current PlexonCraft Phoenix data folder or sanitized exact fixture is supplied and a reviewed adapter is implemented; undocumented Phoenix fields are not guessed.
- Added Markdown migration reports outside the source boundary and tests proving scanner source immutability, deterministic hashes, report confinement and fail-closed import.
- Documented draft-only future import, exact item/chance/key mapping requirements, staging parity checks, dual-engine prohibition and rollback/cutover rules.

### Release engineering

- Added PlexonCore 1.0.0 and PlexonKeys 1.2.0 as `provided` Maven dependencies while keeping SQLite JDBC bundled.
- Updated CI to provision checksum-verified Core/Keys release JARs, reject skipped tests, verify one output JAR, require SQLite classes and reject shaded Core/Keys runtime classes.
- Replaced push-to-`main` release publication with tag-only `v3.0.0-rc.*` prereleases and final `v3.0.0` publication.
- Added `SHA256SUMS.txt` generation/verification and versioned RC/final release-note files.
- Added `docs/PLEXONCORE.md`, `docs/API.md`, `docs/PHOENIX_MIGRATION.md`, `docs/CUTOVER.md`, and expanded `TESTING.md`/`MIGRATION.md` for the 3.0 Core/Phoenix acceptance gates.

### Documentation

- Adopted the expanded 3.0 implementation specification, including the one-edition/unlimited-definition contract and the original PhoenixCrates-benchmarked GUI usability boundary.
- Clarified that PhoenixCratesLite is migration data only, not implementation source material or a runtime dependency.

## 2.0.0 — 2026-09-02

### Added

- SQLite schema for world links, statistics, opening history/journals, reward limits, pity counters, persistent drafts, cached provider templates, migration markers, and administrative audit records.
- Provider-backed physical-key registry with live PlexonKeys discovery, last-known-good cache, exact fallback templates, captured custom keys, collision diagnostics, and optional legacy-template rotation.
- Persistent crate lifecycle (`DRAFT`, `PUBLISHED`, `DISABLED`, and `ARCHIVED`) with guided creation, search, clone, import/export, validation, and destructive confirmations.
- Full reward-bundle editor for exact items, console commands, experience, levels, Vault money, rarity, display item, permissions, messages, broadcasts, limits, ordering, test delivery, and presentation effects.
- Per-player/global lifetime and rolling-window limits, reward cooldowns, and deterministic pity policies.
- Protected persistent-data-tagged Link Wand with block inspection, duplicate-link prevention, confirmations, and break/explosion/piston protection.
- Journal-first opening coordinator with immutable plans, immediate revalidation, per-player locks, bounded bulk behavior, atomic history/state completion, and manual crash-review diagnostics.
- `INSTANT`, `ROULETTE`, `REVEAL`, and `SUMMARY` opening modes, native TextDisplay holograms, centralized particles, and per-reward titles/sounds/firework effects.
- Optional Vault and PlaceholderAPI integrations, personal history command, consistent backups, grouped diagnostics, granular permissions, public services API, and Bukkit lifecycle events.
- Automatic, idempotent, reversible 1.0 migration with a timestamped YAML backup and one transaction spanning SQLite import plus converted-file commit.

### Changed

- Upgraded every bundled configuration file to `config-version: 2` while preserving the four default crates and their 32 rewards.
- Reworked exact key handling so a transaction freezes one resolved template and scans deterministic inventory slots only once per validation/consumption stage.
- Reward previews now use the same permission, dependency, limit, and pity-aware pool as selection.
- Configuration reload now validates a complete immutable snapshot and rolls back settings, messages, menus, crates, keys, and displays if activation fails.
- Administrative GUI permissions now enforce the relevant granular node on each action.
- Opening logs and diagnostics now include transaction/provider/schema context without serializing private item data.

### Security

- Rejected forged key lookalikes that differ in PDC or custom metadata.
- Prevented double-click races, distributed GUI drag capture, editor-item recapture, unbounded bypass bulk opening, path traversal IDs/imports, unsafe reward commands, and implicit OP protection bypass.
- Ensured cancellation, missing permissions, exhausted limits, changed keys, invalid worlds, and insufficient inventory capacity consume zero keys.

### Testing and release

- Expanded unit and MockBukkit coverage for provider resolution, exact matching, key consumption, SQLite atomicity, migration rollback/idempotency, limits, pity, journaled openings, import/export, GUI capture, full reward editing, reload rollback, and Link Wand persistence/protection.
- Retained Java 25 CI and immutable GitHub release packaging with a shaded JAR and SHA-256 checksum.

## 1.0.0 — 2026-09-01

### Added

- Original Paper 26.2 crate engine created by Tonim (ZpkDxGames).
- Basic, Rare, Epic, and Legendary default crates with balanced 100-weight reward pools.
- Live PlexonKeys CONFIG/CAPTURED item integration and exact fallback templates.
- Physical crate blocks, previews, single/bulk openings, weighted rewards, and roulette animation.
- Exact captured item rewards and console-command rewards.
- Native TextDisplay holograms, particles, and linked-block protection.
- Player browser and in-game administration menus.
- Atomic configuration/location edits, safe inventory planning, overflow delivery, opening logs, and statistics.
- Maven tests, Java 25 CI, and automated GitHub release packaging.

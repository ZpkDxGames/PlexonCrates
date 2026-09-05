# Changelog

All notable PlexonCrates changes are documented here.

## 3.0.0 — In development

### Foundation

- Began the 3.0 runtime from the released `v2.0.0` baseline without removing its opening, key, migration, or exact-item safety tests.
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

### Documentation

- Adopted the expanded 3.0 implementation specification, including the one-edition/unlimited-definition contract and the original PhoenixCrates-benchmarked GUI usability boundary.

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

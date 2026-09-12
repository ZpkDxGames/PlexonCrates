# PlexonCrates 6.0 — GUI Architecture

## Design rules

PlexonCrates keeps inventory GUIs where visual browsing matters, uses lightweight action-bar/chat feedback for transient status, and avoids turning every setting into a separate chest workflow. Inventory identity is always carried by custom holders and bound actions; visible titles, lore, materials, and text are presentation only.

All inventory click/drag lifecycle events remain centralized through `CrateMenuEventRouter`. View-changing work is deferred until after the originating inventory event. Dynamic asynchronous results revalidate viewer/session/revision identity before updating or reopening a view.

## Player journey

The canonical path is:

`/crates -> Crate Hall -> Crate Preview -> quantity/selection -> confirmation -> opening -> result`

Secondary player destinations are part of the same product flow rather than separate transaction engines:

- `My Keys` — paginated physical/PlexonKeys/local virtual balances with compatible-crate navigation;
- `Claim Inbox` — existing durable exact-item overflow/recovery queue;
- `Opening History` — paginated read-only history with async loading, refresh/error/empty states, and crate-preview navigation.

Physical blocks and portable crates continue into the same preview/confirmation/opening authorities.

Player-facing controls distinguish browsing from mutation, physical/virtual payment availability, random/selective mode, single/bounded bulk opening, eligibility/cooldown/limit state, pending/recoverable rewards, and stale/unavailable state.

## Admin hierarchy

6.0 deliberately extends the existing administration hierarchy instead of introducing a second control plane:

`Admin Dashboard`

- `Crates`
  - Crate Studio
  - reward manager / Reward Builder
  - opening/display/access settings
  - milestones/rerolls
  - Test Lab
  - opening animation profiles
  - physical idle profiles
- `Physical Keys`
- `World Locations`
- `All Rewards`
- `Statistics`
- `System` / validation, reload, backup, diagnostics/recovery inspection

Crate-scoped Test Lab and animation controls stay inside Crate Studio so the active crate, durable draft/session ownership, and revision are explicit. A new top-level animation dashboard was intentionally not added because it would duplicate crate-selection/session authority.

## Exact-item administration

Exact ItemStack payloads are never decorated in place for UI diagnostics.

Normal administration now exposes read-only companion diagnostics:

- Physical Key manager fingerprints the **resolved exact key template** separately from the cosmetic key-list icon.
- Reward Builder keeps the exact capture slots `[10..16]` unchanged and uses a separate `Exact Item Diagnostics` companion control.
- Global reward browsing uses `displayCopy()` for the menu item while diagnostics inspect `itemCopies()` representing exact delivery templates.
- Test Lab exact-item audit shows an untouched item clone separately from its byte/fingerprint metadata.

Diagnostics include captured quantity, native serialized bytes, short SHA-256 fingerprint, foreign/custom-data presence, nested container-data presence, and effective maximum stack size. `ExactItemInspector` is backed only by native `ItemSnapshotCodec` capture; material/name/lore/plugin IDs are never reconstructed into canonical reward/key state.

## Test Lab

Test Lab is the non-granting verification workspace. It provides:

- publication/readiness summary;
- deterministic dry selection;
- bounded expected-vs-observed simulation;
- configured/player-context probability modes;
- analysis worker queue/activity diagnostics;
- exact-item audit;
- opening animation profile management and shared-renderer preview;
- physical idle profile management and one-frame bounded preview.

Opening preview first dry-selects a reward and then uses the same shared profile renderer as production. It never calls the opening transaction, consumes a key, writes a journal, grants the selected reward, or records statistics.

Idle preview renders at most one frame and has an additional 256-particle ceiling. It creates no repeating preview task.

## Player My Keys

`PlayerKeyMenuService` pages through the complete enabled key-definition set rather than imposing an arbitrary small cap. Balance sources remain authoritative:

- exact physical inventory count from `KeyService`;
- PlexonKeys wallet when that provider owns the virtual wallet;
- local SQLite virtual wallet via asynchronous database load.

Selecting a key opens its compatible published crates. No My Keys action consumes or grants keys.

## Player Opening History

Opening History reuses the existing bounded asynchronous history API. The GUI does not query SQLite synchronously on the primary thread. A pending load presents a loading state and the callback verifies the player is still online and still viewing the same history holder before replacing the view.

History entries are read-only. Selecting an entry only navigates to the currently published crate preview when that crate still exists.

## Reward management invariants

Reward create/edit/duplicate/copy/move/delete operations may alter authored reward configuration, but exact item payloads must not be reconstructed as a side effect. Existing registry copy/reorder paths preserve authored exact-item payloads. Dedicated architecture tests lock this behavior.

A replacement item is an explicit edit operation. Failed capture validation leaves previous draft state unchanged.

## Visual/runtime states

Dynamic lists/editors explicitly represent applicable states such as loading, empty, unavailable/error, stale revision/session, first/last page, disabled/archived, valid/publication-ready, warning, and destructive confirmation.

## Performance rules

GUI rendering remains event-driven. There are no repeating tasks per viewer/menu. Simulation, persistence, migration, profile serialization, history loads, and validation work remain outside the primary server thread where appropriate; only Bukkit-facing inventory/entity operations return to it.

Opening and physical presentation use the shared coordinators documented in `ANIMATIONS_6_0.md`.

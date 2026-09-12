# PlexonCrates 6.0 — GUI Architecture

## Design rules

PlexonCrates keeps inventory GUIs where visual browsing matters, uses lightweight action-bar/chat feedback for transient status, and avoids turning every setting into a chest menu. Inventory identity is always carried by custom holders and bound actions; titles, lore, materials, and display text are presentation only.

All inventory click/drag lifecycle events remain centralized through the 5.1 `CrateMenuEventRouter`. View-changing work is deferred until after the originating inventory event. Dynamic async results must revalidate viewer/session/revision identity before updating or reopening a view.

## Player journey

The canonical player path remains:

`/crates -> Crate Hall -> Crate Preview -> quantity/selection -> confirmation -> opening -> result`

Secondary destinations remain reachable from the player product flow, including Pending Rewards. Physical and portable crate interactions enter the same preview/confirmation/opening authorities instead of creating separate reward/payment engines.

Player-facing controls must continue to distinguish:

- browse/preview from mutation;
- physical and virtual payment availability;
- random versus selective opening;
- single versus bounded bulk opening;
- current eligibility/cooldown/limit state;
- pending or recoverable rewards;
- stale/unavailable state.

## Admin hierarchy

The existing admin dashboard and typed editor hierarchy remain the foundation rather than being duplicated. The intended 6.0 hierarchy is:

`Admin Dashboard`

- `Crates`
  - crate editor
  - rewards
  - opening/display/access settings
  - milestones/rerolls
  - Test Lab
- `Physical Keys`
- `World Locations`
- `All Rewards`
- `Statistics`
- `System`

The 6.0 work extends these surfaces incrementally while keeping durable draft ownership and publication revisions authoritative.

## Test Lab

The Test Lab is the non-granting verification workspace. It currently provides:

- publication/readiness summary;
- one deterministic dry selection;
- bounded expected-vs-observed simulations;
- configured/player-context probability modes;
- analysis worker queue/activity diagnostics;
- exact-item audit for the item held in the administrator's main hand.

The exact-item audit deliberately displays an untouched clone of the held item in one slot and places quantity, byte-size, SHA-256 fingerprint, custom-data, container-content, and stack-size information on separate companion controls. PlexonCrates must never add its own lore/name to the exact clone merely to display diagnostics.

## Reward management invariants

Reward create/edit/duplicate/copy/move/delete operations may alter authored reward configuration, but exact item payloads must not be reconstructed as a side effect. Existing `CrateRegistry` copy/reorder paths copy authored YAML leaf values rather than rebuilding ItemStacks. Dedicated architecture tests lock this behavior.

A replacement item is an explicit edit operation. Failed capture validation leaves the previous draft state unchanged.

## Visual states

Dynamic lists and editors should explicitly represent the states applicable to their surface:

- loading/processing;
- empty;
- unavailable/error;
- stale revision/session;
- first/last page;
- disabled/archived;
- valid/publication-ready;
- warning/destructive confirmation.

## Performance rules

GUI rendering must remain event-driven. Do not add repeating tasks per viewer or per menu. Async simulation, persistence, migration, and validation work stays outside the primary server thread; only Bukkit-facing inventory/entity operations return to it.

Animations are presentation only and use shared coordinators documented in `ANIMATIONS_6_0.md`.

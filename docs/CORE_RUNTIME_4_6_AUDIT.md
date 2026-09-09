# PlexonCrates 4.6 Core Runtime Listener Audit

Baseline: PlexonCrates 4.5.0 (`main` at `7efc9bd131d2e3427764fa04fcada43ad2f950e5`).

Verified dependency API: PlexonCore 2.0.0. Its released `CoreEventGateway` currently exposes a shared `BlockBreakEvent` subscription only. It does not expose `PlayerInteractEvent`, mutable interaction cancellation, or LOWEST/HIGH interaction phase parity. Therefore the 4.6 interaction and protection handlers remain plugin-owned, as required by the migration safety gate.

| Handler | Event | Frequency | 4.5 role | 4.6 ownership | Reason |
|---|---|---:|---|---|---|
| `WandService.interact` | `PlayerInteractEvent` LOWEST | high | wand identity/admin flow | LOCAL optimized | Core 2.0 has no equivalent mutable interaction gateway; preserves immediate cancellation and LOWEST ordering |
| `CrateListener.interact` | `PlayerInteractEvent` HIGH | high | portable + linked crate | LOCAL optimized | preserves `ignoreCancelled=true` and HIGH ordering |
| `CrateListener.breakBlock` | `BlockBreakEvent` HIGH | high | linked-block protection | LOCAL | Core gateway is HIGHEST immutable acquisition; it cannot cancel the original Paper event |
| `blockExplosion` | `BlockExplodeEvent` | medium | protection | LOCAL | requires mutation of Paper block list |
| `entityExplosion` | `EntityExplodeEvent` | medium | protection | LOCAL | requires mutation of Paper block list |
| `pistonExtend` | piston | medium | source/destination protection | LOCAL | exact Paper cancellation semantics retained |
| `pistonRetract` | piston | medium | source/destination protection | LOCAL | exact Paper cancellation semantics retained |
| `entityChangesBlock` | `EntityChangeBlockEvent` | medium | protection | LOCAL | safety-critical cancellation |
| `blockBurns` | `BlockBurnEvent` | low | protection | LOCAL | safety-critical cancellation |
| `blockFades` | `BlockFadeEvent` | low | protection | LOCAL | safety-critical cancellation |
| `bucket` | `PlayerBucketEmptyEvent` | low | protection | LOCAL | safety-critical cancellation |
| provider enabled/disabled | plugin lifecycle | rare | PlexonKeys discovery | LOCAL | domain discovery, not a Core hot path |
| chunk load/unload | chunk lifecycle | medium | display reconciliation | LOCAL | existing indexed display path is already bounded |

## 4.6 interaction acquisition

The two Paper interaction priorities remain intentionally separate. Duplicate metadata work is removed by disjoint material gates:

- Wand path acquires metadata only for `BLAZE_ROD` items with metadata.
- Portable path acquires metadata only for `CHEST` items with metadata.
- A normal item can therefore pass both listeners without any PlexonCrates `ItemMeta` acquisition.
- A wand cannot trigger portable metadata inspection, and a portable chest cannot trigger wand metadata inspection.
- Wand selected-crate data is read from the same PDC acquired for identity validation.
- Portable marker/schema/token are read once into an envelope and the verified token is reused by durable verification.

## Core mode terminology

- `CORE_RUNTIME`: compatible Core 2.x API detected and module registered. Interaction/protection ownership is still LOCAL in 4.6 because the released Core 2.0 contract is insufficient for parity.
- `CORE_LEGACY`: compatible Core 1.x module API detected; optimized local listeners are used.
- `STANDALONE`: Core absent, disabled, unavailable, or outside `>=1.0 <3.0`; optimized local listeners are used.

This audit deliberately does not move crate transaction logic, location mappings, portable authentication, public crate events, exact items, or SQLite state into PlexonCore.

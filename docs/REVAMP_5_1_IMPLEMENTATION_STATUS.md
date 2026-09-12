# PlexonCrates 5.1 Revamp Implementation Status

Baseline: `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`

Current branch: `revamp/5.1.0-full-source-hardening`

## Implemented

- Player Crate Hall sessions now track explicit player ownership and clear view/submission state on close and disconnect.
- Accepted `PLAYER_*` inventory clicks are cancelled immediately, then dispatched on the next server execution point with a second exact session/revision validation before any navigation or opening submission.
- `GuiSessionService` is now a pure session authority. Player-router and simulation-listener registration moved to explicit plugin composition, and the simulation executor is explicitly closed during plugin shutdown.
- A shared `OpeningAnimationCoordinator` has been introduced as the replacement boundary for per-opening roulette tasks. Wiring into `MenuService` remains gated on compile verification of the preceding lifecycle slice.

## Still open

- Wire roulette rendering to the shared coordinator and remove per-opening repeating `BukkitRunnable` creation.
- Consolidate overlapping legacy/player inventory routing where safe.
- Remove blocking reload/validate/diagnose database/file work from live command paths.
- Move Phoenix migration filesystem/hash/import/report work behind an asynchronous operation boundary.
- Move optional published YAML mirror writes off the primary thread while keeping SQLite/runtime publication authoritative.
- Complete runtime interaction, restart/persistence, and spark comparison gates before release promotion.

# PlexonCrates 5.1 Revamp Implementation Status

Baseline: `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`

Current branch: `revamp/5.1.0-full-source-hardening`

## Implemented

- Player Crate Hall sessions track explicit player ownership and clear view/submission state on close and disconnect.
- Accepted `PLAYER_*` inventory clicks are cancelled immediately, then dispatched on the next server execution point with a second exact session/revision validation before any navigation or opening submission.
- `GuiSessionService` is a pure session authority. Player-router and simulation-listener registration live in explicit plugin composition, and the simulation executor is explicitly closed during plugin shutdown.
- Roulette animations use one shared `OpeningAnimationCoordinator` rather than one repeating Bukkit task per opening. The coordinator starts on demand, stops when empty, and is explicitly stopped during plugin disable.
- Crate inventory lifecycle events have one registered authority, `CrateMenuEventRouter`. Player and legacy/admin renderers remain separate but no longer compete as independent Bukkit inventory listeners.
- Live admin reload entry points use `requestReload(...)`: canonical SQLite definitions, drafts, and keys are preloaded asynchronously, then validation/application and Bukkit-facing reconciliation run on the primary thread. The old synchronous `reloadFor(...)` remains only as a compatibility/test path.
- Admin diagnostics move journal/count/portable database probes off the primary thread and marshal only final message rendering back to Bukkit's primary thread.
- Post-reload PlexonCore health probing is asynchronous, preventing a synchronous journal-count query from being reintroduced after an otherwise non-blocking reload.
- Source-contract tests cover player session ownership, deferred GUI dispatch, explicit runtime composition, single inventory routing authority, shared roulette scheduling, live reload threading, diagnostic threading, and asynchronous Core health refresh.

## Current verification boundary

- The inventory-routing slice passed the full canonical Build workflow on head `9d1901fc...`.
- The reload/diagnostic threading slice compiled and all new threading tests passed; one pre-existing player-UX source-contract assertion still referenced the former `CrateMenuEventRouter(menus, playerRouter)` constructor.
- That stale assertion has been corrected to the explicit plugin composition form `CrateMenuEventRouter(this, menus, playerRouter)` on head `572252a6...`.
- A fresh canonical Build gate is required before additional production-source work is stacked on this checkpoint.

## Still open

- Move Phoenix migration scan/hash/plan/import/report work behind explicit asynchronous filesystem/database boundaries without moving Bukkit/plugin-registry access off the primary thread.
- Move optional published YAML mirror writes off the primary thread while keeping SQLite/runtime publication authoritative.
- Re-audit remaining live command surfaces for synchronous filesystem/database work and either harden or explicitly classify startup/shutdown-only blocking paths.
- Complete interaction, restart/persistence, and spark comparison gates on the real PlexonCraft runtime before release promotion.
- Update version/release documentation only after source CI and runtime certification pass.

## Release boundary

Do not merge this branch into `main`, tag 5.1.0, or publish a stable JAR until canonical CI is green and the required PlexonCraft runtime/persistence/performance gates are complete. `v5.0.0` remains the rollback boundary.

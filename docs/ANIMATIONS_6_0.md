# PlexonCrates 6.0 — Animation Architecture

## Boundary

Animation is presentation only. Reward selection, eligibility, payment, key consumption, durable journal state, item/money/XP/command delivery, claims, pity, limits, milestones, rerolls and statistics remain owned by the existing opening/services pipeline.

An animation may show an already-authoritative selected reward. It may never select a replacement reward, consume payment, grant delivery, increment state, or finalize an opening transaction.

## Opening animation profiles

Opening profiles are stored in `animations.yml` and consumed through `OpeningAnimationProfileStore`.

Built-in styles:

- `INSTANT`
- `ROULETTE`
- `SPIN`
- `CHARGE_REVEAL`
- `SPIRAL_BURST`
- `ORB_REVEAL`
- `CASCADE`
- `FIREWORK_STYLE`

Every profile defines explicit durations for `START`, `CHARGE`, `SELECTION`, `REVEAL`, `CELEBRATION`, and `FINISH` plus particle, sound, volume, pitch, per-tick particle budget, receiver range, and optional summary-on-finish behavior. Validation bounds timing, duration, audio, budget, and range before runtime use.

### Assignment and migration behavior

`animations.yml` supports named profiles, one global assignment, and per-crate assignments. `legacy` is reserved inheritance: it projects the crate's existing 5.x `AnimationType` (`INSTANT`, `ROULETTE`, `REVEAL`, `SUMMARY`) into a compatible 6.0 profile. The bundled file uses `global-profile: legacy`, so upgrading does not silently change existing opening presentation.

Administrators can explicitly assign a named profile globally or per crate, return a crate to global inheritance, preserve legacy globally/per crate, clone an effective profile, or reset editable values.

## Shared opening coordinator

`OpeningAnimationCoordinator` remains the single repeating-task authority for opening animations.

- one shared Bukkit scheduler task, never one repeating task per opening/player/profile;
- active sessions are main-thread confined;
- only changed rail/presentation slots are updated;
- receiver-scoped particles are bounded by the profile and a coordinator-wide global ceiling;
- sounds/particles fail as cosmetic presentation only;
- closed, stale or offline viewers are removed promptly;
- completion callback runs through one terminal path;
- `INSTANT` profiles complete without entering the repeating scheduler.

`OpeningProfilePresentationService` prepares the ordinary opening inventory surface and delegates timed presentation to that coordinator. It owns no transaction state.

## Production integration

`OpeningService` resolves the effective profile only at the finalized result-presentation boundary, after reward delivery and durable completion work has succeeded.

For single openings, global animation disable preserves immediate announcement, non-animated profiles announce immediately, and animated profiles use the shared renderer before the normal result announcement. Cosmetic failure falls back to the already-delivered result. Bulk openings remain non-animated and may open the summary according to `summary-on-finish` or the existing bulk summary threshold.

No profile callback reaches reward selection, payment, journal, grant, pity/limit, or statistics authority.

## Test Lab opening preview

Test Lab first performs deterministic `CrateSimulationService.dryRun` selection and then resolves the same effective opening profile used by production.

- zero-duration profiles stay on the non-granting dry-result surface;
- animated profiles call the same `OpeningProfilePresentationService` as production;
- closing/leaving the opening preview prevents Test Lab from force-reopening itself afterward;
- Test Lab never calls the production opening transaction, consumes keys, writes journals, records statistics, or grants the previewed reward.

This keeps preview fidelity high without creating a second animation engine.

## Physical idle profiles

Idle presentation is stored separately in `idle-animations.yml` and consumed through `IdleAnimationProfileStore`.

Built-in styles:

- `NONE`
- `SPARKLE`
- `RING`
- `DOUBLE_RING`
- `ORBIT`
- `HELIX`
- `PULSE`
- `RISING`
- `AURA`

Each idle profile includes particle, radius/height, geometry point count, rotation/vertical speed, particles per point, receiver range, and per-crate/per-viewer particle ceilings. `IdleAnimationMath` generates deterministic offsets independently of Bukkit calls; `CrateParticleCoordinator` performs actual runtime emission.

### Idle assignment and migration behavior

`idle-animations.yml` has the same product assignment model: named profiles, global assignment, and per-crate assignment. Reserved `legacy` means “use the already-validated `particles.*` profile from `config.yml`.” The bundled registry defaults to `global-profile: legacy`, so upgrading alone does not change existing physical crate effects.

The existing `particles.enabled`, scheduler interval, maximum locations per tick, global particle ceiling, and stagger settings remain master runtime safety controls. Named idle profiles refine per-crate geometry/range/local budgets underneath those ceilings.

## Shared physical particle coordinator

There is exactly one `CrateParticleCoordinator` scheduler. Each pass derives candidates through `LocationStore.nearbyChunks`, rejects unloaded worlds/chunks and missing/disabled crates, resolves the effective named/legacy idle profile, samples deterministic geometry, emits only to nearby receivers with profile budgets, and enforces the existing coordinator-wide global particle/location ceilings.

There is no task per crate, linked block, player, or idle profile.

## Idle profile editor and preview

The Test Lab/Crate Studio idle editor supports named profile browsing, style and safe-particle selection, radius/height/point count, rotation/vertical speed, particles-per-point, receiver range, per-crate/per-viewer budgets, clone/reset, global/per-crate assignment, and explicit legacy inheritance.

Preview renders one geometry frame around the administrator and is additionally capped at 256 particles. It creates no repeating preview task and never touches opening state.

## Persistence/threading

Both profile stores keep immutable live snapshots and serialize changes through ordered off-thread writes with atomic file replacement where supported. Animation ticks consume prepared in-memory values only; they perform no file, database, or network I/O.

SQLite remains schema `4` because profile configuration is presentation-only and has no durable player/transaction semantics.

## Runtime certification

The exact 6.0 RC must be exercised with multiple linked crates/viewers and repeated opening animations on the real Paper 26.2 host. Capture TPS/MSPT/scheduler evidence under idle and active presentation load and compare it with the accepted baseline. Unbounded task growth, forced chunk loading, or material TPS/MSPT regression fails certification.

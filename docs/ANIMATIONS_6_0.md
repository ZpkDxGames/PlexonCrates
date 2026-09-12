# PlexonCrates 6.0 — Animation Architecture

## Principle

Animations are presentation only. Reward selection, payment, durable journal state, limits, pity, claims, rerolls, and delivery remain owned by the existing opening transaction pipeline. Closing a GUI or losing a cosmetic effect must never change the selected reward or cause a second grant/payment path.

## Physical crate idle effects

6.0 ships bounded idle styles:

- `NONE`
- `SPARKLE`
- `RING`
- `DOUBLE_RING`
- `ORBIT`
- `HELIX`
- `PULSE`
- `RISING`
- `AURA`

`IdleAnimationMath` is pure deterministic geometry. `CrateParticleCoordinator` is the only scheduler for linked-crate idle particles.

### Scheduler contract

- one shared Bukkit repeating task for all physical crate idle effects;
- no task per crate;
- no task per player;
- no task per chunk;
- candidate crates come from `LocationStore`'s existing world/chunk indexes;
- unloaded chunks are skipped;
- disabled/unpublished crates are skipped;
- receivers are scoped to nearby online players;
- emissions use player-scoped particle delivery rather than world-wide broadcasting;
- a particle API failure disables the cosmetic coordinator without touching crate state.

### Budgets

`config.yml` exposes:

- `performance.particles.max-locations-per-tick`
- `performance.particles.max-particles-per-tick`
- `performance.particles.max-per-crate-per-tick`
- `performance.particles.max-per-viewer-per-tick`
- staggered candidate rotation.

If a budget is exhausted, cosmetic work is deferred/dropped for that pass. Transaction behavior is unaffected.

## Opening GUI animation

The accepted 5.1 `OpeningAnimationCoordinator` remains the scheduling authority for opening roulette presentation. It owns one task for all active opening animations and stops when the active set becomes empty.

6.0 may add richer profile-driven presentation, but new styles must reuse this shared authority or one-shot main-thread callbacks. A new visual style is not allowed to create an independent reward-selection or payment path.

## GUI motion

Menu motion is intentionally subtle. Animation may update decorative or presentation slots only; stable functional controls and holder/action identity remain authoritative. No animation may parse visible lore/title text as state.

## Cleanup

Reload/disable must cancel shared coordinators and clear transient animation state. Chunk unload removes hologram entities for that chunk; particle work relies on the indexed linked-location set and loaded-chunk checks rather than persistent particle entities.

## Runtime certification

The 6.0 RC must be tested with multiple linked crates and multiple nearby viewers. Capture spark/MSPT evidence while idle particles and repeated opening animations are active, and compare it with the accepted 5.0/5.1 baseline. A cosmetic feature that produces unbounded scheduler growth or material TPS/MSPT regression fails certification.

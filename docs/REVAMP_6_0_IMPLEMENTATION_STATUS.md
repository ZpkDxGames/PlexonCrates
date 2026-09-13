# PlexonCrates 6.0 Full-Revamp Implementation Status

## Source lineage

- Stable rollback: `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`
- Published 5.1 runtime candidate: `v5.1.0-rc.1`
- 5.1 RC source: `015bc8fae37c0f6ebf7fc4161a33b3f103b46a7b`
- 6.0 base: `a709e5821516fe674b2b7c26c39e1a78851737ff`
- 6.0 branch: `revamp/6.0.0-gui-animation-overhaul`
- 6.0 PR: `#15`
- Paper target: `26.2.build.121-stable`
- Java target: `25`
- SQLite schema: `4`
- Stable runtime certification: **NOT EXECUTED**

The 6.0 branch is built on the accepted 5.1 hardening line. It preserves the existing journal/payment/recovery pipeline, durable drafts/publication revisions, PlexonKeys integration, schema-4 storage, bounded I/O, centralized GUI routing and rollback boundary.

## Implemented 6.0 product slices

### Exact-item fidelity and diagnostics

- `ItemSnapshotCodec` remains the only canonical item authority: native Paper bytes, SHA-256 verification and native restore.
- `ExactItemInspector` exposes amount, serialized byte size, fingerprint, custom-data/container flags and maximum stack size without mutating the source ItemStack.
- Reward/key capture validates exact native round-trip semantics before changing draft state.
- The normal key manager displays diagnostics from the **resolved exact key template**, not from its cosmetic menu icon.
- Reward Builder keeps its seven exact input slots untouched and exposes a separate read-only `Exact Item Diagnostics` companion control.
- Global reward browsing reports diagnostics from reward delivery copies while continuing to use `displayCopy()` for menu presentation.
- Test Lab includes an exact-item audit that displays an untouched clone separately from diagnostic controls.
- Architecture and MockBukkit tests lock native bytes as source of truth and verify diagnostic presentation does not rewrite item metadata.

### Player product flow

- Crate Hall/detail/preview/opening authorities remain canonical.
- `My Keys` is a first-class paginated GUI backed by authoritative physical/PlexonKeys/local-wallet balances and crate compatibility.
- Opening History is a paginated read-only GUI backed by the existing asynchronous history API, with loading/error/empty/refresh states and crate-preview navigation.
- Pending exact claims/recovery remain on the existing durable Claim Inbox authority.
- Player surfaces continue through the single `CrateMenuEventRouter`; no parallel inventory listener was introduced.

### Opening animation profiles

- Built-in styles: `INSTANT`, `ROULETTE`, `SPIN`, `CHARGE_REVEAL`, `SPIRAL_BURST`, `ORB_REVEAL`, `CASCADE`, `FIREWORK_STYLE`.
- Explicit stages: `START`, `CHARGE`, `SELECTION`, `REVEAL`, `CELEBRATION`, `FINISH`.
- Immutable validated profiles include stage timings, particle/sound settings, receiver range, particle budget and optional summary-on-finish.
- `animations.yml` provides named profiles, global assignment and per-crate assignment.
- Reserved assignment `legacy` preserves each crate's existing 5.x `AnimationType` until an administrator deliberately overrides it.
- The in-game profile editor supports style/stage/particle/sound/volume/pitch/budget/range controls, clone/reset, global/per-crate assignment and legacy inheritance.
- Production `OpeningService` resolves the effective profile only after durable delivery/finalization and delegates presentation to the shared renderer.
- Test Lab uses the **same profile renderer** as production after deterministic dry selection; it never consumes payment or grants the previewed reward.
- One shared `OpeningAnimationCoordinator` owns repeating animation ticks; close/offline/stale views terminate presentation and completion remains single-path.

### Physical idle animation profiles

- Built-in idle styles: `NONE`, `SPARKLE`, `RING`, `DOUBLE_RING`, `ORBIT`, `HELIX`, `PULSE`, `RISING`, `AURA`.
- Geometry is deterministic and separated from Bukkit particle calls.
- `idle-animations.yml` provides named profiles, global/per-crate assignment and migration-safe `legacy` inheritance from accepted `particles.*` settings.
- The shared particle coordinator resolves one effective profile per linked crate while preserving global per-tick ceilings, indexed nearby-chunk discovery, loaded-chunk checks and receiver scoping.
- The idle-profile editor supports style, particle, radius, height, points, rotation/vertical speeds, particles-per-point, range, per-crate/per-viewer budgets, clone/reset and assignments.
- Its preview renders one bounded frame only (maximum 256 particles) and creates no repeating preview task.

### Admin/Test Lab workflow

- Existing Admin Dashboard / Crate Studio / Key Manager / Reward Manager / Locations / Statistics / System hierarchy is retained instead of creating duplicate authorities.
- Crate Studio is the crate-scoped entry to Test Lab and animation profile editing so crate selection/session/revision ownership stays unambiguous.
- Test Lab includes readiness, deterministic dry selection, bounded simulation, configured/player probability modes, analysis-worker diagnostics, exact-item audit and profile-aware opening preview.
- Reward/key management remains on durable draft/key mutation services; 6.0 adds diagnostics and usability without moving persistence into GUI code.

## Preserved authorities

6.0 does **not** move or duplicate:

- `OpeningService` transaction authority;
- journal/payment/revalidation/grant ordering;
- reward selector, limits, pity, cooldown, milestones or reroll semantics;
- claim/recovery/manual-review authority;
- PlexonKeys payment/template provenance;
- SQLite canonical definitions/player state;
- durable draft/publication revisions;
- portable issuance/signature/replay protection;
- Phoenix migration behavior;
- the centralized inventory router.

No SQLite schema increment was required for the cosmetic profile registries.

## Latest accepted source checkpoint before version freeze

`be8de69668c74299c181ac711b4f7e16601453b5`

Canonical PR Build `34718186939`:

- **348 tests**
- **0 failures**
- **0 errors**
- **0 skipped**
- Java 25 / class major 69
- Paper `26.2.build.121-stable`
- packaging/distribution checks PASS
- provided-dependency non-shading checks PASS
- provenance generation PASS
- `git diff --check` PASS

The artifact at this checkpoint is intentionally still versioned `5.1.0-rc.1`; it is a source-completeness checkpoint, not the 6.0 release candidate.

## Remaining work before RC publication

1. reconcile 6.0 GUI/animation/migration/changelog documentation with the completed implementation;
2. bump the source candidate to `6.0.0-rc.1` and add release notes;
3. run canonical CI on the exact versioned source and freeze that SHA;
4. publish an immutable GitHub prerelease with JAR, `SHA256SUMS.txt`, `TEST_SUMMARY.txt` and `PROVENANCE.txt`;
5. verify the release tag target and downloaded asset checksum.

## Runtime/stable gate

Source CI is not runtime certification. The exact published `v6.0.0-rc.1` JAR must be installed on the real PlexonCraft Paper 26.2 host and pass startup, player/admin GUI, exact custom-item persistence/restart/claim, opening/idle animation load, recovery/migration and active-use performance/soak gates.

Until that evidence exists, the maximum truthful release state is:

`RC RELEASED / RUNTIME PENDING`

Stable `v6.0.0` remains forbidden before the exact RC runtime boundary is certified.

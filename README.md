# PlexonCrates

[![Paper](https://img.shields.io/badge/Paper-26.2-2f3136?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820?style=for-the-badge)](https://adoptium.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/ZpkDxGames/PlexonCrates/build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/ZpkDxGames/PlexonCrates/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonCrates?style=for-the-badge)](https://github.com/ZpkDxGames/PlexonCrates/releases/latest)

PlexonCrates is the exact-item, percentage-first crate system for Paper 26.2 and the Plexon plugin family. The stable rollback boundary remains **5.0.0** while the 6.0 line is certified through an immutable release candidate before stable promotion.

6.0 keeps the mature opening transaction/recovery core and concentrates on the product/control plane: first-class player navigation, exact-item diagnostics, reusable opening profiles, physical idle profiles, shared bounded animation coordinators, and expanded regression coverage.

This is an independent implementation inspired by general crate-system usability goals. No third-party crate plugin source code is included or modified.

## Runtime baseline

- Paper `26.2.build.121-stable`
- Java `25`
- PlexonCore `2.0.4` as an optional provided runtime integration
- PlexonKeys API baseline `2.0.0-rc.2` as a provided dependency
- Vault + economy provider only when money-backed features are configured
- PlaceholderAPI only when external placeholders are used
- SQLite schema `4`, owned by PlexonCrates

Canonical CI downloads the exact PlexonCore and PlexonKeys release JARs and verifies their SHA-256 values before compilation. PlexonCore, PlexonKeys, Vault, PlaceholderAPI, LuckPerms, Bukkit/Paper APIs and Adventure are not shaded into PlexonCrates. SQLite JDBC is bundled.

## 6.0 player experience

`/crates` opens the Crate Hall. Browsing/previewing are non-consuming; payment is submitted only through an explicit opening confirmation.

Player surfaces include:

- Crate Hall and exact reward preview;
- random Open 1 and bounded Open More flows;
- selective reward confirmation;
- linked physical crate preview/open;
- **My Keys**, with authoritative physical/PlexonKeys/local-wallet balances and compatible-crate navigation;
- **Opening History**, paginated and loaded asynchronously;
- Pending Rewards / durable Claim Inbox;
- milestone and reroll state;
- signed portable-crate preview/confirmation.

Random reward presentation uses the player's current eligible pool and distinguishes configured base chance. Selective choices do not fabricate random probabilities. Internal transaction/recovery identifiers stay outside ordinary player presentation.

## Opening authority and durability

Correctness remains centralized in `OpeningService`:

`validate -> plan -> journal -> revalidate -> payment -> grant -> durable completion -> presentation`

Important properties:

- one accepted opening at a time per player through explicit locking;
- immutable opening plans tied to a runtime crate revision;
- reward eligibility, limits, pity, milestones, capacity and permissions revalidated before consumption;
- physical, local virtual-key and PlexonKeys wallet payment paths are journaled/idempotent;
- uncertain payment/grant boundaries remain manual-review eligible instead of being guessed or blindly retried;
- history/statistics are committed only through the authoritative transaction path;
- animations run only after authoritative selection/delivery and never own payment or reward state.

## Exact items

Exact reward/key custody uses Paper native ItemStack bytes rather than material/name/lore matching.

`ItemSnapshotCodec` captures native `serializeAsBytes()` data, stores quantity separately, verifies SHA-256, restores through Paper native decoding, and respects the restored item's real maximum stack size. Third-party PDC/components and nested container/block-state contents remain opaque exact data.

6.0 adds read-only diagnostics without changing item authority:

- Physical Key manager fingerprints the resolved exact key template, not its cosmetic icon.
- Reward Builder keeps exact capture slots untouched and shows a separate diagnostics companion.
- Global reward browsing uses presentation copies while diagnostics inspect delivery copies.
- Test Lab can audit an untouched item clone separately from byte/fingerprint metadata.

Diagnostics never reconstruct canonical items from visible metadata.

## Opening animation profiles

`animations.yml` adds reusable presentation-only profiles with:

- styles `INSTANT`, `ROULETTE`, `SPIN`, `CHARGE_REVEAL`, `SPIRAL_BURST`, `ORB_REVEAL`, `CASCADE`, `FIREWORK_STYLE`;
- stages `START`, `CHARGE`, `SELECTION`, `REVEAL`, `CELEBRATION`, `FINISH`;
- particle/sound settings, receiver range, bounded particle budget and optional summary-on-finish;
- named global/per-crate assignment and an in-game editor.

The bundled assignment is `global-profile: legacy`. `legacy` projects each crate's existing 5.x animation value, so installing 6.0 does not silently change established presentation.

Production and Test Lab previews use the same shared `OpeningAnimationCoordinator`. There is no repeating task per opening/player/profile.

## Physical idle profiles

`idle-animations.yml` adds named physical-crate particle profiles with style, particle, radius/height, points, rotation/vertical speed, particles-per-point, receiver range and per-crate/per-viewer ceilings.

The bundled global assignment is also `legacy`, which preserves the accepted `particles.*` behavior from `config.yml` until an administrator explicitly selects another profile.

One `CrateParticleCoordinator` owns all repeating idle-particle work. Candidate linked crates come from indexed nearby chunks; unloaded chunks are skipped; emissions are receiver-scoped; global location/particle ceilings from `config.yml` remain master safety bounds.

The in-game idle editor can clone/reset/assign profiles and render one bounded preview frame without creating another scheduler.

## Administration and Test Lab

The existing administration hierarchy remains authoritative rather than being duplicated:

- Crates / Crate Studio
- Physical Keys
- World Locations
- All Rewards
- Statistics
- System / validation, backup, reload and diagnostics

Crate Studio retains crate-scoped Test Lab and animation controls so durable draft/session/revision ownership is explicit.

Test Lab provides publication readiness, deterministic dry selection, bounded expected-vs-observed simulation, configured/player probability modes, analysis-worker diagnostics, exact-item audit, production-renderer opening preview and bounded idle-profile preview. Test Lab does not consume keys, grant its preview selection, or mutate opening statistics/state.

## Install / upgrade

For stable production use, download the currently promoted stable release. For 6.0 runtime certification, use the **exact published 6.0 RC artifact**, not a local rebuild.

1. Back up `plugins/PlexonCrates/`, including `data/plexoncrates.db`.
2. Download the JAR and `SHA256SUMS.txt` from the matching GitHub release.
3. Verify the JAR with `sha256sum --check SHA256SUMS.txt`.
4. Replace the previous PlexonCrates JAR and retain the existing data directory.
5. Start Paper 26.2 on Java 25.
6. Run `/pcrates validate` and `/pcrates diagnose` before reopening normal player traffic.
7. For a 6.0 RC, complete `TESTING.md` against that exact tagged JAR and retain the tag/source/JAR SHA-256 evidence.

6.0 retains SQLite schema 4. New `animations.yml` and `idle-animations.yml` are presentation-only and default to migration-safe `legacy` assignments.

Rollback remains `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f` until 6.0 stable promotion.

## Common administration commands

| Command | Purpose |
|---|---|
| `/pcrates` | Open the administration dashboard |
| `/pcrates create <id>` | Create a durable crate draft |
| `/pcrates edit <crate>` | Open Crate Studio |
| `/pcrates publish <crate>` | Validate and atomically publish the current draft |
| `/pcrates keys` | Manage exact physical-key definitions/providers |
| `/pcrates wand [crate]` | Obtain the Link Wand |
| `/pcrates portable give <player|uuid> <crate> [amount]` | Issue signed portable crates |
| `/pcrates virtualgrant <player|uuid> <key> <amount>` | Credit an audited virtual-key balance |
| `/pcrates rerolls <give|take|set> <player|uuid> <amount>` | Adjust audited reroll-token state |
| `/pcrates validate` | Validate configuration without activation |
| `/pcrates reload` | Validate and atomically activate configuration |
| `/pcrates backup` | Create a consistent backup |
| `/pcrates status` | Show concise runtime state |
| `/pcrates diagnose` | Show provider/schema/queue/journal/claim/portable health |

See `docs/GUI_6_0.md`, `docs/ANIMATIONS_6_0.md`, `docs/MIGRATION_6_0.md`, `docs/API.md`, `docs/CUTOVER.md` and `TESTING.md` for detailed contracts and certification procedures.

## Performance boundaries

- no database query per reward card;
- no repeating GUI refresh task;
- no repeating task per crate/player/opening/profile;
- GUI/Bukkit inventory mutation stays on the primary thread;
- SQLite/profile persistence work is bounded/off-thread where applicable;
- linked-crate particles use indexed nearby candidates and loaded-chunk checks;
- opening and idle particles are receiver-scoped and budgeted;
- simulation/analysis never enters live payment/grant mutation;
- immutable published runtime snapshots keep player paths outside writable draft state.

## Public integration surface

`PlexonCratesApi` remains registered through Bukkit services. Public lifecycle/opening events remain in `com.antondev.crates.api.event`, including post-success `CrateOpenEvent`.

PlexonCore is lifecycle/integration infrastructure; PlexonCrates retains ownership of definitions, opening transactions, exact items, SQLite state and player interaction semantics.

## Build and release verification

Build locally with:

```bash
mvn -B -ntp clean verify
```

Canonical CI verifies accepted lineage, Java 25/class major 69, exact Paper/Core/Keys dependency boundaries, a non-empty zero-skip test suite, required distribution contents, prohibited provided-dependency shading, plugin/manifest version parity, SHA-256 generation, exact source provenance and whitespace.

A 6.0 prerelease is published only from an exact green source boundary and is marked `runtime_certification=NOT_EXECUTED`. Stable `v6.0.0` is forbidden until the exact published RC passes real PlexonCraft Paper 26.2 startup, GUI/opening, exact-item persistence/restart/claim, migration/recovery and active-use performance/soak certification.

## License

PlexonCrates is available under the MIT License.

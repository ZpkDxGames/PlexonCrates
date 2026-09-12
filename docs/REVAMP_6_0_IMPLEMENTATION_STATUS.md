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
- SQLite schema: `4` unless a later accepted slice proves a migration necessary

The selected 6.0 base contains the published 5.1 RC source plus one documentation-only commit. The accepted 5.1 hardening work therefore remains inherited rather than being restarted.

## Completed 6.0 slices

### Repository audit

`docs/REVAMP_6_0_AUDIT.md` maps lifecycle/composition, transaction authority, persistence, item fidelity, GUI duplication, migration risks, performance hot paths, integrations, and feature viability decisions.

### Physical crate animation foundation

Implemented:

- built-in idle styles `NONE`, `SPARKLE`, `RING`, `DOUBLE_RING`, `ORBIT`, `HELIX`, `PULSE`, `RISING`, `AURA`;
- immutable validated idle profiles;
- deterministic geometry separated from Bukkit calls;
- one shared receiver-scoped particle coordinator;
- loaded-chunk/location-index candidate discovery;
- global, per-crate and per-viewer budgets;
- staggered candidate processing;
- runtime failure isolation;
- animation architecture and geometry tests.

Accepted checkpoint: head `409f2d651728c47810202302eba339c35dfa278a`, Build `34711417191` — PASS.

### Exact-item capture hardening

Implemented:

- `ExactItemInspector` backed exclusively by `ItemSnapshotCodec` native Paper bytes;
- diagnostics for captured amount, serialized bytes, SHA-256/short fingerprint, custom-data/container flags and maximum stack size;
- reward-item capture validates before transient draft mutation;
- physical-key template capture validates before replacement;
- no automatic reconstruction from Material/name/lore/plugin IDs;
- expanded synthetic third-party/PDC and nested-container regression fixtures;
- architecture tests locking native serialization/deserialization as the source of truth.

## Preserved authorities

Unchanged by these slices:

- `OpeningService` transaction authority;
- journal/payment/revalidation ordering;
- reward selector, limits, pity and cooldown semantics;
- claims/recovery and manual-review behavior;
- reroll and selective-opening state machines;
- PlexonKeys provider/payment provenance;
- durable draft/publication revisions;
- SQLite schema and canonical state;
- Phoenix migration behavior;
- portable issuance/signature/replay protection;
- existing shared opening roulette coordinator;
- 5.1 centralized GUI event routing and deferred view transitions.

## Remaining source work before RC freeze

- richer opening-animation profiles while retaining the shared coordinator;
- admin hierarchy and reward/key management UX consolidation;
- player navigation additions such as bounded My Keys/history surfaces where supported by existing data APIs;
- Test Lab animation/exact-item diagnostics integration;
- GUI exact-item companion diagnostics without mutating restored reward clones;
- final migration/config compatibility review;
- README/GUI/migration/testing/changelog/release documentation;
- complete canonical CI source freeze.

## Release gate

No `v6.0.0` stable release is permitted from source CI alone. The source-complete candidate must be versioned/published as an RC, installed on the real PlexonCraft Paper 26.2 host, and pass startup, GUI/opening, exact-item, persistence/restart, migration/recovery and active-use performance certification. Stable promotion must preserve the exact certified runtime source/artifact boundary.

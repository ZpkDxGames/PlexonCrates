# PlexonCrates 5.0.0 — Phase 2 Premium-Tier Product Overhaul

## Baseline

- Repository: `ZpkDxGames/PlexonCrates`
- Stable rollback: `v4.6.0`
- Phase 2 branch base: `d9d55402781fba971a9862976a4c6b853e143354`
- Paper: 26.2
- Java: 25
- PlexonCore: 2.0.4
- Target: `5.0.0`
- First candidate: `5.0.0-rc.1`

## Existing strengths to preserve

The live 4.6 line already has mature crate/opening/exact-item infrastructure. 5.0 must preserve and build on:

- exact ItemStack reward persistence;
- draft-backed crate editing and publication;
- reward creation/editing;
- explicit percentage/chance allocation rather than opaque relative weights;
- preview/opening UI;
- PlexonKeys integration and local key definitions;
- portable crate signing/replay protection;
- linked physical crate protection;
- opening journals/idempotency;
- pending claims/recovery;
- milestones and rerolls;
- public Bukkit API/events;
- SQLite ownership and migration history;
- Core 2.0.4 lifecycle integration;
- 4.6 interaction hot-path optimizations.

Do not rewrite these systems merely to justify a 5.0 number.

## Phase 2 product gaps

The 5.0 candidate focuses on the remaining premium/product closure:

1. coherent admin simulation/testing;
2. richer probability/readiness diagnostics in the editor;
3. safe dry-run reward-plan inspection without consuming a key or granting rewards;
4. repeatable Monte Carlo distribution simulation for administrators, off the primary thread;
5. explicit expected-vs-observed distribution report;
6. crate publication readiness summary integrated into simulation;
7. product-level documentation and migration/rollback evidence;
8. release workflow generalized for RC publishing without one-shot legacy publishers.

## Admin test surface

Add a dedicated crate test/simulation flow from the crate editor.

The surface must let an administrator:

- inspect a crate without consuming a key;
- see enabled/disabled reward count;
- see configured chance total / allocation state;
- see publication issues before testing;
- run one deterministic/non-granting dry selection where practical;
- run a bounded distribution simulation (default 10,000 rolls, configurable safe options);
- compare expected base percentages with observed simulated percentages;
- see absolute percentage-point deviation;
- abort stale simulation output if the crate draft/revision changes;
- never execute commands, economy grants, XP grants, item grants, milestones, rerolls, claims or opening journals during simulation.

## Simulation safety

Simulation is analytical only.

Never call the real delivery pipeline for Monte Carlo work.

Use a pure/bounded planner derived from the published crate reward selection semantics. If existing reward eligibility depends on player permissions or limits, report whether the simulation represents:

- raw configured distribution; or
- player-context eligible distribution.

Do not silently pretend restricted rewards are universally eligible.

## Threading/performance

- No per-player repeating task.
- No per-crate repeating task.
- No database write per simulation roll.
- No Bukkit ItemStack mutation off the primary thread.
- Snapshot immutable scalar reward probability data on the primary thread, then simulate asynchronously.
- Bound sample count (recommended maximum 100,000 per request).
- Use the plugin's existing bounded/shared async execution infrastructure if available.
- Return UI results on the primary thread.

## Probability clarity

The admin/editor/preview must distinguish:

- configured/base chance;
- effective eligible chance if eligibility reshaping applies;
- disabled/locked reward state;
- total allocation health;
- simulation observed percentage.

Do not reintroduce user-facing “weight” terminology for normal rewards.

## Exact item integrity

All preview/editor/simulation surfaces must clone/read existing exact ItemStacks only. Never serialize an item to lore and rebuild it as a reward. Preserve custom plugin PDC, attributes, enchantments, custom model data, components and other metadata.

## Transaction integrity

5.0 must not weaken real opening safety. Existing key reservation/payment, opening journal/idempotency, claim/recovery, command/economy/item delivery and rollback behavior remain authoritative.

Simulation/test actions must be unmistakably non-granting.

## UX language

Follow the PlexonTools-derived Phase 2 visual language:

- MiniMessage / non-italic stock items;
- restrained cyan/secondary gradients;
- gray structural labels and white values;
- clear success/warning/error states;
- stable Back/Close behavior;
- explicit click hints;
- confirmation before destructive actions.

## CI gates

Before an RC branch:

```text
mvn/Gradle clean test/check as defined by the repository
zero skipped failing tests
existing integration suites
new simulation unit/integration tests
JAR contract verification
Java 25 bytecode verification
Core/Keys non-shading verification
SHA-256 generation/verification
git diff --check
```

New tests must prove:

- simulation never calls delivery/grant paths;
- sample count is bounded;
- configured percentages produce statistically sane deterministic-seed output within a broad non-flaky tolerance;
- disabled rewards are excluded according to the selected simulation mode;
- simulation does not mutate crate definitions or reward ItemStacks;
- stale revision/session output is rejected;
- 4.6 opening/payment/idempotency tests still pass.

## Release policy

If source/CI/product gates pass but PlexonCraft runtime certification is unavailable, publish `v5.0.0-rc.1` as a GitHub prerelease with:

`RUNTIME CERTIFICATION NOT EXECUTED`

Stable 5.0.0 promotion requires runtime testing of:

- existing linked crate flow;
- Link Wand;
- exact custom ItemStack rewards;
- physical/PlexonKeys payment;
- portable crates;
- single/bulk/selective opening;
- rerolls/milestones/claims;
- new admin simulation UI;
- restart/persistence/migration;
- Spark/MSPT comparison;
- minimum 30-minute soak;
- no HIGH/CRITICAL known defects.

## Rollback

Rollback remains `v4.6.0`. Back up the full PlexonCrates data directory and SQLite database before 5.0 staging. 5.0 source work must not introduce a destructive migration merely for the simulation feature.

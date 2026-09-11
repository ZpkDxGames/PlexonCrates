# PlexonCrates 4.6.0 → 5.0.0 Migration / Rollback

## Scope

PlexonCrates 5.0.0 does not replace the 4.6 exact-item/opening/transaction architecture and does not introduce a destructive database migration for the Phase 2 simulation feature.

The 5.0 candidate adds an analytical admin layer only: dry-run selection, bounded probability simulation, expected-vs-observed reporting, publication-readiness diagnostics, and stale-revision protection.

## Before staging

1. Stop Paper cleanly.
2. Back up the complete `plugins/PlexonCrates` directory, including `data/plexoncrates.db`.
3. Keep the stable 4.6.0 JAR available for rollback.
4. Confirm PlexonCore 2.0.4 and the intended PlexonKeys provider are present.
5. Record `/pcrates diagnose` output from the 4.6.0 baseline.

## Candidate install

1. Replace the JAR with `PlexonCrates-5.0.0-rc.1.jar`.
2. Do not delete or regenerate crate YAML, key definitions, SQLite state, portable signing state, claims, opening journals, linked locations, or exact-item data.
3. Start Paper 26.2 on Java 25.
4. Run `/pcrates diagnose` and compare crate count, reward count, key provider, linked locations, database queue/journals, claim state, portable state, runtime snapshot, and Core module state to the baseline.

## Test Lab semantics

The editor's **Test & Simulate** entry is analytical only.

- Dry selection consumes no key and grants no reward.
- Simulation does not execute commands, economy, XP, item delivery, journals, claims, milestones, or rerolls.
- `CONFIGURED` mode uses enabled configured base chances and normalizes them into the same 10,000-ticket pool used by runtime selection.
- `PLAYER_CONTEXT` additionally snapshots the current administrator's permission/date eligibility on the primary thread.
- Dynamic reward limits and alternative fallback resolution are not executed by the simulator and are disclosed in the UI.
- A result is discarded if the crate's draft revision changed after the simulation snapshot was taken.

## Runtime certification matrix

Before stable promotion, verify on PlexonCraft:

- linked crate preview/open and protection;
- Link Wand link/unlink behavior;
- exact custom ItemStack/PDC/component rewards;
- physical and PlexonKeys payment;
- portable crate issue/open/replay protection;
- single/bulk/selective opening;
- rerolls, milestones, Claim Inbox and recovery;
- editor dry-run and 1k/10k/100k simulations;
- configured and player-context modes;
- publication issue rendering;
- stale output rejection after editing during a running simulation;
- restart persistence and representative 4.6 data;
- Spark/MSPT comparison;
- minimum 30-minute soak.

## Rollback

If a runtime gate fails:

1. Stop the server.
2. Replace the candidate JAR with stable `PlexonCrates-4.6.0.jar`.
3. If normal gameplay/admin changes occurred while testing 5.0, restore the pre-test PlexonCrates data backup for an exact rollback point.
4. Start the server and re-run `/pcrates diagnose`.

Because the Phase 2 Test Lab introduces no destructive data migration, the code rollback boundary remains the stable 4.6.0 line.

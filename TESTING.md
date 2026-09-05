# PlexonCrates 3.0.0 verification

## Automated suite

Run the release gate on Java 25:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The unit and MockBukkit suites cover:

- all four bundled crates, all 32 default rewards, and exact fallback availability;
- integer percentage-ticket boundaries, permission filtering, displayed chances, limits, cooldowns, and deterministic pity reset/guarantee behavior;
- exact matching with amount normalization, PDC mismatch rejection, multi-stack counting, deterministic consumption, and legacy-template collision detection;
- a live PlexonKeys-like `CAPTURED` template, forged-lookalike rejection, and last-known-good behavior after provider loss;
- item-stack splitting plus full-inventory and overflow planning without mutating the live inventory during validation;
- journal preparation/completion, history pagination, atomic statistics/limit/pity commits, bounded database writing, and retry-safe migration markers;
- 1.0 configuration, keys, locations, and statistics migration without loss or duplicate import;
- rollback of both the SQLite import and migration marker when converted-file commit fails;
- cancelled, full-inventory, overlapping-request, bypass-clamp, and successful opening paths with exact key accounting;
- non-destructive GUI key capture, distributed-drag rejection, and rejection of editor/wand display items as templates;
- server-owned GUI session identity, superseded-view rejection, same-lease revision advancement, and takeover invalidation;
- existing reward editing across delivery actions, permissions, limits, messages, effects, and ordering;
- invalid-reload rollback without changing the active runtime snapshot;
- crate definition export/import as a validated draft with ID/path safety;
- Link Wand persistence, duplicate prevention, unlinking, and break/explosion/piston protection.

Surefire reports are written to `target/surefire-reports`. The release workflow independently runs the same clean verification before producing any GitHub release.

## Real Paper 26.2 acceptance

Use a disposable Java 25 Paper 26.2 server with the release-candidate PlexonCrates JAR and the current PlexonKeys release. Add Vault/economy and PlaceholderAPI for the integration-specific cases.

1. Start a clean server and confirm startup reports four crates, 32 rewards, schema 3, and live PlexonKeys templates.
2. Claim each default PlexonKeys category and open the matching `basic`, `rare`, `epic`, and `legendary` crate without recapturing keys.
3. Change a PlexonKeys category to `CAPTURED`, store an item with unique PDC/custom metadata, and confirm the genuine item opens while a visual copy does not.
4. Disable PlexonKeys after one successful resolution and confirm the cached/fallback source remains explicit in `/pcrates diagnose`.
5. Create a custom key entirely through the GUI. Exercise cursor-click, shift-click, one-slot drag, multi-slot drag rejection, rotation, and the legacy-template option.
6. Create and publish a new crate entirely through the guided GUI, including icon, description, key, opening mode, access rules, hologram, and a reward bundle.
7. Add item, command, XP, level, and money actions; edit rarity/chance/permissions/limits/messages/effects; test-deliver it; and verify test delivery changes no keys, statistics, limits, or pity.
8. Clone the crate, export it, copy the export into `imports/`, import it under a new ID, and confirm it remains a draft until validation passes.
9. Link several safe block types in allowed worlds with the wand. Inspect an existing link, reject a duplicate, and confirm unlink requires the confirmation menu.
10. Try breaking, exploding, piston-moving, and entity-changing linked blocks as OP without `plexoncrates.admin.protection-bypass`; each must remain protected.
11. Restart and confirm links, one display per link, key cache, drafts, statistics, limits, pity, history, and audit data persist without duplicate tasks/displays.
12. Test one and bulk openings through a block, GUI/command, and explicit admin force. Confirm consumed key cost exactly matches completed openings.
13. Fill the inventory with overflow disabled and confirm zero keys are consumed. Enable overflow and confirm only leftovers drop at the player's current position.
14. Cancel `CratePreOpenEvent`, remove permission before the prepared commit, change the exact key stack, disconnect during preparation, and double-click concurrently; every failed path must consume zero keys.
15. Disconnect or close the GUI during every animation type and confirm each frozen reward is delivered exactly once.
16. Exhaust player/global lifetime and rolling limits, wait through cooldown/window boundaries, and trigger pity at its exact threshold. Confirm preview and selection stay identical.
17. Run `/pcrates validate`, `reload`, `backup`, and `diagnose`; then introduce an invalid chance pool, missing key, and duplicate exact key template and confirm activation is rejected without changing the live snapshot.
18. Upgrade a copied 1.0 data folder. Confirm the timestamped backup, converted definitions, imported links/statistics, migration marker, and an idempotent second restart.
19. Inspect timings with many linked crates and concurrent animations. Confirm no per-location task, forced chunk load, unbounded database queue, or interaction-thread disk I/O appears.
20. Verify the release checksum, `plugin.yml` version/API, JAR startup, and clean shutdown with no leaked tasks, displays, locks, sessions, or database worker.

Record the Paper build, Java build, PlexonKeys version, optional integration versions, tester, date, and any deviations with the release candidate.

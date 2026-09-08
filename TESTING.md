# PlexonCrates 3.0.0 verification

## Automated suite

Run the release gate on Java 25:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

CI provisions the released PlexonCore 1.0.0 and PlexonKeys 1.2.0 API JARs as compile-only dependencies before running the same verification. The distribution gate then requires SQLite JDBC to remain bundled while rejecting shaded PlexonCore or PlexonKeys runtime classes.

The unit and MockBukkit suites cover:

- all four bundled crates, all 32 default rewards, and exact fallback availability;
- integer percentage-ticket boundaries, permission filtering, displayed chances, limits, cooldowns, and deterministic pity reset/guarantee behavior;
- exact matching with amount normalization, PDC mismatch rejection, multi-stack counting, deterministic consumption, and legacy-template collision detection;
- a live PlexonKeys-like `CAPTURED` template, forged-lookalike rejection, and last-known-good behavior after provider loss;
- PlexonKeys 1.2 service-first template discovery with exact defensive ItemStack copies and compatibility fallback isolation;
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
- Link Wand persistence, duplicate prevention, unlinking, and break/explosion/piston protection;
- Claim Inbox idempotency, interrupted-claim review recovery, virtual-key and reroll ledger no-overdraft/idempotency behavior, durable milestone state, and deterministic advanced planners;
- portable token tampering, owner/revision metadata, durable secret restart behavior, signing-key-loss refusal, interrupted reservation recovery, preview-without-consumption, cancellation, duplicate-stack consumption, and replay rejection through the real opening pipeline;
- PlexonCore absence/standalone behavior, compatible Core `STARTING -> READY` registration, incompatible Core fallback, module identity/capabilities and `PLEXON_CRATES` integration publication;
- the exact PlexonQuests 3.1 `CrateOpenEvent`/`OpeningPlan` public reflection contract;
- read-only Phoenix migration scanning, deterministic SHA-256 source fingerprints, symlink/path confinement, source immutability, blocked schema-unknown import and report generation.

Surefire reports are written to `target/surefire-reports`. Skipped tests are rejected by CI. The tag-driven release workflow independently runs a clean verification before producing any GitHub release.

## Real Paper 26.2 acceptance

Use a disposable Java 25 Paper 26.2 server with the release-candidate PlexonCrates JAR, PlexonCore 1.0.0, PlexonKeys 1.2.0 and PlexonQuests 3.1.0. Add Vault/economy and PlaceholderAPI for the integration-specific cases.

1. Start a clean server and confirm startup reports four crates, 32 rewards, the expected schema, and live PlexonKeys templates.
2. Run `/plexon modules`, `/plexon integrations`, `/plexon diagnostics` and `/pcrates diagnose`. Confirm PlexonCrates is `READY`, mode is `CORE`, the Core API is inside `>=1.0 <2.0`, the PlexonCrates API service is registered once, and `PLEXON_CRATES` is published once.
3. Run `/quests diagnostics` and confirm `PLEXON_CRATES AVAILABLE` without modifying PlexonQuests.
4. Claim each default PlexonKeys category and open the matching `basic`, `rare`, `epic`, and `legendary` crate without recapturing keys.
5. Change a PlexonKeys category to `CAPTURED`, store an item with unique PDC/custom metadata, and confirm the genuine item opens while a visual copy does not.
6. Confirm `/pcrates diagnose` reports the PlexonKeys 1.2 service path. Disable PlexonKeys after one successful resolution and confirm cached/fallback resolution remains explicit and exact.
7. Create a custom key entirely through the GUI. Exercise cursor-click, shift-click, one-slot drag, multi-slot drag rejection, rotation, and the legacy-template option.
8. Create and publish a new crate entirely through the guided GUI, including icon, description, key, opening mode, access rules, hologram, and a reward bundle.
9. Add item, command, XP, level, and money actions; edit rarity/chance/permissions/limits/messages/effects; test-deliver it; and verify test delivery changes no keys, statistics, limits, or pity.
10. Clone the crate, export it, copy the export into `imports/`, import it under a new ID, and confirm it remains a draft until validation passes.
11. Link several safe block types in allowed worlds with the wand. Inspect an existing link, reject a duplicate, and confirm unlink requires the confirmation menu.
12. Try breaking, exploding, piston-moving, and entity-changing linked blocks as OP without `plexoncrates.admin.protection-bypass`; each must remain protected.
13. Restart and confirm links, one display per link, key cache, drafts, statistics, limits, pity, history and audit data persist without duplicate tasks/displays. Confirm the Core module/API/integration are still registered exactly once.
14. Test one and bulk openings through a block, GUI/command, and explicit admin force. Confirm consumed key cost exactly matches completed openings.
15. Observe PlexonQuests contribution during controlled openings: a single opening contributes exactly one, a bulk opening of five contributes exactly five, and the same `transactionId` is not counted twice.
16. Fill the inventory with overflow disabled and confirm zero keys are consumed. Enable overflow and confirm only leftovers drop at the player's current position.
17. Cancel `CratePreOpenEvent`, remove permission before the prepared commit, change the exact key stack, disconnect during preparation, and double-click concurrently; every failed path must consume zero keys.
18. Disconnect or close the GUI during every animation type and confirm each frozen reward is delivered exactly once.
19. Exhaust player/global lifetime and rolling limits, wait through cooldown/window boundaries, and trigger pity at its exact threshold. Confirm preview and selection stay identical.
20. Run `/pcrates validate`, `reload`, `backup`, and `diagnose`; then introduce an invalid chance pool, missing key, and duplicate exact key template and confirm activation is rejected without changing the live snapshot. Confirm the rejected candidate does not duplicate Core registration/listeners/writers/displays.
21. Upgrade a copied legacy PlexonCrates data folder. Confirm the timestamped backup, converted definitions, imported links/statistics, migration marker, and an idempotent second restart.
22. Inspect timings with many linked crates and concurrent animations. Confirm no per-location task, forced chunk load, unbounded database queue, interaction-thread disk I/O, per-opening Core lookup, filesystem scan, provider discovery or Phoenix scan appears.
23. Issue a portable crate to an online player and an offline UUID. Close its preview, modify a duplicate, confirm one authentic copy, retry the remaining copy, and restart between reservation attempts. Confirm zero preview/tamper/replay consumption, exactly one reward, Claim Inbox delivery, and healthy signer/issuance counts in `/pcrates diagnose`.
24. Stop the server, remove PlexonCore, and start the same PlexonCrates data in a disposable copy. Confirm `STANDALONE` mode, normal crate operation, PlexonKeys operation and the public `PlexonCratesApi`/`CrateOpenEvent` contract remain available.
25. Verify the release checksum, `plugin.yml` version/API, JAR startup, SQLite presence, absence of shaded `com/zpkdxgames/plexoncore/**` and `com/antondev/keys/**`, and clean shutdown with no leaked tasks, displays, locks, sessions or database worker.

## Phoenix replacement staging gate

Automatic field mapping must be tested against the operator-owned current PhoenixCratesLite data folder or sanitized exact fixtures from it. The schema-unknown scanner alone is not evidence of migration parity.

On a cloned production staging server:

1. Back up both Phoenix and PlexonCrates data outside active plugin directories.
2. Copy the Phoenix source data into `plugins/PlexonCrates/imports/phoenix/` for the migration tooling. Do not point the importer at the only production copy.
3. Scan/plan and record source hashes. Confirm the source tree is byte-for-byte unchanged after scanning.
4. If no reviewed source adapter exists for the supplied fixture, stop here. Import must remain blocked and the report must show `MANUAL_REVIEW`; do not invent field mappings.
5. Once a fixture-specific adapter exists, verify deterministic/idempotent planning, invalid YAML/unknown-field reporting, ID conflicts, exact item roundtrips, chance conversion, command validation, key mappings and world-link mapping.
6. Confirm every imported crate enters `DRAFT`, not `PUBLISHED`.
7. Compare every required production crate for display identity, keys, reward count/items/commands/probabilities, permissions, cooldowns, opening method, location, hologram/effects and player preview.
8. Test a current PlexonKeys key, any deliberately retained exact legacy Phoenix key, and an invalid lookalike.
9. Restart staging at least twice and verify no duplicate holograms, module registrations, writer tasks or event contributions.
10. Remove the Phoenix JAR and repeat all player/admin crate tests. No runtime path may require Phoenix.
11. Keep the rollback package and archived Phoenix data until production has stabilized.

See `docs/PHOENIX_MIGRATION.md` and `docs/CUTOVER.md` for the migration boundary and production sequence.

Record the Paper build, Java build, PlexonCore/PlexonKeys/PlexonQuests versions, optional integration versions, source-fixture hash set, tester, date, and any deviations with the release candidate.

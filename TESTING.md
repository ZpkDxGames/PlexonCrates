# PlexonCrates 6.0 release-candidate verification

## Automated source gate

Use Java 25 and run:

```bash
mvn -B -ntp clean verify
```

Canonical CI provisions the checksum-pinned PlexonCore `2.0.4` and PlexonKeys `2.0.0-rc.2` release JARs as provided compile dependencies, targets Paper `26.2.build.121-stable`, and rejects skipped tests. SQLite JDBC must remain bundled while Core/Keys/Vault/PlaceholderAPI/LuckPerms/Bukkit/Adventure API trees must not be shaded into the distribution.

The automated suite covers the preserved transaction/recovery core plus 6.0 product boundaries, including:

- exact percentage-ticket selection, eligibility, limits, cooldowns, pity, milestones and rerolls;
- journal-first opening/payment/grant/recovery transitions and replay/idempotency behavior;
- physical, local virtual and PlexonKeys payment paths;
- exact native ItemStack byte capture/restore, SHA-256 verification, third-party PDC/custom metadata and nested container data;
- exact key matching/consumption and forged-lookalike rejection;
- durable Claim Inbox and uncertain-side-effect review handling;
- portable signing/issuance/reservation/replay protection;
- durable drafts, leases, revision guards, publication and immutable runtime isolation;
- centralized GUI routing, stale-session rejection and deferred view transitions;
- My Keys authoritative balance/compatibility routing;
- asynchronous paginated Opening History GUI;
- exact-item diagnostics that never mutate source/canonical items;
- named opening profiles, legacy migration projection, profile persistence/editor behavior and production result-boundary integration;
- Test Lab use of the same shared opening renderer after deterministic dry selection;
- named physical idle profiles, migration-safe legacy fallback, per-crate resolution and one-frame bounded editor preview;
- one shared opening coordinator and one shared physical-particle coordinator with bounded/receiver-scoped work;
- PlexonCore optional runtime integration and public PlexonCrates API/event contracts;
- Phoenix clean-room migration/source immutability boundaries;
- Java 25 distribution/provenance/release-promotion invariants.

Surefire reports are written to `target/surefire-reports`. The release workflow must report a non-zero test count with zero failures, errors and skips before it may publish an RC.

## Exact RC evidence

Runtime certification is valid only for the exact published GitHub prerelease artifact. Record all of:

- tag, for example `v6.0.0-rc.1`;
- source commit SHA targeted by that tag;
- GitHub release URL;
- `PlexonCrates-6.0.0-rc.1.jar` SHA-256 from `SHA256SUMS.txt`;
- `TEST_SUMMARY.txt` test count;
- `PROVENANCE.txt` Paper/Java/Core/Keys/schema/source values;
- PlexonCraft host Paper and Java versions;
- tester/date and any deviations.

Do not certify a locally rebuilt JAR in place of the published release asset.

## Pre-flight / backup

Before installing the RC on PlexonCraft:

1. Verify production is running the expected Paper/Java baseline.
2. Record currently installed PlexonCrates/PlexonCore/PlexonKeys versions.
3. Back up the full `plugins/PlexonCrates/` directory, including `data/plexoncrates.db`, configuration and profile files.
4. Retain the currently stable `v5.0.0` JAR and its data backup as the rollback package.
5. Download the exact RC JAR plus checksum/provenance assets from GitHub and verify `sha256sum --check SHA256SUMS.txt`.
6. Confirm no different PlexonCrates JAR remains in the plugins directory.

## Startup and migration-safety gate

1. Start Paper `26.2.build.121-stable` on Java 25 with the exact RC.
2. Confirm PlexonCrates enables once, reports schema `4`, and does not start duplicate listeners/workers/displays.
3. Run `/pcrates validate`, `/pcrates status`, and `/pcrates diagnose`.
4. Confirm published crates, linked locations, drafts, key definitions/provider state, statistics, history, limits, pity, milestones, rerolls, claims and portable issuance state are present.
5. Confirm `animations.yml` exists and defaults to `global-profile: legacy` unless deliberately customized.
6. Confirm `idle-animations.yml` exists and defaults to `global-profile: legacy` unless deliberately customized.
7. Verify existing crates retain their pre-6.0 opening animation behavior under opening-profile legacy inheritance.
8. Verify existing physical crate particle behavior remains consistent under idle-profile legacy inheritance.
9. Restart once before feature testing and confirm no duplicate tasks/displays and no unexpected exact-item payload rewrites.

Any startup exception, unexpected schema change, missing durable state or silent legacy-presentation change fails the RC gate.

## Player GUI and payment gate

Exercise at least Basic/Rare/Epic/Legendary plus one custom crate.

1. `/crates` -> Crate Hall -> reward preview -> back/close without payment.
2. Random Open 1 and bounded Open More; verify exact consumed payment equals completed openings.
3. Selective opening; verify only the explicitly eligible selected reward can be confirmed.
4. Physical linked crate preview/open and portable crate preview/open through the same authoritative pipeline.
5. Open **My Keys** and verify physical inventory count, PlexonKeys virtual balance where applicable, local virtual balance where applicable, and compatible-crate navigation.
6. Open **Opening History**, page/refresh it, verify asynchronous loading/empty/error behavior, and navigate from a history row to a currently published crate.
7. Open the Claim Inbox and deliver at least one exact pending item if a safe fixture exists.
8. Exhaust or trigger representative cooldown/limit/pity/milestone/reroll conditions and confirm preview/selection remain consistent.
9. Close or disconnect during confirmation/animation boundaries and verify no duplicate payment/grant.
10. Double-click or attempt overlapping openings and confirm the per-player lock prevents duplicate transaction acceptance.

## Exact-item fidelity gate

Use at least one non-vanilla-looking exact custom item containing unique PDC/components; use a nested-container/block-state item if available.

1. Capture/configure it as a key or reward through the ordinary admin GUI.
2. Verify a visual lookalike with different hidden metadata does not match as the exact key.
3. In Physical Keys, confirm exact diagnostics correspond to the resolved exact template rather than the cosmetic list icon.
4. In Reward Builder, verify the seven exact item input slots remain visually untouched and diagnostics appear on the separate companion control.
5. Use Test Lab exact-item audit and verify the displayed audited item clone itself has not been relored/renamed by PlexonCrates.
6. Publish/open/claim/restart and compare the delivered/restored item with the expected semantic custom item.
7. Verify no ordinary browse, copy/reorder, diagnostics, restart or claim action mass-rewrites stored native payloads.

Any lost foreign PDC/component/container data or lookalike acceptance fails certification.

## Opening profile gate

Exercise legacy and at least three named styles, including one rail style and one reveal/burst style.

1. Verify a crate on `legacy` preserves its established 5.x `AnimationType` behavior.
2. Assign a named profile to one crate; do not change its reward/payment configuration.
3. Edit stage timings, particle, sound, volume/pitch, particle budget and receiver range through the in-game editor.
4. Preview the profile from Test Lab and then perform a real opening.
5. Confirm Test Lab selection is non-granting/non-consuming and its animated presentation matches the production renderer behavior.
6. Close the preview/real opening GUI during animation and confirm shared coordinator cleanup and exactly-once result handling.
7. Set `summary-on-finish` and verify summary behavior without duplicate result announcement/grant.
8. Return the crate to global/legacy inheritance and confirm the expected presentation returns.

Monitor scheduler/task count while repeating openings. There must not be one repeating task per opening/player/profile.

## Physical idle profile gate

Use several linked crates with at least two nearby players if possible.

1. Verify `legacy` reproduces the accepted `particles.*` configuration.
2. Assign a named profile to one crate and test several styles/geometry settings.
3. Adjust receiver range and verify players outside that range do not receive its particles.
4. Exercise per-crate and per-viewer budgets while the existing global particle/location ceilings remain active.
5. Use the editor preview and verify it renders a single bounded frame only; no repeating preview task remains afterward.
6. Move away, unload chunks where safe, and verify no forced chunk load is created for particles.
7. Disable particle presentation/reload/restart and verify the shared coordinator stops/restarts cleanly without duplicates.

## Admin / Test Lab gate

1. Open `/pcrates` and traverse Crates, Keys, Locations, All Rewards, Statistics and System.
2. Create/edit/publish a disposable crate draft through Crate Studio, including key/reward configuration and validation.
3. Open crate-scoped Test Lab and run readiness, deterministic dry selection and bounded simulation.
4. Verify configured/player probability modes and worker diagnostics do not grant rewards or mutate payment/state.
5. Open opening-profile and idle-profile editors from the crate-scoped control path and confirm assignments remain associated with the intended crate.
6. Exercise key/reward exact diagnostics and verify they are read-only.
7. Test-deliver a safe reward and verify the documented admin-test semantics: no opening key, statistics, limit or pity state should be advanced by the test path.
8. Run validate/reload/backup/diagnose with an intentionally invalid candidate in a disposable copy and confirm failed activation does not replace the live runtime.

## Recovery / interruption gate

Test safe interruption scenarios in staging or a controlled production maintenance window:

- disconnect before prepared payment commits;
- change/remove exact physical key before revalidation;
- insufficient virtual payment;
- close during animation after authoritative completion;
- full inventory under the configured overflow policy;
- pending exact claim with insufficient capacity;
- plugin/server restart with pending durable state.

Verify pre-payment failures consume nothing. Where a side effect is known/possibly committed and finalization is uncertain, verify PlexonCrates retains manual-review/recovery evidence rather than guessing and duplicating the operation.

## Phoenix migration gate

Phoenix migration remains a clean-room import boundary. Only use the operator-owned current PhoenixCratesLite data folder or a sanitized exact fixture from it.

1. Back up both source and PlexonCrates data outside active plugin directories.
2. Copy the fixture into the supported import location; never point tooling at the only production copy.
3. Scan/plan and record deterministic hashes; confirm source files remain byte-for-byte unchanged.
4. If the supplied fixture has no reviewed adapter, import must remain blocked/manual-review; do not invent mappings.
5. Where a reviewed fixture path is supported, verify draft-only import, exact items, chance/key/world mappings, report output and restart idempotency.
6. Remove the Phoenix runtime plugin and repeat relevant player/admin tests; PlexonCrates may not require Phoenix at runtime.

## Performance / soak gate

Capture baseline and active-load evidence on the real PlexonCraft host.

At minimum compare:

- steady TPS/MSPT with normal linked crates loaded;
- multiple nearby linked crates with idle particles active;
- repeated concurrent opening animations;
- player/admin GUI browsing and Test Lab simulation;
- restart/reload scheduler/listener counts.

Required invariants:

- no repeating task per crate/player/opening/profile;
- no forced chunk loading from particle presentation;
- no synchronous database/file/network work in animation ticks or ordinary GUI render paths;
- bounded simulation/database workers;
- no unbounded task/session/listener/display growth;
- no material TPS/MSPT regression attributable to 6.0 presentation under representative load.

Record spark/timings/MSPT evidence when available.

## Stable promotion gate

`v6.0.0` must not be published from CI success alone.

Stable promotion requires a runtime certification record tied to the exact published RC:

```text
RUNTIME_CERTIFICATION=PASS
PAPER_VERSION=26.2.build.121-stable
CERTIFIED_RC_TAG=v6.0.0-rc.N
CERTIFIED_RC_SHA=<exact tag source SHA>
CERTIFIED_RC_JAR_SHA256=<exact published JAR SHA-256>
```

The stable release workflow independently verifies the certified prerelease tag/source/JAR, rebuilds/tests the stable-promotion source, rejects non-promotion product changes after the certified RC, and verifies published stable assets.

Until these runtime gates pass, the truthful state is:

`RC RELEASED / RUNTIME PENDING`

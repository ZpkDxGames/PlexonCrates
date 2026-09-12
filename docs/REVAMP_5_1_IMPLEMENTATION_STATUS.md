# PlexonCrates 5.1 Revamp Implementation Status

Baseline / rollback: `v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`

Current branch: `revamp/5.1.0-full-source-hardening`

Product-source freeze: `549eb33547ab56ebe0b4415668b31fd6a15df9e5`

Release-gate / provenance checkpoint: `60c642e2f5a85feea2afa4ad9221bd78b6314640`

## Source implementation status

**SOURCE HARDENING COMPLETE / RUNTIME CERTIFICATION PENDING**

The 5.1 revamp keeps the proven opening, exact-item, publication, recovery and SQLite schema-4 product model. The implementation work concentrated on GUI lifecycle correctness, explicit event ownership, bounded scheduling/executors and live administrative I/O boundaries rather than rewriting the transaction core.

## Implemented

### GUI lifecycle and routing

- Player Crate Hall sessions track explicit player ownership and clear view/submission state on close and disconnect.
- Accepted `PLAYER_*` inventory clicks are cancelled immediately, then dispatched on the next server execution point with a second exact session/revision validation before navigation or opening submission.
- Compatibility/admin view-changing clicks follow the same deferred-dispatch rule where required.
- `GuiSessionService` is a pure session authority; it performs no hidden listener registration.
- `CrateMenuEventRouter` is the single registered inventory click/drag authority for PlexonCrates menu surfaces.
- The Test Lab no longer competes as an independent inventory-click listener. Its editor decoration remains an `InventoryOpenEvent` concern, while click/drag intent is delegated through the central router using the same explicitly composed `SimulationAdminListener` instance.
- Test Lab view changes are deferred and revalidate the exact holder before executing; stale async simulation output is discarded.

### Scheduling and bounded work

- Roulette animations use one shared `OpeningAnimationCoordinator` rather than one repeating Bukkit task per opening.
- The animation coordinator starts on demand, stops when empty and is explicitly stopped on plugin disable.
- Plugin-owned general I/O uses bounded `AsyncIoService` workers rather than unbounded executor growth.
- `OpeningLog` uses a bounded writer queue with rejection handling and explicit shutdown.
- `CrateSimulationService` remains one bounded non-granting analysis worker and is explicitly closed on disable.

### Live reload, validation and diagnostics

- Live admin reload entry points use `requestReload(...)`.
- Canonical SQLite definitions/drafts/keys are loaded asynchronously; config/YAML/cache preparation runs on the bounded I/O executor; only validated state activation and Bukkit-facing reconciliation return to the primary thread.
- The synchronous `reloadFor(...)` path remains only for compatibility/test/startup callers and is not used by live admin GUI/command surfaces.
- Live validation performs file/config parsing off the primary thread and only renders the result on the primary thread.
- Admin diagnostics move journal/count/portable database probes off the primary thread and marshal only final rendering back to Bukkit.
- Post-reload PlexonCore health probing is asynchronous.

### Definition/key/admin I/O boundaries

- Published YAML mirrors are optional and non-authoritative. Runtime/SQLite publication activates first; mirror persistence is queued off-thread and mirror failure cannot roll back a successful canonical publication.
- Draft mirror persistence is isolated from primary-thread state activation.
- Live key mutation preparation/activation remains on the proper Bukkit thread while filesystem mirror writes are isolated on bounded I/O workers.
- Crate creation/import/deletion/transfer and related live admin paths have explicit file/database I/O boundaries covered by architecture tests.
- Remaining synchronous startup bootstrap joins/file reads are classified as startup-only boot barriers rather than gameplay/admin hot paths.

### Phoenix migration

- Read-only scan/plan/validate/report operations run through the bounded plugin I/O executor.
- Bukkit/plugin state needed for planning is captured on the primary thread into immutable planning state before worker execution.
- Worker planning/validation does not touch live Bukkit/plugin registries.
- Destructive import is staged instead of being wrapped wholesale in an async task:
  - async preparation;
  - primary-thread key preparation;
  - async key mirror write;
  - primary-thread key install;
  - async draft payload build;
  - primary-thread draft preparation;
  - async draft write;
  - primary-thread draft/world activation;
  - async database/history/location/report persistence;
  - primary-thread final activation/rendering.
- The persistence stage does not perform Bukkit world or registry mutation.

## Verification boundary

### Product-source checkpoint

Exact source: `549eb33547ab56ebe0b4415668b31fd6a15df9e5`

Canonical Build: `34700843396`

Result:

- `267` tests executed;
- `0` failures;
- `0` errors;
- `0` skipped;
- package/distribution verification passed;
- Java 25 / class major 69 verification passed;
- Paper `26.2.build.121-stable` contract passed;
- forbidden dependency shading checks passed;
- provenance generation passed;
- whitespace gate passed;
- verified CI artifact upload passed.

The artifact produced by this source checkpoint is still versioned `PlexonCrates-5.0.0.jar`. It is a CI certification artifact only, **not** a 5.1 release candidate or stable release.

### Release-provenance checkpoint

Exact checkpoint: `60c642e2f5a85feea2afa4ad9221bd78b6314640`

Canonical Build: `34700994957`

Result: **PASS**.

The build workflow now anchors rollback metadata to the current stable release:

- rollback tag: `v5.0.0`;
- rollback source: `f24e3f7f942f7c886352e71c7105af499828096f`;
- CI proves the tag resolves to that exact SHA;
- CI proves the rollback SHA is an ancestor of the candidate;
- existing Phase 2 / reliability / Phase 3 / accepted RC4 ancestry checks remain intact.

## Architecture/regression coverage now green

The canonical suite includes dedicated coverage for:

- player session ownership and deferred inventory dispatch;
- single inventory click/drag routing authority;
- centralized Test Lab routing and stale-result rejection;
- shared roulette scheduling;
- bounded opening-log writing;
- live reload I/O isolation;
- live validation I/O isolation;
- live key-mutation I/O isolation;
- draft mirror I/O isolation;
- publication mirror I/O isolation;
- crate import/deletion/transfer I/O isolation;
- Phoenix migration threading/staging;
- simulation non-granting contracts;
- opening transaction/recovery behavior;
- administration integration;
- opening pipeline integration;
- PlexonKeys and PlexonCore integration contracts.

## Remaining gates

No additional source-architecture rewrite is currently required before runtime certification. The remaining blockers are operational/release gates:

1. Start the candidate on the real PlexonCraft Paper 26.2 host and prove clean enable/disable/restart behavior.
2. Smoke-test `/crates`, Crate Hall navigation, physical crates, portable crates, single/selective/bulk openings, Pending Rewards/claims, rerolls and failure states.
3. Smoke-test admin editor, Test Lab, publish, reload, backup, validate and diagnose surfaces.
4. Verify schema-4 data and player/admin state survive restart without loss or duplicate grants.
5. Exercise Phoenix migration with an appropriate fixture/live migration source when available and confirm report/backup/import behavior.
6. Capture active-use spark evidence for repeated opening/menu/admin activity and compare it with the accepted 5.0 baseline.
7. Only after runtime certification, create the 5.1 release-candidate version boundary, build the exact candidate JAR, record its SHA-256/source SHA and perform final promotion review.

## Release boundary

Do **not** merge this branch into `main`, tag `v5.1.0`, or publish a stable 5.1 JAR until the real PlexonCraft startup, interaction, persistence/restart and performance gates pass.

`v5.0.0` at `f24e3f7f942f7c886352e71c7105af499828096f` is the rollback boundary.

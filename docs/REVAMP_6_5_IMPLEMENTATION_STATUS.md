# PlexonCrates 6.5 implementation status

## Accepted source lineage

- Working branch starts from `c865e64e00fb7f33975dd362d9f43766156898c9`.
- That head is six commits ahead of `v6.0.0-rc.1` source `cb3ba1aed8ab2b89bdbaec39271ad30ee07e1c28` with zero changed files, preserving the accepted 6.0 product tree.
- `main` remained on the 5.0 stable lineage when the 6.5 branch was created, so 6.5 intentionally carries the accepted 6.0 source forward instead of branching from 5.0.

## Design-system foundation

- Shared semantic glass theme: implemented through `GuiTheme`.
- Shared `LIST_54`, `PANEL_54`, `DIALOG_27`, and `OPENING_27` geometry: implemented through `GuiLayout`.
- Shared chrome renderer: implemented through `GuiChromeRenderer`.
- Stable footer/navigation contract: implemented through `GuiNavigation`.
- Shared loading/empty/error/disabled presentation factory: implemented through `GuiItemFactory`.
- Player, administrator, Test Lab, opening-profile, and idle-profile renderers no longer own independent hard-coded filler loops.

## Menu-by-menu acceptance matrix

`PASS` means the surface now inherits the 6.5 shared presentation contract and its applicable structural/navigation requirements. `N/A` means the requirement is not meaningful for that specialized surface. Regression evidence is architectural/integration coverage in the canonical Maven suite; it is not a claim of a fresh production-host visual certification.

| Surface | Shared chrome | Aligned structure | Glass frame | Separators / groups | Stable navigation | Designed state handling | Regression evidence |
|---|---|---|---|---|---|---|---|
| Player Hall | PASS | PASS | PASS | when useful | PASS | PASS | player UX/layout + design-system tests |
| Player Preview | PASS | PASS | PASS | when useful | PASS | PASS | player UX/probability + layout tests |
| My Keys | PASS | PASS | PASS | when useful | PASS | PASS | `PlayerKeyMenuArchitectureTest` + design-system tests |
| Compatible Crates | PASS | PASS | PASS | when useful | PASS | PASS | player UX/key-menu architecture tests |
| Opening History | PASS | PASS | PASS | when useful | PASS | PASS | `PlayerHistoryMenuArchitectureTest` + stale-view coverage |
| Claim Inbox / Pending Rewards | PASS | PASS | PASS | when useful | PASS | PASS | player UX + claim/opening integration tests |
| Mass Open / Quantity | PASS | PASS | PASS | specialized | PASS | PASS | player UX + opening integration tests |
| Selective Confirm | PASS | PASS | PASS | specialized | PASS | N/A | opening integration + dialog layout tests |
| Reroll | PASS | PASS | PASS | specialized | PASS | N/A | opening pipeline + dialog layout tests |
| Opening Animation | PASS | PASS | PASS | specialized rail | N/A | N/A | opening-animation architecture tests |
| Summary | PASS | PASS | PASS | when useful | PASS | PASS | player UX/design-system tests |
| Admin Dashboard | PASS | PASS | PASS | PASS | PASS | N/A | administration integration + design-system tests |
| Crate List | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + navigation tests |
| Crate Studio | PASS | PASS | PASS | PASS | PASS | PASS | administration integration + panel layout tests |
| Key List | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + exact capture tests |
| Exact Key Capture | PASS | PASS | PASS | PASS | PASS | PASS | `ExactCaptureArchitectureTest` + exact-input safety tests |
| Key Select | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + navigation tests |
| Reward Pool | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + exact-item diagnostics tests |
| Reward Builder | PASS | PASS | PASS | PASS | PASS | PASS | admin diagnostics + exact-input safety + integration tests |
| Milestones | PASS | PASS | PASS | when useful | PASS | PASS | administration/opening integration tests |
| Milestone Detail | PASS | PASS | PASS | PASS | PASS | N/A | administration integration + panel layout tests |
| Locations / Link Wand | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + location/service regression tests |
| Statistics | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + panel layout tests |
| System / Diagnostics | PASS | PASS | PASS | PASS | PASS | N/A | administration integration + release/IO architecture tests |
| Global Rewards | PASS | PASS | PASS | when useful | PASS | PASS | admin exact-item diagnostics + administration tests |
| Wand Select | PASS | PASS | PASS | when useful | PASS | PASS | administration integration + navigation tests |
| Opening Profile Editor | PASS | PASS | PASS | PASS | PASS | PASS | opening-profile editor/presentation architecture tests |
| Idle Profile Editor | PASS | PASS | PASS | PASS | PASS | PASS | idle-profile editor architecture tests |
| Test Lab / Simulation | PASS | PASS | PASS | PASS | PASS | PASS | simulation contract + animation preview architecture tests |
| Confirmation Dialogs | PASS | PASS | PASS | specialized | PASS | N/A | dialog layout + administration/opening integration tests |

## Safety boundaries

- `CrateMenuEventRouter` remains the single registered inventory routing authority.
- Decorative chrome binds no default action.
- Exact reward/key capture regions are cleared from decoration before functional rendering.
- Exact displayed items remain presentation copies; decoration and diagnostics do not become canonical reward/key state.
- No transaction, reward-selection, payment, journal, claim, persistence, or exact-byte authority moved into GUI code.
- No per-menu, per-player, per-crate, or per-opening repeating GUI scheduler was introduced.
- Optional disabled panel controls render inert frame glass rather than unexplained holes; enabled controls overwrite that chrome normally.

## `menus.yml` migration

- Schema version `2`: implemented.
- First pre-6.5 load creates `menus.yml.pre-6.5.bak` before writing migrated structure.
- The shared semantic theme and standard layout metadata are introduced when absent.
- Known list/footer/editor/confirmation structural slots migrate to the 6.5 design.
- Existing administrator-customized names, lore, and materials are retained where the redesign does not require a structural reset.
- Crate, key, reward, claim, journal, exact-item, and SQLite gameplay state are outside this migration.
- Migration and backup behavior are covered by `GuiConfigMigrationTest`.

## Automated verification

The pre-PR canonical GitHub Actions run for source `e1dc297d6a9957ccbd630730a32603bbf57d0c10` completed successfully with:

- Java 25 / class major 69;
- Paper `26.2.build.121-stable`;
- PlexonCore `2.0.4` checksum verification;
- PlexonKeys `2.0.0-rc.2` checksum verification;
- `358` tests;
- `0` failures;
- `0` errors;
- `0` skipped;
- exact `PlexonCrates-6.5.0.jar` distribution verification;
- provided-dependency shading guards;
- `git diff --check` success;
- artifact/provenance upload success.

This document update occurs after that pre-PR run, so the final branch/PR/main release evidence must come from the subsequent canonical run on the frozen source head.

## Stable release gate

The 6.5 stable publisher is fail-closed around the directive's GUI-only authorization:

- exact version must be `6.5.0`;
- final source must equal current `main`;
- accepted 6.0 source ancestry must be present;
- the source delta from the accepted 6.0 baseline is restricted to declared GUI/config/layout/tests/docs/release-engineering paths;
- `OpeningService`, `ClaimService`, `DatabaseService`, `ItemSnapshotCodec`, and `RewardSelector` are explicitly rejected if changed;
- the publisher rebuilds and re-verifies the exact JAR before creating the stable release;
- published assets are re-downloaded and checksum/provenance-verified.

No fresh production-host runtime certification has been performed by this 6.5 GUI campaign. Stable provenance must therefore record `runtime_certification=NOT_EXECUTED_GUI_ONLY_RELEASE`, not a fabricated runtime PASS.

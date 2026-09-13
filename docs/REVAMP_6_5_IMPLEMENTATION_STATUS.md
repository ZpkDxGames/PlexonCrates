# PlexonCrates 6.5 implementation status

## Accepted source lineage
- Working branch starts from `c865e64e00fb7f33975dd362d9f43766156898c9`.
- That head is six commits ahead of `v6.0.0-rc.1` source `cb3ba1aed8ab2b89bdbaec39271ad30ee07e1c28` with zero changed files, preserving the accepted 6.0 product tree.
- `main` remained on the 5.0 stable lineage when the 6.5 branch was created, so 6.5 intentionally carries the accepted 6.0 source forward instead of branching from 5.0.

## Design-system foundation
- Shared semantic glass theme: implemented.
- Shared LIST_54 / PANEL_54 / DIALOG_27 / OPENING_27 geometry: implemented.
- Shared chrome renderer: implemented.
- Stable footer/navigation contract: implemented.
- Shared loading/empty/error/disabled factory: implemented.

## Safety
- Central `CrateMenuEventRouter` authority preserved.
- Decorative chrome binds no actions.
- Exact reward/key capture regions are cleared from decoration before functional rendering.
- No transaction, reward-selection, payment, journal, claim, persistence, or exact-byte authority moved into GUI code.
- No GUI repeating scheduler was introduced.

## Migration
- `menus.yml` schema 2 and pre-6.5 backup: implemented.
- Structural footer/list migration: implemented.
- Safe text/material preservation: implemented.

## Verification
Canonical GitHub Actions is authoritative for compile, full regression tests, distribution checks, version parity and artifact provenance.

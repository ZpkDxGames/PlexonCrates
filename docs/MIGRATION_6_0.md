# PlexonCrates 6.0 — Migration and Compatibility

## Current storage boundary

The 6.0 revamp currently retains SQLite schema `4`. The implemented animation and exact-item slices do not require a destructive data migration:

- linked physical locations keep their existing rows and indexes;
- published crate/draft revisions remain canonical in SQLite;
- opening journals, claims/recovery, history, statistics, limits, pity, milestones and rerolls retain their existing tables/semantics;
- portable issuance/signing state is unchanged;
- PlexonKeys integration and key provenance are unchanged;
- exact reward/key item payloads remain native Paper bytes and are not mass re-encoded.

A later 6.0 source slice must not increment the schema merely to store cosmetic defaults. Any genuine schema change requires an explicit migration test, backup/rollback path, and documentation update here before the RC is frozen.

## Configuration compatibility

`config.yml` remains config-version `3`. Existing particle settings are retained and extended with optional 6.0 idle-animation fields. Missing 6.0 fields receive bounded defaults through `PluginSettings`; existing 5.x installations therefore do not need to delete/regenerate configuration.

New optional particle controls include:

- style;
- points/radius/height;
- rotation and vertical speed;
- per-crate particle budget;
- per-viewer particle budget.

Global location/particle budgets and stagger behavior remain authoritative ceilings.

### Opening animation profiles

6.0 adds `animations.yml` as a presentation-only profile registry. Its bundled migration-safe default is:

```yaml
global-profile: legacy
crate-profiles: {}
```

`legacy` is a reserved assignment, not a named editable profile. It means “project this crate's already-accepted 5.x `crate.animation` value.” Therefore installing 6.0 does not silently convert an `INSTANT`, `REVEAL`, `ROULETTE`, or `SUMMARY` crate to a new animation style.

Administrators may deliberately replace that behavior by selecting a named global profile or assigning a named profile to one crate. A crate may also be explicitly assigned `legacy` while another named global profile is active. The in-game profile editor exposes both “Preserve Legacy Globally” and “Use Legacy for This Crate”; cloning an effective legacy presentation materializes the current crate animation into a normal editable profile.

The profile registry remains cosmetic. It does not store reward choices, key/payment state, pity, limits, journals, claims, milestones, rerolls, or statistics, and it does not require a SQLite schema change.

## Exact item compatibility

Stored exact item bytes are never rewritten solely because 6.0 introduces additional diagnostics. On restore, PlexonCrates verifies the stored SHA-256 before Paper decodes the payload. Capture validation requires a successful native decode back to an equivalent current-server ItemStack while retaining the original captured byte array as the authoritative snapshot.

This distinction is intentional: Paper may normalize/data-fix an item when decoding/re-serializing it, but that is not permission for PlexonCrates to replace the previously stored payload during ordinary editing or startup.

Third-party custom item metadata remains opaque. ItemsAdder-like, Oraxen-like, Nexo-like, Slimefun-like, MMOItems-like and future component data do not require a direct PlexonCrates schema mapping to be preserved.

## Phoenix migration

The existing Phoenix migration service remains in scope and retains its 5.1 asynchronous filesystem/database boundaries. 6.0 does not introduce a second Phoenix importer. Real RC certification must still exercise the available sanitized/live migration fixture and verify backup/report/import behavior before stable promotion.

## Upgrade procedure for the RC

1. Stop PlexonCraft or use the normal controlled plugin deployment procedure.
2. Back up the PlexonCrates plugin directory and SQLite database before replacing the production candidate.
3. Install the exact published 6.0 RC JAR; do not substitute a locally rebuilt binary during certification.
4. Start Paper 26.2 and verify clean enable with the existing config/schema.
5. Verify published crates, linked locations, key sources, exact custom rewards and player durable state.
6. Verify existing crates retain their pre-6.0 animation behavior while `global-profile: legacy` is active, then exercise one deliberate named-profile override and revert it.
7. Exercise restart/reload and confirm no duplicate grants, lost claims or unexpected payload rewrites.
8. Exercise Phoenix migration only with the intended fixture/source and retain its generated backup/report.
9. Capture the exact RC tag, source SHA and JAR SHA-256 in the runtime certification record.

## Rollback

Until 6.0 is runtime-certified and promoted, the current stable rollback boundary remains `v5.0.0` at `f24e3f7f942f7c886352e71c7105af499828096f`.

Do not claim that a database modified by a future, not-yet-defined 6.0 schema migration is downgrade-compatible. If a later slice introduces schema `5`, this document and release procedure must be updated before that change is accepted.

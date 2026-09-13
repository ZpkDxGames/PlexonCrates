# PlexonCrates 6.0 — Migration and Compatibility

## Storage boundary

The 6.0 revamp retains SQLite schema `4`. No destructive data migration is required for the accepted GUI, exact-item, or animation-profile work:

- linked physical locations keep their existing rows/indexes;
- published crate/draft revisions remain canonical in SQLite;
- opening journals, claims/recovery, history, statistics, limits, pity, milestones and rerolls retain existing semantics;
- portable issuance/signing state is unchanged;
- PlexonKeys integration/provenance is unchanged;
- exact reward/key payloads remain native Paper bytes and are not mass re-encoded.

Presentation-only profile configuration is deliberately stored outside SQLite; cosmetic defaults are not justification for a player-state schema migration.

## Existing `config.yml` compatibility

`config.yml` remains config-version `3`. Existing particle settings remain accepted and provide the legacy physical-idle profile. Missing bounded 6.0 particle fields receive defaults through `PluginSettings`; existing installations do not need to delete/regenerate their configuration.

The existing master controls remain authoritative runtime ceilings:

- `particles.enabled` and interval;
- maximum locations processed per tick;
- global maximum particles per tick;
- stagger behavior.

The existing style/geometry/range/per-crate/per-viewer settings are the source used whenever an idle assignment is `legacy`.

## Opening animation profiles

6.0 adds `animations.yml` as a presentation-only profile registry. Its bundled migration-safe assignment is:

```yaml
global-profile: legacy
crate-profiles: {}
```

`legacy` is reserved inheritance, not a named editable profile. It means “project this crate's existing 5.x `crate.animation` value.” Therefore installing 6.0 does not silently convert an `INSTANT`, `REVEAL`, `ROULETTE`, or `SUMMARY` crate to a new style.

Administrators may deliberately replace legacy behavior with a named global profile or a named per-crate profile. A crate may explicitly use `legacy` while another named global profile is active. Clone/reset/assignment operations affect presentation only.

## Physical idle profiles

6.0 also adds `idle-animations.yml`. Its bundled migration-safe assignment is likewise:

```yaml
global-profile: legacy
crate-profiles: {}
```

Here `legacy` means “use the accepted `particles.*` profile from `config.yml`.” Upgrading therefore does not silently change physical crate particles.

Named idle profiles can be assigned globally/per crate and contain particle, geometry, receiver range, and local per-crate/per-viewer budgets. The existing global particle/location ceilings from `config.yml` remain authoritative above those local profiles.

Both `animations.yml` and `idle-animations.yml` are cosmetic registries. They contain no reward selection, key/payment, journal, claim, pity, limit, milestone, reroll, history, or statistics state and require no SQLite schema change.

## Exact item compatibility

Stored exact item bytes are never rewritten solely because 6.0 adds diagnostics or new GUI surfaces. On restore, PlexonCrates verifies the stored SHA-256 before Paper decodes the payload. Capture validation requires successful native decode to an equivalent current-runtime ItemStack while retaining the captured byte array as the authoritative snapshot.

Paper may normalize/data-fix an item during decode/re-serialization. That does not authorize PlexonCrates to replace a previously stored payload during ordinary startup, browsing, diagnostics, copy/reorder, claim, or edit operations.

Third-party custom item metadata remains opaque. ItemsAdder-like, Oraxen-like, Nexo-like, Slimefun-like, MMOItems-like and future component/PDC data do not require a direct PlexonCrates schema mapping to be preserved.

6.0 admin diagnostics inspect native bytes on defensive item copies/resolved templates only. They never reconstruct exact state from material, display name, lore, model data, or plugin IDs.

## Player GUI compatibility

New `My Keys` and Opening History screens reuse existing authoritative data sources; they introduce no new durable player-state format. Existing claims/recovery remain in the schema-4 Claim Inbox pipeline.

Old compatibility commands/menu entry points continue to route to canonical services rather than introducing alternate opening/payment engines.

## Phoenix migration

The existing Phoenix migration service remains separate and retains its asynchronous filesystem/database boundaries. 6.0 does not add a second Phoenix importer or guess undocumented Phoenix fields. Real RC certification must exercise the intended sanitized/live fixture and verify backup/report/import behavior before stable promotion.

## RC upgrade procedure

1. Stop PlexonCraft or use the normal controlled deployment procedure.
2. Back up the PlexonCrates plugin directory and SQLite database before replacing the production candidate.
3. Install the **exact published** 6.0 RC JAR; do not substitute a local rebuild during certification.
4. Start Paper `26.2.build.121-stable` and verify clean enable with existing schema/config.
5. Verify published crates, linked locations, PlexonKeys/captured key sources, exact custom rewards, claims and durable player state.
6. Confirm existing crates preserve their pre-6.0 opening behavior while `animations.yml` uses `global-profile: legacy`.
7. Confirm existing physical particle behavior is preserved while `idle-animations.yml` uses `global-profile: legacy`.
8. Exercise one deliberate named opening-profile override and one named idle-profile override, then revert/inherit safely.
9. Exercise My Keys, Opening History, reward/key exact diagnostics and Test Lab without consuming/granting unintended state.
10. Restart/reload and confirm no duplicate grants, lost claims, unexpected exact-item payload rewrites, or duplicate profile schedulers.
11. Exercise Phoenix migration only with the intended fixture/source and retain its backup/report.
12. Capture the exact RC tag, source SHA and JAR SHA-256 in runtime certification evidence.

## Rollback

Until 6.0 is runtime-certified and promoted, the stable rollback boundary remains:

`v5.0.0` / `f24e3f7f942f7c886352e71c7105af499828096f`

No 6.0 schema change currently prevents rollback at the database-schema level. Operational rollback still requires normal backups because runtime activity may legitimately advance player/opening state while the RC is installed.

If a future change introduces schema `5`, this document, migration tests, backup/rollback evidence and stable-release gate must be updated before that change is accepted.

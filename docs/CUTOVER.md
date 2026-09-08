# PlexonCrates 3.0 Production Cutover

This runbook is intentionally conservative because PlexonCrates 3.0 replaces the live PhoenixCratesLite engine and must preserve existing PlexonKeys/Quests behavior.

## Hard gates before production

Do not cut over until all of the following have passed on a cloned staging server:

- PlexonCrates 3.0 release candidate builds and tests cleanly;
- PlexonCore reports PlexonCrates `READY`;
- PlexonQuests reports `PLEXON_CRATES AVAILABLE`;
- PlexonKeys 1.2 live templates resolve without unexpected collisions;
- the actual Phoenix data fixture has been scanned, mapped and reviewed;
- every required production crate exists as a validated PlexonCrates definition;
- reward counts/items/commands/chances are checked against the operator-owned Phoenix data;
- required world links, previews, holograms and effects are checked;
- new PlexonKeys keys and any explicitly retained legacy Phoenix keys open correctly;
- invalid lookalike keys fail exact matching;
- at least two staging restarts preserve definitions, links, keys, displays and integrations;
- staging passes with the Phoenix JAR absent;
- a rollback package exists.

## Staging sequence

1. Clone production server/plugin data to a separate staging instance.
2. Install PlexonCore 1.0.0, PlexonKeys 1.2.0, PlexonQuests 3.1.0 and the PlexonCrates 3.0 release candidate.
3. Keep all custom-item providers, Vault/economy and PlaceholderAPI present where production definitions require them.
4. Copy the operator-owned Phoenix data into the migration input boundary described in `PHOENIX_MIGRATION.md`.
5. Generate and review the migration scan/plan/report.
6. Do not enable import until the source adapter has been implemented and tested against that exact fixture.
7. Once an adapter exists, import to `DRAFT` only.
8. Run normal PlexonCrates definition validation and inspect every imported draft before publication.
9. Publish definitions through the normal atomic publication path.
10. Validate diagnostics and perform controlled openings for every required crate family.
11. Restart staging at least twice and repeat integration checks.
12. Remove the Phoenix JAR from staging and verify no PlexonCrates gameplay path depends on it.

## Production backup

With the server stopped, back up at minimum:

```text
PhoenixCratesLite JAR
plugins/PhoenixCratesLite/
plugins/PlexonCrates/ (if already present)
PlexonKeys data
relevant server/world location context
```

Store the backup outside the active plugins directory.

## Production cutover

1. Stop the server.
2. Create final backups.
3. Move the PhoenixCratesLite JAR out of `plugins/`.
4. Keep the Phoenix data directory archived and unchanged.
5. Install the approved PlexonCrates 3.0 artifact.
6. Keep PlexonCore and PlexonKeys installed.
7. Apply the already validated migrated PlexonCrates data from staging, or run only the exact import flow that passed staging.
8. Start the server.
9. Run:

```text
/plexon modules
/plexon integrations
/plexon diagnostics
/quests diagnostics
/pcrates diagnose
/pcrates status
/crates
```

10. Confirm `PlexonCrates — READY` and `PLEXON_CRATES AVAILABLE`.
11. Perform one controlled physical-key opening before opening the server broadly.
12. Verify the resulting key consumption, reward delivery, history/statistics and Quests contribution.

## Dual-engine prohibition

Do not run normal production gameplay with PhoenixCratesLite and PlexonCrates both enabled. Both engines can contend for crate block interactions, key consumption, commands, holograms and reward delivery.

The migration framework may inspect an operator-owned copy of Phoenix data. It does not require Phoenix to be an active runtime dependency.

## Rollback

If production cutover fails:

1. Stop the server.
2. Remove the active PlexonCrates JAR.
3. Restore the PhoenixCratesLite JAR.
4. Restore the Phoenix data backup only if an operator action changed it; the PlexonCrates importer itself must never modify the Phoenix source.
5. Restore prior PlexonCrates data only where necessary.
6. Start the server and verify the previous crate engine before reopening gameplay.

Do not delete the archived Phoenix data after a successful cutover. Keep it through normal-operation stabilization and remove/archive it later only by deliberate operator action.

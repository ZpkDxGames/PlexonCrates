# PlexonCrates

[![Paper](https://img.shields.io/badge/Paper-26.2-2f3136?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820?style=for-the-badge)](https://adoptium.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/ZpkDxGames/PlexonCrates/build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/ZpkDxGames/PlexonCrates/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonCrates?style=for-the-badge)](https://github.com/ZpkDxGames/PlexonCrates/releases/latest)

PlexonCrates **5.0.0** is the stable premium crate system for Paper 26.2 and the Plexon plugin family. It combines exact-item custody, percentage-first rewards, durable editor drafts, immutable published revisions, journal-first openings, limits/pity, milestones, rerolls, portable crates, virtual-key ledgers, and a durable Claim Inbox.

This is an independent implementation inspired by the usability goals and feature concepts of ExcellentCrates. No ExcellentCrates source code is included or modified.

## Runtime baseline

- Paper `26.2`
- Java `25`
- PlexonCore `2.0.4` optional runtime integration; stable builds verify its exact release JAR by SHA-256
- PlexonKeys API baseline `2.0.0-rc.2`; stable builds verify that exact provided dependency by SHA-256
- Vault + an economy provider only when money rewards or money-backed rerolls are configured
- PlaceholderAPI only when external placeholders are used
- SQLite schema `4`, owned by PlexonCrates

PlexonCore, PlexonKeys, Vault and PlaceholderAPI are not shaded into PlexonCrates. SQLite JDBC is bundled.

## Install / upgrade

1. Back up `plugins/PlexonCrates/`, including `data/plexoncrates.db`.
2. Download `PlexonCrates-5.0.0.jar` and `SHA256SUMS.txt` from the matching stable GitHub release.
3. Verify the JAR with `sha256sum --check SHA256SUMS.txt`.
4. Replace the previous PlexonCrates JAR and keep the existing data directory.
5. Start Paper 26.2 on Java 25.
6. Run `/pcrates validate` and `/pcrates diagnose` before reopening normal player traffic.
7. When live deployment certification is required, use `TESTING.md` and the migration/cutover documentation as the operational follow-up.

Rollback baseline for the 5.0 stable closure is `v4.6.0` at `261270243beb9ff4a8992f53095f16c20458f770`.

## Player experience

`/crates` opens the Crate Hall. Browsing and previewing are non-consuming; payment is submitted only by an explicit Open or Confirm action.

Player surfaces include:

- Crate Hall and reward preview
- random Open 1 and bounded Open More flows
- selective reward confirmation
- linked physical crate preview/open
- personal opening history
- Pending Rewards / Claim Inbox
- physical and optional virtual-key balances
- milestone progress
- reroll-token balance
- signed portable-crate preview and confirmation

Random reward presentation uses the player's current eligible-pool probability and distinguishes configured base chance where useful. Selective rewards display `Selective choice`; no random percentage is fabricated for a player choice. Internal reward IDs, transaction IDs and backend recovery enums remain outside ordinary player presentation.

## Opening authority and durability

Opening correctness remains centralized in `OpeningService`:

`validate -> plan -> journal -> revalidate -> payment -> grant -> durable completion`

Important properties:

- one accepted opening at a time per player through explicit locking;
- immutable opening plans tied to a runtime crate revision;
- reward eligibility, limits, pity, milestones, inventory capacity and permissions revalidated immediately before consumption;
- physical, local virtual-key and PlexonKeys wallet payment paths are journaled/idempotent;
- payment/grant uncertainty is retained for review instead of guessed or silently retried;
- opening history/statistics are committed only through the authoritative transaction path;
- cosmetics/animations follow selection and correctness rather than owning it.

## Exact items and Claim Inbox

Exact item/key handling preserves Bukkit item metadata rather than matching by display appearance. Item snapshots are byte-backed, size-bounded and SHA-256 verified.

The Claim Inbox stores durable recovery deliveries. A claim is reserved before inventory or virtual-ledger mutation. If a side effect may already have happened and durable finalization becomes uncertain, the row moves to `REVIEW` and is not automatically retried.

Stable 5.0 also closes a final audit edge case: a successfully credited **virtual-key claim** now moves immediately to `REVIEW` when `completeClaim` cannot be finalized, matching the fail-closed behavior already used after exact-item inventory insertion.

## Percentage-first rewards

Configured random chances use integer basis points (`10,000 = 100.00%`). The eligible subset is normalized into an exact integer-ticket pool for selection. Permission/limit/cooldown/pity filtering is shared between preview and actual selection so the UI does not advertise impossible outcomes.

Selective crates bypass random probability semantics and require an explicit eligible reward confirmation.

## Durable definitions and administration

SQLite is the canonical mutable definition/recovery store. Administrative editing uses durable versioned drafts with:

- ordered saves and visible save health;
- writable leases and confirmed takeover;
- stale action/revision rejection;
- bounded undo/revision history;
- atomic publication into normalized rows;
- immutable published runtime snapshots;
- validation before activation;
- administrative audit records.

Unpublished edits never become player runtime state.

Common administration commands include:

| Command | Purpose |
|---|---|
| `/pcrates` | Open the administration dashboard |
| `/pcrates create <id>` | Create a durable crate draft |
| `/pcrates edit <crate>` | Open the guided editor |
| `/pcrates publish <crate>` | Validate and atomically publish the current draft |
| `/pcrates keys` | Manage exact physical-key definitions/providers |
| `/pcrates wand [crate]` | Obtain the Link Wand |
| `/pcrates portable give <player|uuid> <crate> [amount]` | Issue signed portable crates |
| `/pcrates virtualgrant <player|uuid> <key> <amount>` | Credit an audited virtual-key balance |
| `/pcrates rerolls <give|take|set> <player|uuid> <amount>` | Adjust audited reroll-token state |
| `/pcrates validate` | Validate configuration without activation |
| `/pcrates reload` | Validate and atomically activate configuration |
| `/pcrates backup` | Create a consistent backup |
| `/pcrates status` | Show the concise runtime state |
| `/pcrates diagnose` | Show provider/schema/queue/journal/claim/portable health |

See `docs/API.md`, `MIGRATION.md`, `docs/MIGRATION_5_0.md`, `docs/CUTOVER.md` and `TESTING.md` for the detailed contracts and operational procedures.

## Portable crates

Portable crate items are HMAC-signed and backed by durable issuance rows. Confirmation rechecks signature, issuance UUID, owner, state and revision before reservation. Closing a preview consumes nothing. Reserved-but-unconsumed issuances are recoverable after interruption; consumed issuances cannot be replayed from duplicated items.

The signing secret is persisted locally. If outstanding issuances exist and the secret is missing/corrupt, PlexonCrates fails closed rather than silently rotating trust material.

## Performance boundaries

- no database query per reward card;
- no repeating GUI refresh task;
- no task per crate/reward/player opening;
- GUI and Bukkit inventory mutation stays on the primary thread;
- SQLite work uses the bounded database worker;
- player interaction paths use material/PDC fast gates where applicable;
- simulation/analysis remains isolated from live opening/payment mutation;
- immutable published runtime snapshots prevent player paths from traversing writable draft state.

## Public integration surface

`PlexonCratesApi` remains registered through Bukkit services. Public crate lifecycle/opening events remain in `com.antondev.crates.api.event`, including the post-success `CrateOpenEvent` contract consumed by first-party integrations.

PlexonCore is lifecycle/integration infrastructure; PlexonCrates retains ownership of crate definitions, opening transactions, exact items, SQLite state and player interaction semantics.

## Build and stable-release verification

Build locally with:

```bash
mvn -B -ntp clean verify
```

Canonical CI verifies:

- accepted Phase 2, reliability, Phase 3 and final RC4 ancestry;
- Java 25 / class major 69;
- Paper 26.2;
- checksum-pinned PlexonCore 2.0.4 and PlexonKeys 2.0.0-rc.2 provided APIs;
- non-empty tests with zero failures, errors or skips;
- required opening, claim, simulation, integration and SQLite classes in the JAR;
- no shaded Core/Keys/Vault/PlaceholderAPI/LuckPerms/Bukkit/Adventure API trees;
- plugin/manifest version parity;
- SHA-256 and exact-source provenance.

Stable publication runs only from `release/stable` when it points to exact current `main`. It rebuilds and tests that source, publishes `PlexonCrates-<version>.jar`, `SHA256SUMS.txt`, `TEST_SUMMARY.txt` and `PROVENANCE.txt`, then downloads those assets and verifies their checksum and exact source provenance before the workflow can succeed.

Live PlexonCraft runtime/soak certification is a deployment follow-up and may be recorded as `NOT_EXECUTED` in GitHub release provenance; it does not block source/release closure.

## License

PlexonCrates is available under the MIT License.

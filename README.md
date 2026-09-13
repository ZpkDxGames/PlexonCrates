# PlexonCrates

> [!WARNING]
> **ABANDONED / NO LONGER MAINTAINED**
>
> PlexonCrates has been discontinued and is no longer used or maintained for PlexonCraft. The project has been retired in favor of **PhoenixCrates**. No further feature development, compatibility work, releases, or support are planned.
>
> The repository is retained only as a historical source/archive of the implementation that existed before retirement.

[![Status](https://img.shields.io/badge/status-abandoned-red?style=for-the-badge)](#)
[![Paper](https://img.shields.io/badge/Paper-26.2-2f3136?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820?style=for-the-badge)](https://adoptium.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/ZpkDxGames/PlexonCrates/build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/ZpkDxGames/PlexonCrates/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonCrates?style=for-the-badge)](https://github.com/ZpkDxGames/PlexonCrates/releases/latest)

## Retirement status

- **Project status:** Abandoned
- **Maintenance:** Ended
- **New releases:** None planned
- **Production replacement:** PhoenixCrates
- **Repository purpose:** Historical/reference archive only

The remaining documentation below describes the final PlexonCrates implementation and is preserved for historical reference. It should not be interpreted as an actively supported product.

---

PlexonCrates is the exact-item, percentage-first crate system for Paper 26.2 and the Plexon plugin family. Version **6.5.0** carries forward the accepted 6.0 transaction/exact-item product boundary and adds a product-wide inventory GUI design system without moving opening, payment, reward-selection, claim, persistence, or exact-item authority into presentation code.

The accepted rollback boundary for the 6.5 GUI campaign is `v6.0.0-rc.1` (`cb3ba1aed8ab2b89bdbaec39271ad30ee07e1c28`). The later 6.0 branch head is source-equivalent to that tagged product tree.

## Runtime baseline

- Paper `26.2.build.121-stable`
- Java `25`
- PlexonCore `2.0.4` as an optional provided runtime integration
- PlexonKeys API baseline `2.0.0-rc.2` as a provided dependency
- Vault + economy provider only when money-backed features are configured
- PlaceholderAPI only when external placeholders are used
- SQLite schema `4`, owned by PlexonCrates

Canonical CI downloads the exact PlexonCore and PlexonKeys release JARs and verifies their SHA-256 values before compilation. PlexonCore, PlexonKeys, Vault, PlaceholderAPI, LuckPerms, Bukkit/Paper APIs and Adventure are not shaded into PlexonCrates. SQLite JDBC is bundled.

## 6.5 GUI design system

PlexonCrates 6.5 standardizes the inventory product around shared presentation primitives:

- `GuiTheme` — semantic background, frame, separator, accent, success, warning and danger presentation;
- `GuiLayout` — reusable list, panel, dialog and opening geometry;
- `GuiChromeRenderer` — one inert chrome renderer for player/admin inventory shells;
- `GuiItemFactory` — shared loading, empty, error and disabled states;
- `GuiNavigation` — stable 54-slot footer semantics.

The standard 54-slot list grid uses slots `10–16`, `19–25`, `28–34`, and `37–43`. Footer semantics are stable: Previous `45`, Search/Refresh `46`, Context `47`, Back `48`, Primary `49`, Secondary `50`, Status `51`, Close/Cancel `52`, and Next `53`.

Crate Studio and other complex admin surfaces use grouped panel structure with dark framing and separators. Confirmation inventories use centered decision geometry, normally Confirm `11`, Subject `13`, and Cancel `15`.

Decorative panes are presentation only: they bind no default action, cannot become canonical key/reward input, and declared exact-input regions are cleared from chrome before their owning editor renders them.

## Player experience

`/crates` opens the Crate Hall. Browsing/previewing are non-consuming; payment is submitted only through an explicit opening path.

Player surfaces include:

- Crate Hall and exact reward preview;
- random Open 1 and bounded Open More flows;
- selective reward confirmation;
- linked physical crate preview/open;
- **My Keys**, with physical/PlexonKeys/local-wallet provenance and compatible-crate navigation;
- **Opening History**, paginated and loaded asynchronously;
- Pending Rewards / durable Claim Inbox;
- milestone and reroll state;
- signed portable-crate preview/confirmation.

Async GUI completion revalidates the open holder/session before rendering. No per-player repeating GUI refresh task is introduced by 6.5.

## Opening authority and durability

Correctness remains centralized in `OpeningService`:

`validate -> plan -> journal -> revalidate -> payment -> grant -> durable completion -> presentation`

Important properties remain unchanged:

- one accepted opening at a time per player through explicit locking;
- immutable opening plans tied to a runtime crate revision;
- reward eligibility, limits, pity, milestones, capacity and permissions revalidated before consumption;
- physical, local virtual-key and PlexonKeys wallet payment paths journaled/idempotent;
- uncertain payment/grant boundaries remain manual-review eligible instead of being guessed or blindly retried;
- history/statistics are committed only through the authoritative transaction path;
- animations run only after authoritative selection/delivery and never own payment or reward state.

## Exact items

Exact reward/key custody uses Paper native ItemStack bytes rather than material/name/lore matching. `ItemSnapshotCodec` captures native bytes, stores quantity separately, verifies SHA-256, restores through Paper native decoding, and respects the restored item's real maximum stack size.

6.5 keeps GUI display items as presentation copies. Exact key capture, Reward Builder input, Claim Inbox delivery, diagnostics, and preview chrome do not rewrite canonical exact items.

## Opening and idle animation profiles

The accepted 6.0 reusable opening/idle profile systems remain intact. Production and Test Lab previews use bounded shared coordinators; there is no repeating task per opening, player, profile, or menu.

The 6.5 GUI conversion gives these editors the shared PlexonCrates visual language without changing animation state authority.

## `menus.yml` migration

6.5 introduces GUI schema version `2`. On the first load of a pre-6.5 `menus.yml`, PlexonCrates:

1. creates `menus.yml.pre-6.5.bak`;
2. installs missing semantic theme/layout keys;
3. migrates known structural list/footer/editor positions to the 6.5 design;
4. preserves administrator-customized names, lore and materials where structurally safe;
5. writes schema version 2 only after migration succeeds.

This migration does **not** modify crate definitions, key definitions, reward data, exact item bytes, claims, journals, or SQLite gameplay state.

## Install / upgrade

1. Back up `plugins/PlexonCrates/`, including `data/plexoncrates.db` and existing YAML configuration.
2. Download `PlexonCrates-6.5.0.jar` and `SHA256SUMS.txt` from the matching GitHub release.
3. Verify the JAR with `sha256sum --check SHA256SUMS.txt`.
4. Replace the previous PlexonCrates JAR and retain the existing data directory.
5. Start Paper 26.2 on Java 25.
6. Confirm the one-time `menus.yml.pre-6.5.bak` migration backup exists when upgrading an older menu schema.
7. Run `/pcrates validate` and `/pcrates diagnose` before reopening normal player traffic.

SQLite remains schema `4` in this GUI-focused release.

## Common administration commands

| Command | Purpose |
|---|---|
| `/pcrates` | Open the administration dashboard |
| `/pcrates create <id>` | Create a durable crate draft |
| `/pcrates edit <crate>` | Open Crate Studio |
| `/pcrates publish <crate>` | Validate and atomically publish the current draft |
| `/pcrates keys` | Manage exact physical-key definitions/providers |
| `/pcrates wand [crate]` | Obtain the Link Wand |
| `/pcrates portable give <player|uuid> <crate> [amount]` | Issue signed portable crates |
| `/pcrates virtualgrant <player|uuid> <key> <amount>` | Credit an audited virtual-key balance |
| `/pcrates rerolls <give|take|set> <player|uuid> <amount>` | Adjust audited reroll-token state |
| `/pcrates validate` | Validate configuration without activation |
| `/pcrates reload` | Validate and atomically activate configuration |
| `/pcrates backup` | Create a consistent backup |
| `/pcrates status` | Show concise runtime state |
| `/pcrates diagnose` | Show provider/schema/queue/journal/claim/portable health |

See `docs/GUI_6_5.md`, `docs/MIGRATION_6_5.md`, `docs/REVAMP_6_5_IMPLEMENTATION_STATUS.md`, `docs/API.md`, `docs/CUTOVER.md`, and `TESTING.md` for detailed contracts.

## Performance boundaries

- no database query per reward card;
- no repeating GUI refresh task;
- no repeating task per crate/player/opening/profile;
- GUI/Bukkit inventory mutation stays on the primary thread;
- SQLite/profile persistence work is bounded/off-thread where applicable;
- linked-crate particles use indexed nearby candidates and loaded-chunk checks;
- opening and idle particles are receiver-scoped and budgeted;
- simulation/analysis never enters live payment/grant mutation;
- immutable published runtime snapshots keep player paths outside writable draft state.

## Public integration surface

`PlexonCratesApi` remains registered through Bukkit services. Public lifecycle/opening events remain in `com.antondev.crates.api.event`, including post-success `CrateOpenEvent`.

PlexonCore is lifecycle/integration infrastructure; PlexonCrates retains ownership of definitions, opening transactions, exact items, SQLite state and player interaction semantics.

## Build and release verification

Build locally with:

```bash
mvn -B -ntp clean verify
```

Canonical CI verifies accepted ancestry, Java 25/class major 69, exact Paper/Core/Keys dependency boundaries, a non-empty zero-skip test suite, required distribution contents, prohibited provided-dependency shading, plugin/manifest version parity, SHA-256 generation, exact source provenance and whitespace.

The `v6.5.0` stable publisher is intentionally fail-closed around the GUI-only authorization: it allows only the declared presentation/config-layout/tests/docs/release-engineering source delta from the accepted 6.0 GUI baseline and explicitly rejects changes to opening, claim, database, item-snapshot and reward-selection authorities. Because this is a GUI-only stable path, release provenance truthfully records `runtime_certification=NOT_EXECUTED_GUI_ONLY_RELEASE`; it does not fabricate a fresh production-host PASS.

## License

PlexonCrates is available under the MIT License.

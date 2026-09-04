# PlexonCrates

[![Paper](https://img.shields.io/badge/Paper-26.2-2f3136?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820?style=for-the-badge)](https://adoptium.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/ZpkDxGames/PlexonCrates/build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/ZpkDxGames/PlexonCrates/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonCrates?style=for-the-badge)](https://github.com/ZpkDxGames/PlexonCrates/releases/latest)

PlexonCrates 3.0 is the active development line of the safe, GUI-first crate system created by **Tonim (ZpkDxGames)** for Paper and the Plexon plugin family. It combines exact physical keys, percentage-first rewards, guided crate editing, protected world links, journaled openings, persistent limits and pity, and portable definitions without requiring a framework plugin.

This is an independent implementation inspired by the usability goals and feature concepts of [ExcellentCrates](https://github.com/nulli0n/ExcellentCrates-spigot). No ExcellentCrates source code is included or modified.

## Highlights

- Four zero-setup defaults: `basic`, `rare`, `epic`, and `legendary`, with 32 bundled rewards.
- Live PlexonKeys `CONFIG` and `CAPTURED` templates, last-known-good caching, exact fallbacks, and controlled legacy templates.
- Exact item matching that preserves PDC, custom metadata, components, enchantments, lore, names, and other Bukkit item data.
- Non-destructive drag/cursor capture for custom keys, crate icons, and reward items.
- Exact `0.01%` base chances backed by a 10,000-ticket allocator, automatic proportional rebalancing, and eligible-pool normalization.
- Persistent crate drafts with publish validation, cloning, search, archive/delete confirmation, and safe YAML import/export.
- Item, console-command, experience, level, and Vault money actions in one reward bundle.
- Permission filters, player/global lifetime and rolling-window limits, reward cooldowns, rarities, and deterministic pity guarantees.
- `INSTANT`, `ROULETTE`, `REVEAL`, and `SUMMARY` presentation modes plus per-reward titles, sounds, particles, messages, and broadcasts.
- Protected Link Wand workflow for unlimited physical blocks, native TextDisplay holograms, centralized particles, and safe unlinking.
- Journal-first openings with immediate revalidation, per-player race locks, exact key accounting, immutable outcomes, SQLite history, and manual crash-review diagnostics.
- Atomic configuration reloads, consistent backups, administrative audit records, and reversible 1.0 migration.
- Optional PlaceholderAPI and Vault integrations; no hard plugin dependency.

## Requirements

- Paper `26.2`
- Java `25`
- [PlexonKeys](https://github.com/ZpkDxGames/PlexonKeys) `1.1.0+` recommended for live physical-key categories
- Vault plus an economy provider only when money rewards are used
- PlaceholderAPI only when external placeholders are used in reward commands

PlexonKeys, Vault, and PlaceholderAPI are soft dependencies. Bundled exact key fallbacks keep the four default crates usable when PlexonKeys is unavailable.

## Fresh install

1. Download `PlexonCrates-2.0.0.jar` and its checksum from [GitHub Releases](https://github.com/ZpkDxGames/PlexonCrates/releases/latest).
2. Verify it with `sha256sum -c PlexonCrates-2.0.0.jar.sha256`.
3. Put the JAR in the server's `plugins` folder, preferably beside PlexonKeys.
4. Start Paper once and review `plugins/PlexonCrates/config.yml`.
5. Ensure `settings.worlds` contains the exact worlds where crates will be used, or set it to `[]` to allow every non-excluded world.
6. Run `/pcrates`, create world links with the Link Wand, and use `/crates` to check the player view.

For a 1.0 server, follow [MIGRATION.md](MIGRATION.md) before replacing the JAR.

## Default key behavior

In the default `LIVE_FIRST` mode, a PlexonKeys-backed definition resolves in this order:

1. the current live PlexonKeys category template;
2. the last known good live template cached in SQLite;
3. the exact fallback in `keys.yml`;
4. unresolved, which prevents publication/opening and appears in diagnostics.

This supports both PlexonKeys modes:

- `CONFIG`: the item generated from the active PlexonKeys category configuration;
- `CAPTURED`: the complete item captured with `/keysadmin setitem <category>`.

PlexonCrates normalizes only stack amount before comparison. A visually identical item with different PDC or metadata is rejected. During deliberate key rotation, an administrator can retain the previous exact template as a legacy match.

The key registry provides create, duplicate, import, provider sync, test-give, rotation, and confirmed deletion controls. Key imports read a complete `config-version: 2` registry from `imports/<file>.yml`; every definition is validated first and any existing ID conflict rejects the whole import.

## Player commands and interactions

| Command | Purpose |
|---|---|
| `/crates` | Open the crate browser |
| `/crates <crate>` | Preview one crate |
| `/crates preview <crate>` | Preview the rewards this player can currently win and their recalculated chances |
| `/crates open <crate> [amount]` | Open with exact physical keys from inventory |
| `/crates history [page]` | Read recent persisted opening outcomes |
| `/crates help` | Show the player command summary |

At a linked block, left-click previews, right-click opens one, and sneak-right-click requests a bounded bulk opening. Offhand keys are ignored by default. A bulk key bypass is clamped to one opening to prevent accidental free mass openings.

## Administration

`/pcrates` opens the dashboard. The GUI supports persistent guided drafts, full reward editing, exact capture, key rotation, location inspection, statistics, validation, reload, backups, and diagnostics. Display items are never trusted as data; all actions resolve through server-side menu state.

| Command | Purpose |
|---|---|
| `/pcrates create <id>` | Create a persistent crate draft |
| `/pcrates edit <crate>` | Open the guided editor |
| `/pcrates clone <crate> <new-id>` | Clone a definition as a draft |
| `/pcrates import <file.yml> <new-id>` | Import `imports/file.yml` as a validated draft |
| `/pcrates export <crate>` | Export a definition to `exports/<crate>.yml` |
| `/pcrates delete <crate>` | Open destructive confirmation |
| `/pcrates keys` | Open the physical-key registry |
| `/pcrates keys sync` | Refresh PlexonKeys discovery and templates |
| `/pcrates wand [crate]` | Obtain a persistent-data-tagged Link Wand |
| `/pcrates link <crate>` | Link the targeted block |
| `/pcrates unlink` | Confirm unlinking the targeted block |
| `/pcrates givekey <player> <key> [amount]` | Give the currently resolved exact key |
| `/pcrates open <player> <crate> [amount]` | Perform an explicit administrative keyless opening |
| `/pcrates validate` | Validate configuration without activating it |
| `/pcrates reload` | Validate and atomically activate a new snapshot |
| `/pcrates backup` | Create a consistent YAML and SQLite backup |
| `/pcrates status` | Show a concise runtime summary |
| `/pcrates diagnose` | Show provider, schema, collision, queue, location, draft, and journal details |

Compatibility editing commands remain available: `/pcrates set`, `unset`, `additem`, `addcommand`, `remove`, `chance`, and `save`. The old `/pcrates weight` spelling remains a deprecated alias during 3.x.

In the Crates menu, shift-left-click a crate to export it. Imported version 2 or 3 definitions always enter the `DRAFT` state under a new safe ID; publishing performs the normal key/reward checks.

## Permissions

| Permission | Default | Purpose |
|---|---:|---|
| `plexoncrates.use` | Everyone | Compatibility parent for normal player features |
| `plexoncrates.preview` | Everyone | Browse and preview crates |
| `plexoncrates.open` | Everyone | Open crates |
| `plexoncrates.history` | Everyone | Read personal opening history |
| `plexoncrates.admin` | OP | Parent for every granular administration node |
| `plexoncrates.admin.gui` | OP | Open the dashboard and system summaries |
| `plexoncrates.admin.crates` | OP | Create, edit, clone, import/export, archive, and delete crates |
| `plexoncrates.admin.keys` | OP | Manage exact keys and provider discovery |
| `plexoncrates.admin.rewards` | OP | Create, edit, reorder, test, copy, and remove rewards |
| `plexoncrates.admin.locations` | OP | Use the Link Wand and manage locations |
| `plexoncrates.admin.give` | OP | Give keys and request administrative openings |
| `plexoncrates.admin.reload` | OP | Validate, reload, and flush statistics |
| `plexoncrates.admin.backup` | OP | Create backups |
| `plexoncrates.admin.diagnose` | OP | View detailed diagnostics |
| `plexoncrates.admin.protection-bypass` | OP | Break a protected linked block intentionally |
| `plexoncrates.bypass.key` | OP | Open without a key; bulk is still clamped to one |
| `plexoncrates.bypass.cooldown` | OP | Ignore crate cooldowns |
| `plexoncrates.bypass.limit` | OP | Ignore reward limits and pity gating |

Each crate can require its own permission. Each reward can independently require or block a permission; previews and actual selection use the same eligible pool.

## Storage layout

| Path | Responsibility |
|---|---|
| `config.yml` | Runtime, database, interaction, opening, visual, integration, and logging settings |
| `keys.yml` | Provider-backed and plugin-owned exact physical-key definitions |
| `menus.yml` | Configurable inventory layouts, slots, icons, names, and lore |
| `messages.yml` | MiniMessage feedback |
| `crates/*.yml` | Versioned crate and reward definitions |
| `data/plexoncrates.db` | Links, statistics, limits, pity, history, journals, drafts, template cache, migration markers, and audit data |
| `imports/` / `exports/` | Deliberate crate-definition transfer boundary |
| `backups/` | Automatic migration and manual consistent backups |
| `logs/openings-YYYY-MM-DD.log` | Optional human-readable opening log |

SQLite writes use one bounded worker. World interaction, GUI clicks, animations, and reward selection do not perform synchronous database I/O.

## Crate and reward definitions

Base chances are stored as integer basis points (`10,000 = 100.00%`). For each player, the currently eligible subset is renormalized into exactly 10,000 unbiased integer tickets. Limits can be omitted or set to `0` for unlimited behavior. Version 2 relative weights are converted with stable largest-remainder allocation when imported or edited.

```yaml
config-version: 3
id: vote
state: PUBLISHED
display-order: 50
display-name: <aqua><bold>Vote Crate</bold></aqua>
description: [<gray>Thanks for voting.</gray>]
keys:
  cost: 1
  accepted: [vote]
opening:
  cooldown-seconds: 1
  bulk-enabled: true
  bulk-maximum: 64
  animation: ROULETTE
pity:
  enabled: true
  threshold: 10
  rarity: EPIC
  administrative-openings-count: false
rewards:
  custom_drill:
    enabled: true
    display-name: <aqua><bold>Custom Drill</bold></aqua>
    rarity: EPIC
    chance-basis-points: 500
    required-permission: ''
    blocked-permission: ''
    limits:
      player-lifetime: 0
      player-window: 1
      player-window-seconds: 86400
      global-lifetime: 0
      global-window: 0
      global-window-seconds: 0
      cooldown-seconds: 0
    items:
      drill:
        # The GUI or /pcrates additem writes exact base64 safely.
        base64: '...'
    commands:
    - experience add %player% 250 points
    experience: {points: 0, levels: 0}
    money: {amount: 0.0}
    presentation:
      title: <gold><bold>Epic reward!</bold></gold>
      subtitle: <reward>
      sound: minecraft:entity.player.levelup
      sound-volume: 1.0
      sound-pitch: 1.1
      firework: true
```

Built-in command placeholders are `%player%`, `%display_name%`, `%uuid%`, `%crate%`, `%crate_id%`, `%reward%`, `%reward_id%`, `%world%`, `%x%`, `%y%`, and `%z%`. Commands execute as console, must be one line, and must omit the leading `/`. PlaceholderAPI expansion runs afterward when enabled.

## Opening safety and recovery

An opening freezes its key template and reward outcome, fires the cancellable pre-open event, and writes a journal before inventory mutation. On the primary thread it then revalidates crate state, world, permission, limits, exact key count, and inventory capacity. Only after those checks can it consume keys and deliver rewards.

Opening history, statistics, limits, pity state, and journal completion are committed together in SQLite. If a process stops after the journal is prepared or a key is consumed, startup reports the unresolved transaction under the default `MANUAL_REVIEW` policy. `/pcrates diagnose` exposes the pending count; the plugin never blindly repeats command rewards.

Closing an animation or disconnecting after delivery cannot remove or duplicate the already-frozen outcome. Cosmetic failures are logged without undoing delivery.

## Public API and events

Other plugins can obtain `com.antondev.crates.api.PlexonCratesApi` from Bukkit's services manager. The API returns immutable snapshots and can query crates/keys or request a validated opening.

Primary-thread Bukkit events:

- `CratePreOpenEvent` — cancellable, before journal/key mutation;
- `CrateRewardSelectEvent` — informational, after the outcome is frozen;
- `CrateKeyConsumeEvent` — after exact key consumption;
- `CrateOpenEvent` — after delivery succeeds;
- `CrateLinkEvent` and `CrateUnlinkEvent` — cancellable before persistence;
- `CrateDefinitionChangeEvent` — after a crate is created, updated, published, disabled, archived, or deleted.

## Build and test

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Build with Java 25. The shaded artifact is `target/PlexonCrates-2.0.0.jar`; SQLite is bundled, while Paper and optional plugin APIs remain provided dependencies. See [TESTING.md](TESTING.md) for automated coverage and the real-server acceptance checklist.

## License and authorship

Copyright © 2026 Tonim (ZpkDxGames). PlexonCrates is available under the [MIT License](LICENSE).

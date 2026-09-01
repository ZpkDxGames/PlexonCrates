# PlexonCrates

[![Paper](https://img.shields.io/badge/Paper-26.2-2f3136?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820?style=for-the-badge)](https://adoptium.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/ZpkDxGames/PlexonCrates/build.yml?branch=main&style=for-the-badge&label=Build)](https://github.com/ZpkDxGames/PlexonCrates/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZpkDxGames/PlexonCrates?style=for-the-badge)](https://github.com/ZpkDxGames/PlexonCrates/releases/latest)

PlexonCrates is a direct, modern crate system created by **Tonim (ZpkDxGames)** for the Plexon plugin family. It keeps the familiar crate workflow—blocks, previews, weighted rewards, animations, holograms, and admin editing—without a framework dependency or a maze of files.

This is an independent implementation inspired by the usability goals of established crate plugins. No ExcellentCrates source code is included or modified.

## What ships in 1.0.0

- Four complete default crates: `basic`, `rare`, `epic`, and `legendary`.
- Native PlexonKeys integration, including keys captured with `/keysadmin setitem`.
- Exact item matching: material, name, lore, enchantments, components, PDC, custom NBT, and other item data are respected.
- `keys.yml` fallback templates matching PlexonKeys 1.1.0 defaults.
- Weighted item and console-command rewards.
- Full item capture for custom content from Slimefun, ExecutableItems, ItemsAdder, Oraxen, MMOItems, and similar plugins.
- Reward preview, crate browser, roulette opening, and bulk opening.
- Built-in TextDisplay holograms and lightweight particles—no hologram plugin required.
- Multiple physical blocks per crate with explosion, piston, and break protection.
- In-game admin menus plus concise commands for exact edits.
- Per-player/global opening statistics and dated opening logs.
- MiniMessage throughout.
- Transactional checks: an invalid crate, missing permission, missing reward, cooldown, or full inventory never consumes a key.
- Safe delivery: the selected reward is delivered before the visual animation, so closing the GUI or disconnecting cannot lose it.

## Requirements

- Paper `26.2`
- Java `25`
- PlexonKeys `1.1.0+` is recommended, but not required because exact fallback keys are bundled.

There are no hard plugin dependencies.

## Install

1. Download `PlexonCrates-1.0.0.jar` from [Releases](https://github.com/ZpkDxGames/PlexonCrates/releases/latest).
2. Put it in the server's `plugins` folder beside PlexonKeys.
3. Start the server once.
4. Look at the block that should become a crate and run `/pcrates set basic` (or `rare`, `epic`, `legendary`).
5. Use `/crates` to preview the finished collection.

PlexonKeys players claim their virtual keys with `/keys`; those physical items open the matching PlexonCrates crate immediately. No item recapture is needed for the default configuration.

## PlexonKeys compatibility

The default `LIVE_FIRST` mode asks the running PlexonKeys instance for its active item template every time a key is checked. This covers both of PlexonKeys' modes:

- `CONFIG`: the generated MiniMessage key from PlexonKeys' config.
- `CAPTURED`: the complete serialized item captured by `/keysadmin setitem <category>`.

If PlexonKeys is missing, disabled, or its live API is unavailable, PlexonCrates automatically uses `keys.yml`. Set `plexonkeys.mode: FALLBACK_ONLY` only when you intentionally want PlexonCrates to ignore live PlexonKeys templates.

## Player commands

| Command | Purpose |
|---|---|
| `/crates` | Open the crate browser |
| `/crates <crate>` | Preview one crate |
| `/crates preview <crate>` | Preview rewards and calculated chances |
| `/crates open <crate> [amount]` | Open with physical keys from the inventory |

At a linked block, left-click previews, right-click opens one, and sneak-right-click opens as many as possible up to `settings.maximum-bulk-open`.

## Admin commands

| Command | Purpose |
|---|---|
| `/pcrates` | Open the visual admin editor |
| `/pcrates set <crate>` | Link the targeted block |
| `/pcrates unset` | Unlink the targeted block |
| `/pcrates additem <crate> <id> <weight>` | Capture the held stack as an exact reward |
| `/pcrates addcommand <crate> <id> <weight> <command>` | Add a console-command reward |
| `/pcrates remove <crate> <reward>` | Remove a reward |
| `/pcrates weight <crate> <reward> <weight>` | Change a reward's weight |
| `/pcrates givekey <player> <key> [amount]` | Give the current live/fallback key item |
| `/pcrates open <player> <crate> [amount]` | Force an administrative keyless opening |
| `/pcrates reload` | Validate and atomically activate edited files |
| `/pcrates status` | Show loaded crates, rewards, locations, and key source |
| `/pcrates save` | Save dirty statistics now |

## Permissions

| Permission | Default | Purpose |
|---|---:|---|
| `plexoncrates.use` | Everyone | Browse, preview, and open crates |
| `plexoncrates.admin` | OP | Full editor and admin commands |
| `plexoncrates.bypass.key` | OP | Open without consuming a key |
| `plexoncrates.bypass.cooldown` | OP | Ignore crate cooldowns |

Each crate can also define a custom `permission` in its own YAML file. Each reward supports `required-permission` and `blocked-permission`; percentages shown in previews are recalculated from the rewards that player can actually win.

## Files

| File | Responsibility |
|---|---|
| `config.yml` | Worlds, integration, opening behavior, holograms, particles, logging |
| `keys.yml` | Exact fallback physical-key templates |
| `menus.yml` | Menu titles, slots, icons, names, and lore |
| `messages.yml` | MiniMessage feedback |
| `crates/*.yml` | One readable file per crate and its rewards |
| `locations.yml` | Linked block locations; maintained in game |
| `statistics.yml` | Opening counters; maintained by the plugin |
| `logs/openings-YYYY-MM-DD.log` | Optional append-only reward log |

## Reward format

Weights are relative, so `10` and `30` mean 25% and 75% when both rewards are eligible.

```yaml
rewards:
  custom_drill:
    enabled: true
    display-name: <aqua><bold>Custom Drill</bold></aqua>
    weight: 5
    required-permission: ''
    blocked-permission: ''
    broadcast: <white><player></white> <gray>found <reward><gray>!</gray>
    items:
      drill:
        # Use /pcrates additem to create base64 automatically and retain all NBT.
        base64: '...'
    commands:
    - experience add %player% 250 points
```

Command placeholders are `%player%`, `%uuid%`, `%crate%`, and `%reward%`. Commands run as console and should not begin with `/`.

## Build

```bash
mvn clean verify
```

The produced file is `target/PlexonCrates-1.0.0.jar`. CI builds with Temurin Java 25 and uploads the verified JAR as an artifact.

## License and authorship

Copyright © 2026 Tonim (ZpkDxGames). PlexonCrates is available under the [MIT License](LICENSE).

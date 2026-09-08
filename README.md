# PlexonCrates 4.0

PlexonCrates 4.0 is a ground-up Paper rewrite focused on GUI-first crate management, exact-item rewards, virtual and physical keys, low-overhead effects, and non-blocking persistence.

## Runtime targets

- Paper 1.20.x through 1.21.x
- Java 17+ bytecode
- Maven
- Local SQLite database bundled in the plugin JAR

## Player workflow

- `/crates` opens the crate dashboard.
- Left-click a crate to preview every weighted reward and its calculated chance.
- Right-click a crate in the dashboard to spend one virtual key and open it.
- Right-click a linked physical crate while holding its exact key to open it.
- `/crates claims` opens the durable overflow Claim Inbox.

## Administration

- `/crates admin` opens the management dashboard.
- Create crates directly in the GUI or with `/crates admin create <id>`.
- The crate editor toggles state, cycles opening animations and idle effects, and opens reward management.
- In the reward editor, drag an item into an empty reward slot or shift-click it from the player inventory. Capture is non-destructive and retains complete ItemStack metadata.
- Left/right click reward icons to tune integer weights; displayed percentages recalculate dynamically.
- `/crates link <crate>` stores a PDC marker on the targeted tile-state block and persists its coordinates.
- `/crates reward action ...` adds ITEM, COMMAND, MESSAGE and SOUND actions.
- `/crates key give ...` grants virtual keys; `/crates key physical ...` gives exact physical key items.

## Opening animations

- `CSGO`: horizontally scrolling 9-slot reward strip with progressive slowdown.
- `WHEEL`: rotating reward ring in a 54-slot inventory.
- Physical openings also produce an in-world floating ArmorStand reward reveal with particles and sounds.
- Idle physical crate effects: `HELIX`, `CLOUD`, `FOUNTAIN`, or `NONE`.

All Bukkit world, entity and inventory work stays on the server thread. SQLite and file persistence use the shared PlexonCrates worker pool through `CompletableFuture`, avoiding database I/O on gameplay paths.

## Database

SQLite stores virtual key balances, global/per-player opening statistics, opening history, and durable overflow claims. The database uses WAL mode and a configurable busy timeout.

## Build

```bash
mvn clean verify
```

Output: `target/PlexonCrates-4.0.0-SNAPSHOT.jar`

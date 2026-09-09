# PlexonCrates 4.6.0 Performance Evidence

## Scope

4.6.0 targets the measured `PlayerInteractEvent` metadata hot path. It does not rewrite the display engine, opening transaction engine, exact-item persistence, or SQLite architecture.

## Code-level acceptance evidence

The 4.6 source enforces these fast paths:

- Wand: non-`BLAZE_ROD` items return before `hasItemMeta()`/`getItemMeta()`.
- Portable: non-`CHEST` items return before `hasItemMeta()`/`getItemMeta()`.
- Candidate items without metadata return before `getItemMeta()`.
- Wand identity + selected crate use one metadata/PDC acquisition per inspection.
- Portable marker/schema/token + HMAC decode use one metadata/PDC acquisition per inspection.
- The portable listener passes the already-inspected token into durable verification rather than performing a second metadata read.
- No new async task is created for ordinary interactions.
- Existing durable portable database verification remains asynchronous.

`InteractionIdentityFastPathTest` verifies that unrelated materials never call `hasItemMeta()` or `getItemMeta()` and that candidate materials without metadata never call `getItemMeta()`.

## CI matrix

| Gate | 4.6 result |
|---|---|
| Maven `clean verify` | populated by GitHub Actions for the release commit |
| skipped tests | required to be 0 |
| final JAR structure | required PASS |
| Java bytecode | required Java 25 / major 69 |
| Core/Keys shaded classes | required absent |
| JAR SHA-256 | generated and re-verified by CI |

## Runtime A/B plan

For production Spark validation, compare the same server/world state:

| Scenario | 4.5.0 | 4.6.0 LOCAL | 4.6.0 CORE_RUNTIME | Result |
|---|---:|---:|---:|---|
| idle | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| ordinary interact | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| ordinary mining | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| link wand | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| portable crate | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| linked crate open | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| 5 players | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| 10 players | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |
| mixed soak | NOT EXECUTED | NOT EXECUTED | NOT EXECUTED | staging evidence required |

No numerical performance percentage is claimed without a real Spark capture.

## What to inspect in Spark

- `WandService.interact`
- `WandService.isWand`
- `PortableCrateService.inspect`
- `ItemStack.getItemMeta`
- `CraftMetaItem`
- `CrateListener.interact`
- `LocationStore.at`

Under ordinary gameplay, PlexonCrates metadata acquisitions should be dramatically lower than total `PlayerInteractEvent` traffic because only `BLAZE_ROD` and `CHEST` candidates reach metadata inspection.

## Core 2 note

The released PlexonCore 2.0.0 does not provide shared `PlayerInteractEvent` acquisition. Consequently `CORE_RUNTIME` and standalone/local modes use the same optimized local interaction handlers in 4.6. Any difference between those modes should come only from Core module lifecycle overhead, not an alternate crate interaction path.

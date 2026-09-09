# PlexonCrates 4.6 — PlexonCore 2 Runtime Integration

PlexonCrates 4.6.0 compiles against the released PlexonCore 2.0.0 API while preserving the standalone-safe reflective bridge used by 4.5.

## Runtime detection

The module compatibility range is `>=1.0 <3.0`.

| Detected Core | Reported mode | Interaction ownership | Protection ownership |
|---|---|---|---|
| Core 2.x | `CORE_RUNTIME` | LOCAL | LOCAL |
| Core 1.x | `CORE_LEGACY` | LOCAL | LOCAL |
| absent/incompatible | `STANDALONE` | LOCAL | LOCAL |

`CORE_RUNTIME` means the Core 2 API/module runtime is available. It does **not** claim that PlayerInteract routing moved to Core in 4.6.

## Why PlayerInteract remains local

The released PlexonCore 2.0.0 `CoreEventGateway` provides the shared block-break acquisition path. It does not provide a `PlayerInteractEvent` subscription, mutable cancellation decision, or the LOWEST/HIGH phase contract needed to preserve existing PlexonCrates behavior.

PlexonCrates therefore follows the migration safety rule: where Core cannot provide exact semantic parity, the safety- or behavior-critical event remains local.

The 4.6 local path is optimized so unrelated interactions are cheap:

```text
main hand interaction
  -> WandService: material != BLAZE_ROD => return without ItemMeta
  -> CrateListener portable: material != CHEST => return without ItemMeta
  -> clicked block absent => return
  -> exact indexed LocationStore lookup
```

## Wand identity

A wand inspection now performs:

1. null/material gate (`BLAZE_ROD` only);
2. `hasItemMeta()` gate;
3. one `getItemMeta()` call;
4. one PDC acquisition;
5. marker + schema validation;
6. selected crate read from that same PDC.

The interaction handler reuses the inspected identity instead of calling `isWand()` and then acquiring metadata again.

## Portable identity

A portable inspection now performs:

1. null/material gate (`CHEST` only);
2. `hasItemMeta()` gate;
3. one `getItemMeta()` call;
4. one PDC acquisition;
5. marker + schema + token extraction;
6. HMAC decoding from the extracted token without another metadata read.

The listener passes the verified inspection into durable database verification, avoiding the former `isPortable()` + `decode()` double metadata acquisition.

The PDC envelope remains untrusted. Authorization still requires HMAC verification and the existing durable issuance row checks. No portable signing secret or database authority is moved into Core.

## Protection

All linked crate protections remain local in 4.6, including break, explosions, pistons, entity block changes, burn, fade, and bucket use. Core 2.0's block-break context is immutable and cannot replace Paper cancellation semantics.

## Standalone safety

`CoreBridgeFactory` remains a lazy reflective boundary. Core-specific classes are not eagerly linked when PlexonCore is absent. PlexonCore and PlexonKeys are `provided` dependencies and must not be shaded into the final JAR.

## Future migration gate

A later release may move interaction acquisition to Core only after Core provides all of the following:

- `PlayerInteractEvent` or equivalent subscription;
- synchronous main-thread callback contract;
- priority/phase parity for LOWEST and HIGH behavior;
- mutable cancellation semantics;
- safe clicked-block facts;
- lazy candidate-material item identity extraction;
- deterministic subscription cleanup.

Until then, LOCAL ownership is intentional rather than a fallback defect.

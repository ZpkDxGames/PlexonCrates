# PlexonCrates 6.0 — Exact Item Integrity

## Authority

Exact reward and physical-key identity is the native Paper `ItemStack` byte payload. PlexonCrates does not rebuild custom items from material, display name, lore, enchantments, PDC keys, model data, plugin IDs, or other visible metadata.

The canonical capture path is:

`source ItemStack -> clone -> amount-one native serialization -> SHA-256 -> native deserialize verification`

Captured quantity is separate delivery metadata. The amount-one template remains the exact identity used for persistence, comparison, preview restoration, queue storage, and later delivery.

## Capture boundary

6.0 validates new reward-item and key-template captures before mutating transient editor state. `ExactItemInspector` delegates to `ItemSnapshotCodec`, which performs the native serialization/round-trip and exposes UI-safe diagnostics:

- captured quantity;
- serialized payload size;
- full and short SHA-256 fingerprint;
- custom-data indicator;
- container-content indicator;
- real maximum stack size.

The source stack is never consumed, normalized, renamed, relored, or rewritten during inspection.

## Persistence and restore rules

- Stored bytes are fingerprint-checked before decode.
- Corrupt payloads fail closed.
- Delivery restores the exact amount-one template and splits quantity only by the restored item's actual maximum stack size.
- Older stored bytes are not required to reserialize byte-for-byte after a future Paper data-fix; the stored fingerprint protects the original payload before decode.
- Existing durable reward payloads are not mass re-encoded merely because an administrator opens or edits unrelated settings.

## Third-party custom items

PlexonCrates intentionally treats unknown metadata as opaque native item data. This is the compatibility strategy for ItemsAdder-like, Oraxen-like, Nexo-like, Slimefun-like, MMOItems-like, executable/custom item systems, and future Paper data components.

No direct dependency on those plugins is required for exact-byte preservation. Their runtime plugin may still be required by the server for the custom item's own behavior.

## GUI rules

The exact displayed item must remain an unmodified clone of the restored item. Chance text, click hints, fingerprints, and diagnostics belong on companion information controls or separate lore-bearing UI items, not by modifying the exact reward clone itself.

Replacing an exact item is an explicit editor action. A failed native capture or restore must leave the previous draft item unchanged.

## Regression coverage

6.0 tests enforce:

- source non-mutation;
- PDC and synthetic external identity preservation;
- Adventure name/lore and component-sensitive metadata;
- damage/unbreakable/glint state;
- nested bundle content preservation;
- quantity separation;
- fingerprint differentiation;
- corrupt-payload rejection;
- defensive byte-array copies;
- fail-closed reward/key capture ordering;
- native serialization/deserialization remaining the source of truth.

Real-server runtime certification must additionally exercise at least one representative production custom item if such a provider is installed on PlexonCraft.

# PlexonCrates 1.0.0 test checklist

## Automated

Run `mvn clean verify` on Java 25. The suite checks:

- deterministic weighted-selection boundaries;
- disabled reward exclusion and displayed chance calculations;
- inventory merge/overflow planning without mutating the live inventory;
- loading all four bundled crates and all 32 default rewards;
- every default reward pool totaling exactly 100 weight;
- fallback key availability for all four crate IDs;
- a complete key-consumption/opening/statistics flow in MockBukkit.

## Paper smoke test

1. Start Paper 26.2 on Java 25 with PlexonKeys 1.1.0 and PlexonCrates 1.0.0.
2. Confirm startup reports `PlexonKeys live templates`.
3. Run `/keysadmin set basic 1`, claim it in `/keys`, link a chest with `/pcrates set basic`, and right-click it.
4. Confirm exactly one key is consumed and exactly one reward is delivered even if the animation GUI is closed.
5. Capture a custom-NBT key with `/keysadmin setitem basic`, claim a fresh copy, and repeat the opening.
6. Sneak-right-click with multiple keys and confirm bulk consumption and reward counts match.
7. Fill the inventory, set `drop-overflow-items: false`, and confirm the open is rejected without key consumption.
8. Set it to `true`, retry, and confirm overflow is dropped at the player's feet.
9. Restart and confirm linked blocks, statistics, holograms, and particles return.
10. Attempt to break, explode, or piston-move a linked block and confirm protection.

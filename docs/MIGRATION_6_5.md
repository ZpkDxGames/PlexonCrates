# PlexonCrates 6.5 menu migration

`menus.yml` schema version is now `2`. On the first load of a pre-6.5 file PlexonCrates:

1. creates `menus.yml.pre-6.5.bak` before writing the migrated file;
2. installs the semantic theme and reusable layout metadata;
3. migrates structural list/footer slots to the 6.5 layout;
4. preserves administrator-customized item names, lore and materials wherever the redesign does not require a structural reset;
5. writes schema version 2 only after backup and migration succeed.

The migration does not read or mutate crate definitions, keys, rewards, SQLite state, exact item snapshots, claims, statistics or opening journals.

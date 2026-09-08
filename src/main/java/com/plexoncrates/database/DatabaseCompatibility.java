package com.plexoncrates.database;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup compatibility guard for databases created by pre-4.0 PlexonCrates builds.
 *
 * <p>Older releases used some of the same SQLite table names with different columns. SQLite's
 * {@code CREATE TABLE IF NOT EXISTS} cannot upgrade an existing table, so 4.0 detects structural
 * collisions before {@link DatabaseManager} creates its schema. Incompatible tables are renamed
 * in-place and retained as read-only legacy backups. A consistent SQLite backup is created before
 * any structural repair.</p>
 */
public final class DatabaseCompatibility {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = requiredColumns();
    private static final List<String> OWNED_INDEXES = List.of(
            "idx_legacy_reward_player",
            "idx_claims_player",
            "idx_history_player"
    );

    private DatabaseCompatibility() {}

    public static void prepare(PlexonCrates plugin, ConfigManager config) throws Exception {
        Path database = plugin.getDataFolder().toPath().resolve(config.databaseFile()).toAbsolutePath().normalize();
        Path parent = database.getParent();
        if (parent != null) Files.createDirectories(parent);

        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA busy_timeout=" + config.busyTimeoutMillis());
                pragma.execute("PRAGMA foreign_keys=ON");
            }

            DatabaseSchemaVersion.ensureMetaTable(connection);
            DatabaseSchemaVersion.requireSupported(connection);

            Map<String, Set<String>> incompatible = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : REQUIRED_COLUMNS.entrySet()) {
                if (!tableExists(connection, entry.getKey())) continue;
                Set<String> actual = tableColumns(connection, entry.getKey());
                if (!actual.containsAll(entry.getValue())) incompatible.put(entry.getKey(), actual);
            }
            if (incompatible.isEmpty()) return;

            Path backup = createConsistentBackup(connection, plugin, database);
            plugin.getLogger().warning("Created pre-upgrade SQLite backup: " + backup);

            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (String index : OWNED_INDEXES) {
                        statement.execute("DROP INDEX IF EXISTS " + quoteIdentifier(index));
                    }
                }

                for (Map.Entry<String, Set<String>> entry : incompatible.entrySet()) {
                    String table = entry.getKey();
                    String legacy = nextLegacyName(connection, table);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE " + quoteIdentifier(table)
                                + " RENAME TO " + quoteIdentifier(legacy));
                    }
                    Set<String> missing = new LinkedHashSet<>(REQUIRED_COLUMNS.get(table));
                    missing.removeAll(entry.getValue());
                    plugin.getLogger().warning("Preserved incompatible pre-4.0 SQLite table '" + table
                            + "' as '" + legacy + "' (missing columns: " + String.join(", ", missing) + ").");
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    static Map<String, String> inspectLegacyTables(Path database) throws Exception {
        if (!Files.isRegularFile(database)) return Map.of();
        Class.forName("org.sqlite.JDBC");
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name LIKE ?
                     ORDER BY name
                     """)) {
            for (String table : REQUIRED_COLUMNS.keySet()) {
                statement.setString(1, table + "_legacy_pre4%");
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> names = new ArrayList<>();
                    while (rows.next()) names.add(rows.getString(1));
                    if (!names.isEmpty()) result.put(table, String.join(",", names));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Path createConsistentBackup(Connection connection, PlexonCrates plugin, Path database) throws Exception {
        Path root = plugin.getDataFolder().toPath().resolve("backups/schema").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path backup = root.resolve("schema-before-v" + DatabaseSchemaVersion.CURRENT_VERSION + "-"
                + System.currentTimeMillis() + ".db").normalize();
        if (!backup.startsWith(root)) throw new IllegalStateException("Schema backup path escaped backup directory");
        if (Files.exists(backup)) throw new IllegalStateException("Schema backup target already exists: " + backup);

        String escaped = backup.toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + escaped + "'");
        }
        if (!Files.isRegularFile(backup) || Files.size(backup) == 0L) {
            throw new IllegalStateException("SQLite pre-upgrade backup was not created correctly for " + database);
        }
        return backup;
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Set<String> tableColumns(Connection connection, String table) throws Exception {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + quoteIdentifier(table) + ")")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        return Set.copyOf(columns);
    }

    private static String nextLegacyName(Connection connection, String table) throws Exception {
        String base = table + "_legacy_pre4";
        if (!tableExists(connection, base)) return base;
        for (int suffix = 2; suffix < 10_000; suffix++) {
            String candidate = base + "_" + suffix;
            if (!tableExists(connection, candidate)) return candidate;
        }
        throw new IllegalStateException("Could not allocate legacy SQLite table name for " + table);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        result.put("player_keys", Set.of("player_uuid", "crate_id", "amount", "updated_at"));
        result.put("crate_stats", Set.of("crate_id", "openings", "updated_at"));
        result.put("player_stats", Set.of("player_uuid", "crate_id", "openings", "updated_at"));
        result.put("opening_history", Set.of("id", "player_uuid", "player_name", "crate_id", "reward_id", "opened_at"));
        result.put("claims", Set.of("id", "player_uuid", "crate_id", "reward_id", "item_blob", "created_at"));
        result.put("migration_markers", Set.of("source", "imported_at", "metadata"));
        result.put("legacy_reward_wins", Set.of("source", "player_uuid", "player_name", "reward_id", "crate_id", "wins"));
        return Map.copyOf(result);
    }
}

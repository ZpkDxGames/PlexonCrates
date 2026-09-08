package com.plexoncrates.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

/**
 * Formal PlexonCrates SQLite schema version marker.
 *
 * <p>The integer is an internal database revision, independent of the plugin's semantic version.
 * A database created by a newer, unsupported PlexonCrates build is rejected rather than being
 * silently opened with an older schema implementation.</p>
 */
public final class DatabaseSchemaVersion {
    public static final int CURRENT_VERSION = 1;

    private DatabaseSchemaVersion() {}

    public static void ensureMetaTable(Connection connection) throws Exception {
        Objects.requireNonNull(connection, "connection");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta(
                      schema_version INTEGER NOT NULL,
                      upgraded_at INTEGER NOT NULL,
                      plugin_version TEXT NOT NULL
                    )
                    """);
        }
    }

    public static int readVersion(Connection connection) throws Exception {
        ensureMetaTable(connection);
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT schema_version
                     FROM schema_meta
                     ORDER BY upgraded_at DESC
                     LIMIT 1
                     """)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    public static void requireSupported(Connection connection) throws Exception {
        int version = readVersion(connection);
        if (version > CURRENT_VERSION) {
            throw new IllegalStateException("SQLite schema version " + version
                    + " is newer than supported version " + CURRENT_VERSION);
        }
    }

    public static void markCurrent(Connection connection, String pluginVersion) throws Exception {
        Objects.requireNonNull(connection, "connection");
        String version = pluginVersion == null || pluginVersion.isBlank() ? "unknown" : pluginVersion;
        ensureMetaTable(connection);
        connection.setAutoCommit(false);
        try {
            try (Statement delete = connection.createStatement()) {
                delete.executeUpdate("DELETE FROM schema_meta");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO schema_meta(schema_version, upgraded_at, plugin_version)
                    VALUES(?, ?, ?)
                    """)) {
                insert.setInt(1, CURRENT_VERSION);
                insert.setLong(2, System.currentTimeMillis());
                insert.setString(3, version);
                insert.executeUpdate();
            }
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    public static void markCurrent(Path database, String pluginVersion, int busyTimeoutMillis) throws Exception {
        Objects.requireNonNull(database, "database");
        Path parent = database.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=" + Math.max(1000, busyTimeoutMillis));
            }
            markCurrent(connection, pluginVersion);
        }
    }
}

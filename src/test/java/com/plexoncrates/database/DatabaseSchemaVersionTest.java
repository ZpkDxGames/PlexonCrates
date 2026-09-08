package com.plexoncrates.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseSchemaVersionTest {
    @TempDir
    Path tempDir;

    @Test
    void emptyMetaTableIsUnversionedUntilMarked() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("schema.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            DatabaseSchemaVersion.ensureMetaTable(connection);
            assertEquals(0, DatabaseSchemaVersion.readVersion(connection));

            DatabaseSchemaVersion.markCurrent(connection, "4.0.0-test");
            assertEquals(DatabaseSchemaVersion.CURRENT_VERSION, DatabaseSchemaVersion.readVersion(connection));

            try (var rows = connection.createStatement().executeQuery(
                    "SELECT plugin_version FROM schema_meta LIMIT 1")) {
                assertEquals("4.0.0-test", rows.getString(1));
            }
        }
    }

    @Test
    void rejectsDatabaseCreatedByNewerSchema() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path database = tempDir.resolve("future.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            DatabaseSchemaVersion.ensureMetaTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM schema_meta");
                statement.executeUpdate("INSERT INTO schema_meta(schema_version, upgraded_at, plugin_version) "
                        + "VALUES(" + (DatabaseSchemaVersion.CURRENT_VERSION + 1) + ", 1, 'future')");
            }
            assertThrows(IllegalStateException.class, () -> DatabaseSchemaVersion.requireSupported(connection));
        }
    }
}

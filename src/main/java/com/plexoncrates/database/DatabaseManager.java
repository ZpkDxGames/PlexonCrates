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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;

public final class DatabaseManager {
    public record ClaimRecord(long id, UUID playerId, String crateId, String rewardId, byte[] itemBytes, Instant createdAt) {
        public ClaimRecord {
            itemBytes = itemBytes.clone();
        }

        @Override
        public byte[] itemBytes() {
            return itemBytes.clone();
        }
    }

    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final ExecutorService executor;
    private final Path databaseFile;
    private final CompletableFuture<Void> ready;

    public DatabaseManager(PlexonCrates plugin, ConfigManager config, ExecutorService executor) {
        this.plugin = plugin;
        this.config = config;
        this.executor = executor;
        this.databaseFile = plugin.getDataFolder().toPath().resolve(config.databaseFile()).normalize();
        this.ready = CompletableFuture.runAsync(this::initializeDatabase, executor);
    }

    public CompletableFuture<Void> ready() { return ready; }
    public boolean isReady() { return ready.isDone() && !ready.isCompletedExceptionally(); }

    public CompletableFuture<Map<String, Long>> virtualKeys(UUID playerId) {
        return supply(connection -> {
            Map<String, Long> keys = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT crate_id, amount FROM player_keys WHERE player_uuid = ? ORDER BY crate_id")) {
                statement.setString(1, playerId.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) keys.put(rows.getString(1), rows.getLong(2));
                }
            }
            return Map.copyOf(keys);
        });
    }

    public CompletableFuture<Long> virtualKeyBalance(UUID playerId, String crateId) {
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT amount FROM player_keys WHERE player_uuid = ? AND crate_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, crateId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<Long> grantVirtualKeys(UUID playerId, String crateId, long amount) {
        if (amount == 0) return virtualKeyBalance(playerId, crateId);
        return supply(connection -> {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO player_keys(player_uuid, crate_id, amount, updated_at)
                        VALUES(?, ?, ?, ?)
                        ON CONFLICT(player_uuid, crate_id) DO UPDATE SET
                          amount = MAX(0, player_keys.amount + excluded.amount),
                          updated_at = excluded.updated_at
                        """)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, crateId);
                    statement.setLong(3, amount);
                    statement.setLong(4, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                long balance;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT amount FROM player_keys WHERE player_uuid = ? AND crate_id = ?")) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, crateId);
                    try (ResultSet rows = statement.executeQuery()) {
                        balance = rows.next() ? rows.getLong(1) : 0L;
                    }
                }
                connection.commit();
                return balance;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public CompletableFuture<Boolean> tryConsumeVirtualKey(UUID playerId, String crateId) {
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE player_keys
                    SET amount = amount - 1, updated_at = ?
                    WHERE player_uuid = ? AND crate_id = ? AND amount > 0
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, playerId.toString());
                statement.setString(3, crateId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> recordOpening(UUID playerId, String playerName, String crateId, String rewardId) {
        return run(connection -> {
            connection.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();
                try (PreparedStatement global = connection.prepareStatement("""
                        INSERT INTO crate_stats(crate_id, openings, updated_at)
                        VALUES(?, 1, ?)
                        ON CONFLICT(crate_id) DO UPDATE SET
                          openings = crate_stats.openings + 1,
                          updated_at = excluded.updated_at
                        """)) {
                    global.setString(1, crateId);
                    global.setLong(2, now);
                    global.executeUpdate();
                }
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO player_stats(player_uuid, crate_id, openings, updated_at)
                        VALUES(?, ?, 1, ?)
                        ON CONFLICT(player_uuid, crate_id) DO UPDATE SET
                          openings = player_stats.openings + 1,
                          updated_at = excluded.updated_at
                        """)) {
                    player.setString(1, playerId.toString());
                    player.setString(2, crateId);
                    player.setLong(3, now);
                    player.executeUpdate();
                }
                try (PreparedStatement history = connection.prepareStatement("""
                        INSERT INTO opening_history(player_uuid, player_name, crate_id, reward_id, opened_at)
                        VALUES(?, ?, ?, ?, ?)
                        """)) {
                    history.setString(1, playerId.toString());
                    history.setString(2, playerName);
                    history.setString(3, crateId);
                    history.setString(4, rewardId);
                    history.setLong(5, now);
                    history.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    public CompletableFuture<Long> globalOpenings(String crateId) {
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT openings FROM crate_stats WHERE crate_id = ?")) {
                statement.setString(1, crateId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<Long> playerOpenings(UUID playerId, String crateId) {
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT openings FROM player_stats WHERE player_uuid = ? AND crate_id = ?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, crateId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<Long> queueClaim(UUID playerId, String crateId, String rewardId, byte[] itemBytes) {
        byte[] copy = itemBytes.clone();
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO claims(player_uuid, crate_id, reward_id, item_blob, created_at)
                    VALUES(?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, crateId);
                statement.setString(3, rewardId);
                statement.setBytes(4, copy);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("SQLite did not return a claim ID");
                    return keys.getLong(1);
                }
            }
        });
    }

    public CompletableFuture<List<ClaimRecord>> claims(UUID playerId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        return supply(connection -> {
            List<ClaimRecord> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, crate_id, reward_id, item_blob, created_at
                    FROM claims
                    WHERE player_uuid = ?
                    ORDER BY id
                    LIMIT ? OFFSET ?
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, safeLimit);
                statement.setInt(3, safeOffset);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        claims.add(new ClaimRecord(rows.getLong(1), playerId, rows.getString(2), rows.getString(3),
                                rows.getBytes(4), Instant.ofEpochMilli(rows.getLong(5))));
                    }
                }
            }
            return List.copyOf(claims);
        });
    }

    public CompletableFuture<Boolean> deleteClaim(long claimId, UUID playerId) {
        return supply(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM claims WHERE id = ? AND player_uuid = ?")) {
                statement.setLong(1, claimId);
                statement.setString(2, playerId.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private void initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(databaseFile.getParent());
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS player_keys(
                          player_uuid TEXT NOT NULL,
                          crate_id TEXT NOT NULL,
                          amount INTEGER NOT NULL DEFAULT 0 CHECK(amount >= 0),
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(player_uuid, crate_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS crate_stats(
                          crate_id TEXT PRIMARY KEY,
                          openings INTEGER NOT NULL DEFAULT 0,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS player_stats(
                          player_uuid TEXT NOT NULL,
                          crate_id TEXT NOT NULL,
                          openings INTEGER NOT NULL DEFAULT 0,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(player_uuid, crate_id)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS opening_history(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          player_uuid TEXT NOT NULL,
                          player_name TEXT NOT NULL,
                          crate_id TEXT NOT NULL,
                          reward_id TEXT NOT NULL,
                          opened_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS claims(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          player_uuid TEXT NOT NULL,
                          crate_id TEXT NOT NULL,
                          reward_id TEXT NOT NULL,
                          item_blob BLOB NOT NULL,
                          created_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE INDEX IF NOT EXISTS idx_claims_player ON claims(player_uuid, id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_history_player ON opening_history(player_uuid, opened_at)");
            }
            plugin.getLogger().info("SQLite backend is ready: " + databaseFile);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize PlexonCrates SQLite backend", error);
            throw new RuntimeException(error);
        }
    }

    private Connection connect() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + config.busyTimeoutMillis());
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private CompletableFuture<Void> run(SqlWork work) {
        return ready.thenRunAsync(() -> {
            try (Connection connection = connect()) {
                work.run(connection);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supply(SqlQuery<T> query) {
        return ready.thenApplyAsync(ignored -> {
            try (Connection connection = connect()) {
                return query.run(connection);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface SqlWork { void run(Connection connection) throws Exception; }

    @FunctionalInterface
    private interface SqlQuery<T> { T run(Connection connection) throws Exception; }
}

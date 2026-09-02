package com.antondev.crates.database;

import com.antondev.crates.config.ItemCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.inventory.ItemStack;

/**
 * Owns PlexonCrates' SQLite schema and the single bounded database writer.
 * Bukkit objects never cross into this class; runtime calls use immutable DTOs.
 */
public final class DatabaseService implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    public record StoredLocation(
            UUID worldUuid, String worldName, int x, int y, int z, String crateId, Instant updatedAt) {}

    public record StatsSnapshot(Map<String, Long> global, Map<UUID, Map<String, Long>> players) {
        public StatsSnapshot {
            global = Map.copyOf(global);
            var playerCopy = new LinkedHashMap<UUID, Map<String, Long>>();
            players.forEach((uuid, values) -> playerCopy.put(uuid, Map.copyOf(values)));
            players = Map.copyOf(playerCopy);
        }
    }

    public record JournalRecord(
            UUID transactionId,
            UUID playerId,
            String playerName,
            String crateId,
            String keyId,
            int keyAmount,
            int openingCount,
            String source,
            String rewardIds,
            Instant createdAt) {}

    public record OpeningRecord(
            UUID transactionId,
            UUID playerId,
            String playerName,
            String crateId,
            String keyId,
            int keyAmount,
            int openingCount,
            String source,
            String rewardIds,
            String location,
            int overflowCount,
            Instant completedAt) {}

    public record AuditRecord(
            UUID actorId,
            String actorName,
            String action,
            String targetType,
            String targetId,
            String summary,
            Instant createdAt) {}

    public record RewardPlayerState(
            UUID playerId, String crateId, String rewardId, long totalWins, long windowWins,
            long windowStartedAt, long lastWonAt) {}

    public record RewardGlobalState(
            String crateId, String rewardId, long totalWins, long windowWins, long windowStartedAt) {}

    public record PityState(UUID playerId, String crateId, int misses) {}

    public record RewardStateSnapshot(
            List<RewardPlayerState> players, List<RewardGlobalState> global, List<PityState> pity) {
        public RewardStateSnapshot {
            players = List.copyOf(players);
            global = List.copyOf(global);
            pity = List.copyOf(pity);
        }
    }

    public record RewardMutation(RewardPlayerState player, RewardGlobalState global) {}

    public record RewardStateCommit(List<RewardMutation> rewards, PityState pity) {
        public RewardStateCommit {
            rewards = List.copyOf(rewards);
        }

        public static RewardStateCommit empty() {
            return new RewardStateCommit(List.of(), null);
        }
    }

    private final Logger logger;
    private final String jdbcUrl;
    private final ThreadPoolExecutor writer;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DatabaseService(Logger logger, Path file, int maximumQueuedWrites) throws Exception {
        this.logger = logger;
        Files.createDirectories(file.getParent());
        this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        Class.forName("org.sqlite.JDBC");
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(64, maximumQueuedWrites)), runnable -> {
                    Thread thread = new Thread(runnable, "PlexonCrates-Database");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        initialize();
    }

    private void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS locations (
                        world_uuid TEXT,
                        world_name TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        crate_id TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (world_name, x, y, z)
                    )
                    """);
            statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS locations_uuid_position ON locations(world_uuid, x, y, z) WHERE world_uuid IS NOT NULL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS statistics_global (
                        crate_id TEXT PRIMARY KEY,
                        openings INTEGER NOT NULL CHECK(openings >= 0)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS statistics_player (
                        player_uuid TEXT NOT NULL,
                        crate_id TEXT NOT NULL,
                        openings INTEGER NOT NULL CHECK(openings >= 0),
                        PRIMARY KEY (player_uuid, crate_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS key_template_cache (
                        key_id TEXT PRIMARY KEY,
                        item_base64 TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS opening_journal (
                        transaction_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        crate_id TEXT NOT NULL,
                        key_id TEXT NOT NULL,
                        key_amount INTEGER NOT NULL,
                        opening_count INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        reward_ids TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        detail TEXT NOT NULL DEFAULT '',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS opening_history (
                        transaction_id TEXT PRIMARY KEY,
                        player_uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        crate_id TEXT NOT NULL,
                        key_id TEXT NOT NULL,
                        key_amount INTEGER NOT NULL,
                        opening_count INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        reward_ids TEXT NOT NULL,
                        location TEXT NOT NULL,
                        overflow_count INTEGER NOT NULL,
                        completed_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS history_player_time ON opening_history(player_uuid, completed_at DESC)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_player_state (
                        player_uuid TEXT NOT NULL,
                        crate_id TEXT NOT NULL,
                        reward_id TEXT NOT NULL,
                        total_wins INTEGER NOT NULL DEFAULT 0,
                        window_wins INTEGER NOT NULL DEFAULT 0,
                        window_started_at INTEGER NOT NULL DEFAULT 0,
                        last_won_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, crate_id, reward_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS reward_global_state (
                        crate_id TEXT NOT NULL,
                        reward_id TEXT NOT NULL,
                        total_wins INTEGER NOT NULL DEFAULT 0,
                        window_wins INTEGER NOT NULL DEFAULT 0,
                        window_started_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (crate_id, reward_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pity_state (
                        player_uuid TEXT NOT NULL,
                        crate_id TEXT NOT NULL,
                        misses INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (player_uuid, crate_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        actor_uuid TEXT,
                        actor_name TEXT NOT NULL,
                        action TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS drafts (
                        crate_id TEXT PRIMARY KEY,
                        yaml TEXT NOT NULL,
                        editor_uuid TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS migration_history (
                        marker TEXT PRIMARY KEY,
                        imported_at INTEGER NOT NULL
                    )
                    """);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                upsert.setString(1, Integer.toString(SCHEMA_VERSION));
                upsert.executeUpdate();
            }
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        return connection;
    }

    public List<StoredLocation> loadLocations() throws SQLException {
        var result = new ArrayList<StoredLocation>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT world_uuid, world_name, x, y, z, crate_id, updated_at FROM locations ORDER BY world_name, x, y, z");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new StoredLocation(nullableUuid(rows.getString(1)), rows.getString(2), rows.getInt(3),
                        rows.getInt(4), rows.getInt(5), rows.getString(6), Instant.ofEpochMilli(rows.getLong(7))));
            }
        }
        return List.copyOf(result);
    }

    public StatsSnapshot loadStatistics() throws SQLException {
        var global = new LinkedHashMap<String, Long>();
        var players = new LinkedHashMap<UUID, Map<String, Long>>();
        try (Connection connection = connect();
             PreparedStatement globalQuery = connection.prepareStatement("SELECT crate_id, openings FROM statistics_global");
             ResultSet globalRows = globalQuery.executeQuery()) {
            while (globalRows.next()) global.put(globalRows.getString(1), globalRows.getLong(2));
        }
        try (Connection connection = connect();
             PreparedStatement playerQuery = connection.prepareStatement("SELECT player_uuid, crate_id, openings FROM statistics_player");
             ResultSet playerRows = playerQuery.executeQuery()) {
            while (playerRows.next()) {
                UUID uuid = UUID.fromString(playerRows.getString(1));
                players.computeIfAbsent(uuid, ignored -> new LinkedHashMap<>())
                        .put(playerRows.getString(2), playerRows.getLong(3));
            }
        }
        return new StatsSnapshot(global, players);
    }

    public Map<String, ItemStack> loadKeyTemplateCache() throws SQLException {
        var result = new LinkedHashMap<String, ItemStack>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT key_id, item_base64 FROM key_template_cache");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    result.put(rows.getString(1), ItemCodec.decode(rows.getString(2), true));
                } catch (IllegalArgumentException error) {
                    logger.log(Level.WARNING, "Ignoring an invalid cached key template for " + rows.getString(1), error);
                }
            }
        }
        return Map.copyOf(result);
    }

    public int pendingJournalCount() throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM opening_journal WHERE stage NOT IN ('COMPLETED', 'CANCELLED')");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    public List<OpeningRecord> history(UUID playerId, int limit, int offset) throws SQLException {
        var result = new ArrayList<OpeningRecord>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                SELECT transaction_id, player_uuid, player_name, crate_id, key_id, key_amount, opening_count, source,
                       reward_ids, location, overflow_count, completed_at
                FROM opening_history WHERE player_uuid = ? ORDER BY completed_at DESC LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, Math.max(1, Math.min(limit, 100)));
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(openingRecord(rows));
            }
        }
        return List.copyOf(result);
    }

    public CompletableFuture<List<OpeningRecord>> historyAsync(UUID playerId, int limit, int offset) {
        return submitQuery("load opening history", connection -> history(connection, playerId, limit, offset));
    }

    public RewardStateSnapshot loadRewardStates() throws SQLException {
        var players = new ArrayList<RewardPlayerState>();
        var global = new ArrayList<RewardGlobalState>();
        var pity = new ArrayList<PityState>();
        try (Connection connection = connect()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_uuid, crate_id, reward_id, total_wins, window_wins, window_started_at, last_won_at
                    FROM reward_player_state
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) players.add(new RewardPlayerState(UUID.fromString(rows.getString(1)),
                        rows.getString(2), rows.getString(3), rows.getLong(4), rows.getLong(5), rows.getLong(6), rows.getLong(7)));
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT crate_id, reward_id, total_wins, window_wins, window_started_at
                    FROM reward_global_state
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) global.add(new RewardGlobalState(rows.getString(1), rows.getString(2),
                        rows.getLong(3), rows.getLong(4), rows.getLong(5)));
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_uuid, crate_id, misses FROM pity_state"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) pity.add(new PityState(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getInt(3)));
            }
        }
        return new RewardStateSnapshot(players, global, pity);
    }

    public CompletableFuture<Void> saveLocation(StoredLocation location) {
        return submit("save location", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO locations(world_uuid, world_name, x, y, z, crate_id, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(world_name, x, y, z) DO UPDATE SET
                      world_uuid=excluded.world_uuid, crate_id=excluded.crate_id, updated_at=excluded.updated_at
                    """)) {
                nullableUuid(statement, 1, location.worldUuid());
                statement.setString(2, location.worldName());
                statement.setInt(3, location.x());
                statement.setInt(4, location.y());
                statement.setInt(5, location.z());
                statement.setString(6, location.crateId());
                statement.setLong(7, location.updatedAt().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> removeLocation(StoredLocation location) {
        return submit("remove location", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM locations WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                statement.setString(1, location.worldName());
                statement.setInt(2, location.x());
                statement.setInt(3, location.y());
                statement.setInt(4, location.z());
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> cacheKeyTemplate(String keyId, ItemStack template) {
        String encoded = ItemCodec.capture(template, true);
        long now = System.currentTimeMillis();
        return submit("cache key template", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO key_template_cache(key_id, item_base64, updated_at) VALUES(?, ?, ?)
                    ON CONFLICT(key_id) DO UPDATE SET item_base64=excluded.item_base64, updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, keyId);
                statement.setString(2, encoded);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> removeKeyTemplateCache(String keyId) {
        return submit("remove cached key template", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM key_template_cache WHERE key_id = ?")) {
                statement.setString(1, keyId);
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> prepareJournal(JournalRecord journal) {
        return submit("prepare opening journal", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO opening_journal(transaction_id, player_uuid, player_name, crate_id, key_id,
                        key_amount, opening_count, source, reward_ids, stage, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                    """)) {
                statement.setString(1, journal.transactionId().toString());
                statement.setString(2, journal.playerId().toString());
                statement.setString(3, journal.playerName());
                statement.setString(4, journal.crateId());
                statement.setString(5, journal.keyId());
                statement.setInt(6, journal.keyAmount());
                statement.setInt(7, journal.openingCount());
                statement.setString(8, journal.source());
                statement.setString(9, journal.rewardIds());
                statement.setLong(10, journal.createdAt().toEpochMilli());
                statement.setLong(11, journal.createdAt().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> completeOpening(OpeningRecord record) {
        return completeOpening(record, RewardStateCommit.empty());
    }

    public CompletableFuture<Void> completeOpening(OpeningRecord record, RewardStateCommit state) {
        return submitTransaction("complete opening", connection -> {
            try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO opening_history(transaction_id, player_uuid, player_name, crate_id, key_id,
                        key_amount, opening_count, source, reward_ids, location, overflow_count, completed_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                history.setString(1, record.transactionId().toString());
                history.setString(2, record.playerId().toString());
                history.setString(3, record.playerName());
                history.setString(4, record.crateId());
                history.setString(5, record.keyId());
                history.setInt(6, record.keyAmount());
                history.setInt(7, record.openingCount());
                history.setString(8, record.source());
                history.setString(9, record.rewardIds());
                history.setString(10, record.location());
                history.setInt(11, record.overflowCount());
                history.setLong(12, record.completedAt().toEpochMilli());
                history.executeUpdate();
            }
            try (PreparedStatement global = connection.prepareStatement("""
                    INSERT INTO statistics_global(crate_id, openings) VALUES(?, ?)
                    ON CONFLICT(crate_id) DO UPDATE SET openings=statistics_global.openings + excluded.openings
                    """)) {
                global.setString(1, record.crateId());
                global.setInt(2, record.openingCount());
                global.executeUpdate();
            }
            try (PreparedStatement player = connection.prepareStatement("""
                    INSERT INTO statistics_player(player_uuid, crate_id, openings) VALUES(?, ?, ?)
                    ON CONFLICT(player_uuid, crate_id) DO UPDATE SET openings=statistics_player.openings + excluded.openings
                    """)) {
                player.setString(1, record.playerId().toString());
                player.setString(2, record.crateId());
                player.setInt(3, record.openingCount());
                player.executeUpdate();
            }
            try (PreparedStatement playerState = connection.prepareStatement("""
                    INSERT INTO reward_player_state(player_uuid, crate_id, reward_id, total_wins, window_wins,
                        window_started_at, last_won_at) VALUES(?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(player_uuid, crate_id, reward_id) DO UPDATE SET
                        total_wins=excluded.total_wins, window_wins=excluded.window_wins,
                        window_started_at=excluded.window_started_at, last_won_at=excluded.last_won_at
                    """); PreparedStatement globalState = connection.prepareStatement("""
                    INSERT INTO reward_global_state(crate_id, reward_id, total_wins, window_wins, window_started_at)
                        VALUES(?, ?, ?, ?, ?)
                    ON CONFLICT(crate_id, reward_id) DO UPDATE SET
                        total_wins=excluded.total_wins, window_wins=excluded.window_wins,
                        window_started_at=excluded.window_started_at
                    """)) {
                for (RewardMutation mutation : state.rewards()) {
                    RewardPlayerState playerValue = mutation.player();
                    playerState.setString(1, playerValue.playerId().toString());
                    playerState.setString(2, playerValue.crateId());
                    playerState.setString(3, playerValue.rewardId());
                    playerState.setLong(4, playerValue.totalWins());
                    playerState.setLong(5, playerValue.windowWins());
                    playerState.setLong(6, playerValue.windowStartedAt());
                    playerState.setLong(7, playerValue.lastWonAt());
                    playerState.addBatch();

                    RewardGlobalState globalValue = mutation.global();
                    globalState.setString(1, globalValue.crateId());
                    globalState.setString(2, globalValue.rewardId());
                    globalState.setLong(3, globalValue.totalWins());
                    globalState.setLong(4, globalValue.windowWins());
                    globalState.setLong(5, globalValue.windowStartedAt());
                    globalState.addBatch();
                }
                playerState.executeBatch();
                globalState.executeBatch();
            }
            if (state.pity() != null) {
                try (PreparedStatement pity = connection.prepareStatement("""
                        INSERT INTO pity_state(player_uuid, crate_id, misses) VALUES(?, ?, ?)
                        ON CONFLICT(player_uuid, crate_id) DO UPDATE SET misses=excluded.misses
                        """)) {
                    pity.setString(1, state.pity().playerId().toString());
                    pity.setString(2, state.pity().crateId());
                    pity.setInt(3, state.pity().misses());
                    pity.executeUpdate();
                }
            }
            updateJournal(connection, record.transactionId(), "COMPLETED", "");
        });
    }

    public CompletableFuture<Void> awaitIdle() {
        return submit("wait for database queue", ignored -> { });
    }

    public CompletableFuture<Void> updateJournal(UUID transactionId, String stage, String detail) {
        return submit("update opening journal", connection -> updateJournal(connection, transactionId, stage, detail));
    }

    public CompletableFuture<Void> audit(AuditRecord record) {
        return submit("write audit record", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO audit_log(actor_uuid, actor_name, action, target_type, target_id, summary, created_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?)
                    """)) {
                nullableUuid(statement, 1, record.actorId());
                statement.setString(2, record.actorName());
                statement.setString(3, record.action());
                statement.setString(4, record.targetType());
                statement.setString(5, record.targetId());
                statement.setString(6, record.summary());
                statement.setLong(7, record.createdAt().toEpochMilli());
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> saveDraft(String crateId, String yaml, UUID editorId) {
        long now = System.currentTimeMillis();
        return submit("save crate draft", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO drafts(crate_id, yaml, editor_uuid, updated_at) VALUES(?, ?, ?, ?)
                    ON CONFLICT(crate_id) DO UPDATE SET yaml=excluded.yaml, editor_uuid=excluded.editor_uuid,
                        updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, crateId);
                statement.setString(2, yaml);
                nullableUuid(statement, 3, editorId);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> createBackup(Path dataFolder, Path backupDirectory) {
        Path destination = backupDirectory.resolve("data/plexoncrates.db").toAbsolutePath().normalize();
        return submit("create backup", connection -> {
            Files.createDirectories(destination.getParent());
            for (String fileName : List.of("config.yml", "keys.yml", "menus.yml", "messages.yml",
                    "locations.yml", "statistics.yml")) {
                Path source = dataFolder.resolve(fileName);
                if (!Files.isRegularFile(source)) continue;
                Path target = backupDirectory.resolve(fileName);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Path crates = dataFolder.resolve("crates");
            if (Files.isDirectory(crates)) {
                try (var files = Files.list(crates)) {
                    for (Path source : files.filter(Files::isRegularFile).toList()) {
                        Path target = backupDirectory.resolve("crates").resolve(source.getFileName());
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            String escaped = destination.toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
            Files.writeString(backupDirectory.resolve("BACKUP.txt"),
                    "PlexonCrates 2.0 backup\nCreated: " + Instant.now() + "\nSchema: " + SCHEMA_VERSION + "\n",
                    StandardCharsets.UTF_8);
        });
    }

    public void importLegacy(String marker, List<StoredLocation> locations, StatsSnapshot statistics) throws SQLException {
        try {
            importLegacy(marker, locations, statistics, () -> { });
        } catch (SQLException error) {
            throw error;
        } catch (Exception error) {
            throw new SQLException("Legacy migration commit failed", error);
        }
    }

    public void importLegacy(String marker, List<StoredLocation> locations, StatsSnapshot statistics,
                             MigrationFileCommit fileCommit) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                if (migrationExists(connection, marker)) {
                    connection.rollback();
                    fileCommit.commit();
                    return;
                }
                try (PreparedStatement location = connection.prepareStatement("""
                        INSERT OR IGNORE INTO locations(world_uuid, world_name, x, y, z, crate_id, updated_at)
                        VALUES(?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (StoredLocation value : locations) {
                        nullableUuid(location, 1, value.worldUuid());
                        location.setString(2, value.worldName());
                        location.setInt(3, value.x());
                        location.setInt(4, value.y());
                        location.setInt(5, value.z());
                        location.setString(6, value.crateId());
                        location.setLong(7, value.updatedAt().toEpochMilli());
                        location.addBatch();
                    }
                    location.executeBatch();
                }
                try (PreparedStatement global = connection.prepareStatement("""
                        INSERT INTO statistics_global(crate_id, openings) VALUES(?, ?)
                        ON CONFLICT(crate_id) DO UPDATE SET openings=MAX(openings, excluded.openings)
                        """)) {
                    for (Map.Entry<String, Long> entry : statistics.global().entrySet()) {
                        global.setString(1, entry.getKey());
                        global.setLong(2, entry.getValue());
                        global.addBatch();
                    }
                    global.executeBatch();
                }
                try (PreparedStatement player = connection.prepareStatement("""
                        INSERT INTO statistics_player(player_uuid, crate_id, openings) VALUES(?, ?, ?)
                        ON CONFLICT(player_uuid, crate_id) DO UPDATE SET openings=MAX(openings, excluded.openings)
                        """)) {
                    for (Map.Entry<UUID, Map<String, Long>> owner : statistics.players().entrySet()) {
                        for (Map.Entry<String, Long> entry : owner.getValue().entrySet()) {
                            player.setString(1, owner.getKey().toString());
                            player.setString(2, entry.getKey());
                            player.setLong(3, entry.getValue());
                            player.addBatch();
                        }
                    }
                    player.executeBatch();
                }
                try (PreparedStatement history = connection.prepareStatement(
                        "INSERT INTO migration_history(marker, imported_at) VALUES(?, ?)")) {
                    history.setString(1, marker);
                    history.setLong(2, System.currentTimeMillis());
                    history.executeUpdate();
                }
                fileCommit.commit();
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @FunctionalInterface
    public interface MigrationFileCommit {
        void commit() throws Exception;
    }

    public int queuedWrites() {
        return writer.getQueue().size();
    }

    public boolean isOverloaded() {
        return writer.getQueue().remainingCapacity() == 0;
    }

    private CompletableFuture<Void> submit(String description, SqlWork work) {
        return submitInternal(description, false, work);
    }

    private CompletableFuture<Void> submitTransaction(String description, SqlWork work) {
        return submitInternal(description, true, work);
    }

    private CompletableFuture<Void> submitInternal(String description, boolean transaction, SqlWork work) {
        var future = new CompletableFuture<Void>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Database is closed"));
            return future;
        }
        try {
            writer.execute(() -> {
                try (Connection connection = connect()) {
                    if (transaction) connection.setAutoCommit(false);
                    try {
                        work.run(connection);
                        if (transaction) connection.commit();
                        future.complete(null);
                    } catch (Exception error) {
                        if (transaction) connection.rollback();
                        future.completeExceptionally(error);
                        logger.log(Level.SEVERE, "Could not " + description, error);
                    }
                } catch (Exception error) {
                    future.completeExceptionally(error);
                    logger.log(Level.SEVERE, "Could not " + description, error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new IllegalStateException("Database queue is full", error));
            logger.warning("PlexonCrates database queue is full; rejected operation: " + description);
        }
        return future;
    }

    private <T> CompletableFuture<T> submitQuery(String description, SqlQuery<T> query) {
        var future = new CompletableFuture<T>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Database is closed"));
            return future;
        }
        try {
            writer.execute(() -> {
                try (Connection connection = connect()) {
                    future.complete(query.run(connection));
                } catch (Exception error) {
                    future.completeExceptionally(error);
                    logger.log(Level.SEVERE, "Could not " + description, error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(new IllegalStateException("Database queue is full", error));
            logger.warning("PlexonCrates database queue is full; rejected operation: " + description);
        }
        return future;
    }

    private static void updateJournal(Connection connection, UUID transactionId, String stage, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE opening_journal SET stage = ?, detail = ?, updated_at = ? WHERE transaction_id = ?")) {
            statement.setString(1, stage);
            statement.setString(2, detail == null ? "" : detail);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, transactionId.toString());
            statement.executeUpdate();
        }
    }

    private static boolean migrationExists(Connection connection, String marker) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM migration_history WHERE marker = ?")) {
            statement.setString(1, marker);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static OpeningRecord openingRecord(ResultSet rows) throws SQLException {
        return new OpeningRecord(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), rows.getString(3),
                rows.getString(4), rows.getString(5), rows.getInt(6), rows.getInt(7), rows.getString(8), rows.getString(9),
                rows.getString(10), rows.getInt(11), Instant.ofEpochMilli(rows.getLong(12)));
    }

    private static List<OpeningRecord> history(Connection connection, UUID playerId, int limit, int offset) throws SQLException {
        var result = new ArrayList<OpeningRecord>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT transaction_id, player_uuid, player_name, crate_id, key_id, key_amount, opening_count, source,
                       reward_ids, location, overflow_count, completed_at
                FROM opening_history WHERE player_uuid = ? ORDER BY completed_at DESC LIMIT ? OFFSET ?
                """)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, Math.max(1, Math.min(limit, 100)));
            statement.setInt(3, Math.max(0, offset));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(openingRecord(rows));
            }
        }
        return List.copyOf(result);
    }

    private static UUID nullableUuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static void nullableUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warning("Database queue did not drain before shutdown; pending operations were interrupted.");
                writer.shutdownNow();
            }
        } catch (InterruptedException error) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    @FunctionalInterface
    private interface SqlQuery<T> {
        T run(Connection connection) throws Exception;
    }
}

package com.antondev.crates.database;

import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.domain.draft.DefinitionDraft;
import com.antondev.crates.domain.draft.DraftMutation;
import com.antondev.crates.domain.draft.DraftSaveState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    public static final int SCHEMA_VERSION = 3;

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

    public record DefinitionItemData(
            int actionIndex, byte[] bytes, int deliveryAmount, String material,
            int serializedSize, String sha256, Instant capturedAt) {
        public DefinitionItemData {
            bytes = copyBytes(bytes, "item bytes");
            material = requiredText(material, "material");
            sha256 = requiredText(sha256, "sha256");
            capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt");
            if (actionIndex < 0 || deliveryAmount < 1 || serializedSize != bytes.length) {
                throw new IllegalArgumentException("Invalid normalized reward item");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record DefinitionActionData(int actionIndex, String actionType, byte[] payload) {
        public DefinitionActionData {
            actionType = requiredText(actionType, "actionType").toUpperCase(java.util.Locale.ROOT);
            payload = copyBytes(payload, "action payload");
            if (actionIndex < 0) throw new IllegalArgumentException("Action index cannot be negative");
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    public record DefinitionRewardData(
            String rewardId, int position, boolean enabled, String displayName, String rarityId,
            int chanceBasisPoints, boolean locked, byte[] settingsPayload,
            List<DefinitionItemData> items, List<DefinitionActionData> actions) {
        public DefinitionRewardData {
            rewardId = requiredText(rewardId, "rewardId");
            displayName = requiredText(displayName, "displayName");
            rarityId = requiredText(rarityId, "rarityId");
            settingsPayload = copyBytes(settingsPayload, "reward settings payload");
            items = List.copyOf(items);
            actions = List.copyOf(actions);
            if (position < 0 || chanceBasisPoints < 0 || chanceBasisPoints > 10_000) {
                throw new IllegalArgumentException("Invalid normalized reward metadata");
            }
        }

        @Override public byte[] settingsPayload() { return settingsPayload.clone(); }
    }

    public record DefinitionKeyTemplateData(
            String templateKind, int sequence, byte[] bytes, String material,
            int serializedSize, String sha256, Instant capturedAt) {
        public DefinitionKeyTemplateData {
            templateKind = requiredText(templateKind, "templateKind").toUpperCase(java.util.Locale.ROOT);
            bytes = copyBytes(bytes, "key template bytes");
            material = requiredText(material, "material");
            sha256 = requiredText(sha256, "sha256");
            capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt");
            if (sequence < 0 || serializedSize != bytes.length) {
                throw new IllegalArgumentException("Invalid normalized key template");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record DefinitionKeyData(
            String keyId, String sourceType, String displayName, String resolutionState,
            boolean archived, byte[] settingsPayload, List<DefinitionKeyTemplateData> templates) {
        public DefinitionKeyData {
            keyId = requiredText(keyId, "keyId");
            sourceType = requiredText(sourceType, "sourceType");
            displayName = requiredText(displayName, "displayName");
            resolutionState = requiredText(resolutionState, "resolutionState");
            settingsPayload = copyBytes(settingsPayload, "key settings payload");
            templates = List.copyOf(templates);
        }

        @Override public byte[] settingsPayload() { return settingsPayload.clone(); }
    }

    /**
     * Canonical key metadata read from SQLite.  This is deliberately separate
     * from {@link DefinitionKeyData}: publication DTOs describe the incoming
     * graph, while these records describe the durable graph used during restart
     * and reload when the optional YAML mirror is unavailable.
     */
    public record StoredKeyTemplate(
            String templateKind, int sequence, byte[] bytes, String material,
            int serializedSize, String sha256, Instant capturedAt) {
        public StoredKeyTemplate {
            templateKind = requiredText(templateKind, "templateKind").toUpperCase(java.util.Locale.ROOT);
            bytes = copyBytes(bytes, "key template bytes");
            material = requiredText(material, "material");
            sha256 = requiredText(sha256, "sha256");
            capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt");
            if (sequence < 0 || serializedSize != bytes.length) {
                throw new IllegalArgumentException("Invalid stored key template");
            }
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record StoredKeyDefinition(
            String keyId, String sourceType, String displayName, String resolutionState,
            boolean archived, long revision, byte[] settingsPayload,
            List<StoredKeyTemplate> templates, Instant createdAt, Instant updatedAt) {
        public StoredKeyDefinition {
            keyId = requiredText(keyId, "keyId");
            sourceType = requiredText(sourceType, "sourceType");
            displayName = requiredText(displayName, "displayName");
            resolutionState = requiredText(resolutionState, "resolutionState");
            settingsPayload = copyBytes(settingsPayload, "key settings payload");
            templates = List.copyOf(templates);
            createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
            if (revision < 0) throw new IllegalArgumentException("Key revision cannot be negative");
        }

        @Override public byte[] settingsPayload() { return settingsPayload.clone(); }
    }

    public record DefinitionBundle(
            String crateId, String lifecycle, int displayOrder, String displayName, String description,
            byte[] iconBytes, byte[] settingsPayload, List<DefinitionRewardData> rewards,
            List<DefinitionKeyData> keys, List<String> acceptedKeyIds, int keyCost,
            Instant createdAt, Instant updatedAt) {
        public DefinitionBundle {
            crateId = requiredText(crateId, "crateId");
            lifecycle = requiredText(lifecycle, "lifecycle").toUpperCase(java.util.Locale.ROOT);
            displayName = requiredText(displayName, "displayName");
            description = java.util.Objects.requireNonNull(description, "description");
            iconBytes = copyBytes(iconBytes, "icon bytes");
            settingsPayload = copyBytes(settingsPayload, "definition payload");
            rewards = List.copyOf(rewards);
            keys = List.copyOf(keys);
            acceptedKeyIds = List.copyOf(acceptedKeyIds);
            createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
            if (displayOrder < 0 || keyCost < 0 || keyCost > 64) {
                throw new IllegalArgumentException("Invalid normalized crate metadata");
            }
        }

        @Override public byte[] iconBytes() { return iconBytes.clone(); }
        @Override public byte[] settingsPayload() { return settingsPayload.clone(); }
    }

    public record StoredDefinition(
            String crateId, long publishedRevision, String lifecycle, byte[] payload, Instant updatedAt) {
        public StoredDefinition {
            crateId = requiredText(crateId, "crateId");
            lifecycle = requiredText(lifecycle, "lifecycle");
            payload = copyBytes(payload, "stored definition payload");
            updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
            if (publishedRevision < 1) throw new IllegalArgumentException("Published revision must be positive");
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    public record PublishedSnapshot(long runtimeRevision, List<StoredDefinition> definitions) {
        public PublishedSnapshot {
            if (runtimeRevision < 0) throw new IllegalArgumentException("Runtime revision cannot be negative");
            definitions = List.copyOf(definitions);
        }
    }

    public record PublishRequest(
            UUID draftId, long expectedDraftRevision, long expectedLeaseToken, UUID actorId,
            String actorName, byte[] frozenPayload, DefinitionBundle definition, Instant createdAt) {
        public PublishRequest {
            draftId = java.util.Objects.requireNonNull(draftId, "draftId");
            actorId = java.util.Objects.requireNonNull(actorId, "actorId");
            actorName = requiredText(actorName, "actorName");
            frozenPayload = copyBytes(frozenPayload, "frozen draft payload");
            definition = java.util.Objects.requireNonNull(definition, "definition");
            createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
            if (expectedDraftRevision < 0 || expectedLeaseToken < 0) {
                throw new IllegalArgumentException("Expected draft revision and lease token cannot be negative");
            }
        }

        @Override public byte[] frozenPayload() { return frozenPayload.clone(); }
    }

    public record PublishResult(StoredDefinition definition, long runtimeRevision) {
        public PublishResult {
            definition = java.util.Objects.requireNonNull(definition, "definition");
            if (runtimeRevision < 1) throw new IllegalArgumentException("Runtime revision must be positive");
        }
    }

    public record DeleteResult(String crateId, long definitionRevision, long runtimeRevision, boolean removed) {
        public DeleteResult {
            crateId = requiredText(crateId, "crateId");
            if (definitionRevision < 0 || runtimeRevision < 0) {
                throw new IllegalArgumentException("Definition revisions cannot be negative");
            }
        }
    }

    /** Durable exact-item/virtual-credit claim awaiting player delivery. */
    public record ClaimEntry(
            UUID claimId, String idempotencyToken, UUID playerId, String sourceType, String sourceId,
            String crateId, String rewardId, byte[] itemBytes, int itemAmount, String itemSha256,
            String virtualKeyId, int virtualKeyAmount, String state, String attemptToken,
            String lastResult, Instant createdAt, Instant updatedAt) {
        public ClaimEntry {
            claimId = java.util.Objects.requireNonNull(claimId, "claimId");
            idempotencyToken = requiredText(idempotencyToken, "idempotencyToken");
            playerId = java.util.Objects.requireNonNull(playerId, "playerId");
            sourceType = requiredText(sourceType, "sourceType");
            sourceId = requiredText(sourceId, "sourceId");
            crateId = optionalText(crateId);
            rewardId = optionalText(rewardId);
            if (itemBytes != null) {
                itemBytes = copyBytes(itemBytes, "claim item bytes");
                if (itemAmount < 1 || itemSha256 == null || !itemSha256.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalArgumentException("Invalid exact-item claim metadata");
                }
                if (virtualKeyId != null || virtualKeyAmount != 0) {
                    throw new IllegalArgumentException("A claim cannot contain both an item and virtual credit");
                }
            } else {
                itemAmount = 0;
                itemSha256 = null;
                virtualKeyId = optionalText(virtualKeyId);
                if (virtualKeyId == null || virtualKeyAmount < 1) {
                    throw new IllegalArgumentException("Virtual-key claim metadata is incomplete");
                }
            }
            state = requiredText(state, "state").toUpperCase(java.util.Locale.ROOT);
            if (!List.of("PENDING", "CLAIMING", "CLAIMED", "REVIEW").contains(state)) {
                throw new IllegalArgumentException("Invalid claim state: " + state);
            }
            attemptToken = optionalText(attemptToken);
            lastResult = lastResult == null ? "" : lastResult;
            createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
        }

        @Override public byte[] itemBytes() { return itemBytes == null ? null : itemBytes.clone(); }
    }

    public record DefinitionCounts(int rewards, int items, int actions, int keyLinks) {}

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
            initializeDefinitionSchema(statement);
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

    /**
     * Creates the normalized 3.0 definition, draft, ledger, and recovery schema.
     * Runtime adoption is intentionally staged: these additive tables can be
     * installed over a healthy 2.0 database before any live definition moves.
     */
    private static void initializeDefinitionSchema(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS crate_definition (
                    crate_id TEXT PRIMARY KEY,
                    lifecycle TEXT NOT NULL CHECK(lifecycle IN ('DRAFT','PUBLISHED','DISABLED','ARCHIVED')),
                    published_revision INTEGER NOT NULL DEFAULT 0 CHECK(published_revision >= 0),
                    display_order INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    icon_bytes BLOB,
                    settings_payload BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS crate_collection_page "
                + "ON crate_definition(lifecycle, display_name COLLATE NOCASE, crate_id)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_definition (
                    crate_id TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    position INTEGER NOT NULL CHECK(position >= 0),
                    enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
                    display_name TEXT NOT NULL,
                    rarity_id TEXT NOT NULL DEFAULT 'common',
                    chance_basis_points INTEGER NOT NULL CHECK(chance_basis_points BETWEEN 0 AND 10000),
                    locked INTEGER NOT NULL DEFAULT 0 CHECK(locked IN (0,1)),
                    settings_payload BLOB NOT NULL,
                    PRIMARY KEY(crate_id, reward_id),
                    UNIQUE(crate_id, position),
                    FOREIGN KEY(crate_id) REFERENCES crate_definition(crate_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS reward_collection_page "
                + "ON reward_definition(crate_id, position, reward_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS reward_rarity_filter "
                + "ON reward_definition(crate_id, rarity_id, enabled, position)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_item (
                    crate_id TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_index INTEGER NOT NULL CHECK(action_index >= 0),
                    item_bytes BLOB NOT NULL,
                    delivery_amount INTEGER NOT NULL CHECK(delivery_amount > 0),
                    material TEXT NOT NULL,
                    serialized_size INTEGER NOT NULL CHECK(serialized_size > 0),
                    sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                    captured_at INTEGER NOT NULL,
                    PRIMARY KEY(crate_id, reward_id, action_index),
                    FOREIGN KEY(crate_id, reward_id) REFERENCES reward_definition(crate_id, reward_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reward_action (
                    crate_id TEXT NOT NULL,
                    reward_id TEXT NOT NULL,
                    action_index INTEGER NOT NULL CHECK(action_index >= 0),
                    action_type TEXT NOT NULL CHECK(action_type IN ('ITEM','COMMAND','EXPERIENCE_POINTS','EXPERIENCE_LEVELS','MONEY')),
                    action_payload BLOB NOT NULL,
                    PRIMARY KEY(crate_id, reward_id, action_index),
                    FOREIGN KEY(crate_id, reward_id) REFERENCES reward_definition(crate_id, reward_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS key_definition_v3 (
                    key_id TEXT PRIMARY KEY,
                    source_type TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    resolution_state TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    settings_payload BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS key_collection_page "
                + "ON key_definition_v3(archived, display_name COLLATE NOCASE, key_id)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS key_template_v3 (
                    key_id TEXT NOT NULL,
                    template_kind TEXT NOT NULL CHECK(template_kind IN ('CURRENT','FALLBACK','LEGACY','LAST_KNOWN_GOOD')),
                    sequence INTEGER NOT NULL DEFAULT 0 CHECK(sequence >= 0),
                    item_bytes BLOB NOT NULL,
                    material TEXT NOT NULL,
                    serialized_size INTEGER NOT NULL CHECK(serialized_size > 0),
                    sha256 TEXT NOT NULL CHECK(length(sha256) = 64),
                    captured_at INTEGER NOT NULL,
                    PRIMARY KEY(key_id, template_kind, sequence),
                    FOREIGN KEY(key_id) REFERENCES key_definition_v3(key_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS active_key_template_fingerprint "
                + "ON key_template_v3(sha256) WHERE template_kind IN ('CURRENT','FALLBACK','LAST_KNOWN_GOOD')");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS crate_key_link (
                    crate_id TEXT NOT NULL,
                    key_id TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    cost INTEGER NOT NULL CHECK(cost > 0),
                    priority INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
                    PRIMARY KEY(crate_id, key_id),
                    FOREIGN KEY(crate_id) REFERENCES crate_definition(crate_id) ON DELETE CASCADE,
                    FOREIGN KEY(key_id) REFERENCES key_definition_v3(key_id) ON DELETE RESTRICT
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS crate_key_reverse_lookup ON crate_key_link(key_id, crate_id)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS effect_profile (
                    profile_id TEXT PRIMARY KEY,
                    immutable_preset INTEGER NOT NULL DEFAULT 0 CHECK(immutable_preset IN (0,1)),
                    display_name TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    profile_payload BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS rarity_profile (
                    rarity_id TEXT PRIMARY KEY,
                    immutable_preset INTEGER NOT NULL DEFAULT 0 CHECK(immutable_preset IN (0,1)),
                    archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)),
                    display_name TEXT NOT NULL,
                    sort_order INTEGER NOT NULL,
                    curve_share INTEGER,
                    profile_payload BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS milestone_definition (
                    crate_id TEXT NOT NULL,
                    milestone_id TEXT NOT NULL,
                    threshold INTEGER NOT NULL CHECK(threshold > 0),
                    repeat_policy TEXT NOT NULL CHECK(repeat_policy IN ('ONCE','REPEATING')),
                    cycle_length INTEGER NOT NULL DEFAULT 0 CHECK(cycle_length >= 0),
                    position INTEGER NOT NULL CHECK(position >= 0),
                    definition_payload BLOB NOT NULL,
                    PRIMARY KEY(crate_id, milestone_id),
                    FOREIGN KEY(crate_id) REFERENCES crate_definition(crate_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS milestone_threshold_lookup "
                + "ON milestone_definition(crate_id, threshold, position)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS milestone_state (
                    player_uuid TEXT NOT NULL,
                    crate_id TEXT NOT NULL,
                    openings INTEGER NOT NULL DEFAULT 0 CHECK(openings >= 0),
                    last_cycle INTEGER NOT NULL DEFAULT 0 CHECK(last_cycle >= 0),
                    earned_payload BLOB NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(player_uuid, crate_id)
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reroll_policy (
                    crate_id TEXT PRIMARY KEY,
                    policy_payload BLOB NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    FOREIGN KEY(crate_id) REFERENCES crate_definition(crate_id) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS reroll_balance (
                    player_uuid TEXT PRIMARY KEY,
                    balance INTEGER NOT NULL DEFAULT 0 CHECK(balance >= 0),
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    updated_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS virtual_key_balance (
                    player_uuid TEXT NOT NULL,
                    key_id TEXT NOT NULL,
                    balance INTEGER NOT NULL DEFAULT 0 CHECK(balance >= 0),
                    revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(player_uuid, key_id)
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ledger_entry (
                    entry_id TEXT PRIMARY KEY,
                    idempotency_token TEXT NOT NULL UNIQUE,
                    player_uuid TEXT NOT NULL,
                    ledger_type TEXT NOT NULL CHECK(ledger_type IN ('VIRTUAL_KEY','REROLL')),
                    key_id TEXT,
                    delta INTEGER NOT NULL,
                    balance_after INTEGER NOT NULL CHECK(balance_after >= 0),
                    source_type TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    actor_uuid TEXT,
                    created_at INTEGER NOT NULL
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS ledger_player_history "
                + "ON ledger_entry(player_uuid, ledger_type, key_id, created_at DESC)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS claim_entry (
                    claim_id TEXT PRIMARY KEY,
                    idempotency_token TEXT NOT NULL UNIQUE,
                    player_uuid TEXT NOT NULL,
                    source_type TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    crate_id TEXT,
                    reward_id TEXT,
                    item_bytes BLOB,
                    item_amount INTEGER,
                    item_sha256 TEXT,
                    virtual_key_id TEXT,
                    virtual_key_amount INTEGER,
                    state TEXT NOT NULL CHECK(state IN ('PENDING','CLAIMING','CLAIMED','REVIEW')),
                    attempt_token TEXT,
                    last_result TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    CHECK((item_bytes IS NOT NULL AND item_amount > 0 AND length(item_sha256) = 64
                           AND virtual_key_id IS NULL AND virtual_key_amount IS NULL)
                       OR (item_bytes IS NULL AND item_amount IS NULL AND item_sha256 IS NULL
                           AND virtual_key_id IS NOT NULL AND virtual_key_amount > 0))
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS claim_player_state_page "
                + "ON claim_entry(player_uuid, state, created_at, claim_id)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS portable_crate_issue (
                    issue_id TEXT PRIMARY KEY,
                    crate_id TEXT NOT NULL,
                    revision_policy TEXT NOT NULL CHECK(revision_policy IN ('LATEST_PUBLISHED','PINNED_REVISION')),
                    pinned_revision INTEGER,
                    issued_to TEXT,
                    issued_by TEXT,
                    signature_version INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('UNUSED','RESERVED','CONSUMED','SUSPENDED','REVIEW')),
                    reservation_token TEXT,
                    issued_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY(crate_id) REFERENCES crate_definition(crate_id) ON DELETE RESTRICT
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS portable_crate_state_lookup "
                + "ON portable_crate_issue(crate_id, state, issued_at)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS plugin_secret (
                    secret_id TEXT PRIMARY KEY,
                    secret_bytes BLOB NOT NULL,
                    algorithm TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    retired_at INTEGER
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS definition_draft (
                    draft_uuid TEXT PRIMARY KEY,
                    target_type TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    owner_uuid TEXT NOT NULL,
                    owner_name TEXT NOT NULL,
                    base_revision INTEGER NOT NULL CHECK(base_revision >= 0),
                    revision INTEGER NOT NULL CHECK(revision >= 0),
                    lease_token INTEGER NOT NULL CHECK(lease_token >= 0),
                    save_state TEXT NOT NULL CHECK(save_state IN ('SAVING','SAVED','FAILED')),
                    validation_status TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(target_type, target_id)
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS draft_owner_page "
                + "ON definition_draft(owner_uuid, updated_at DESC, draft_uuid)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS draft_revision (
                    draft_uuid TEXT NOT NULL,
                    revision INTEGER NOT NULL CHECK(revision >= 0),
                    sequence INTEGER NOT NULL CHECK(sequence >= 0),
                    action_type TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    actor_uuid TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY(draft_uuid, revision),
                    FOREIGN KEY(draft_uuid) REFERENCES definition_draft(draft_uuid) ON DELETE CASCADE
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS draft_revision_undo "
                + "ON draft_revision(draft_uuid, revision DESC)");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS schema_migration (
                    migration_id TEXT PRIMARY KEY,
                    source_version INTEGER NOT NULL,
                    target_version INTEGER NOT NULL,
                    checksum TEXT NOT NULL,
                    backup_path TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('PLANNED','RUNNING','COMPLETED','ROLLED_BACK','FAILED')),
                    report_path TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER
                )
                """);
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS location_world_position "
                + "ON locations(world_uuid, x, z, y)");
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

    /**
     * Loads the canonical key registry and exact templates.  The query is
     * intentionally bounded to the definition tables; YAML mirrors are not
     * consulted here and therefore cannot override a published key on restart.
     */
    public CompletableFuture<List<StoredKeyDefinition>> loadDefinitionKeys() {
        return submitQuery("load canonical key definitions", connection -> {
            var definitions = new ArrayList<StoredKeyDefinition>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT key_id, source_type, display_name, resolution_state, archived, revision,
                           settings_payload, created_at, updated_at
                    FROM key_definition_v3 ORDER BY display_name COLLATE NOCASE, key_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    definitions.add(new StoredKeyDefinition(rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getString(4), rows.getInt(5) != 0, rows.getLong(6),
                            rows.getBytes(7), List.of(), Instant.ofEpochMilli(rows.getLong(8)),
                            Instant.ofEpochMilli(rows.getLong(9))));
                }
            }
            var templates = new LinkedHashMap<String, List<StoredKeyTemplate>>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT key_id, template_kind, sequence, item_bytes, material, serialized_size,
                           sha256, captured_at
                    FROM key_template_v3 ORDER BY key_id, template_kind, sequence
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] bytes = rows.getBytes(4);
                    if (bytes == null || bytes.length == 0) {
                        logger.warning("Ignoring empty canonical key template for " + rows.getString(1));
                        continue;
                    }
                    try {
                        templates.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                                .add(new StoredKeyTemplate(rows.getString(2), rows.getInt(3), bytes,
                                        rows.getString(5), rows.getInt(6), rows.getString(7),
                                        Instant.ofEpochMilli(rows.getLong(8))));
                    } catch (IllegalArgumentException error) {
                        logger.log(Level.WARNING, "Ignoring invalid canonical key template for " + rows.getString(1), error);
                    }
                }
            }
            return definitions.stream().map(definition -> new StoredKeyDefinition(definition.keyId(),
                    definition.sourceType(), definition.displayName(), definition.resolutionState(),
                    definition.archived(), definition.revision(), definition.settingsPayload(),
                    templates.getOrDefault(definition.keyId(), List.of()), definition.createdAt(),
                    definition.updatedAt())).toList();
        });
    }

    public int pendingJournalCount() throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM opening_journal WHERE stage NOT IN ('COMPLETED', 'CANCELLED')");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    public int schemaVersion() throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM schema_meta WHERE key = 'schema_version'");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing schema_version metadata");
            return Integer.parseInt(rows.getString(1));
        }
    }

    public boolean schemaObjectExists(String type, String name) throws SQLException {
        if (!List.of("table", "index").contains(type)) throw new IllegalArgumentException("Unsupported schema object type");
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
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

    /**
     * Inserts an exact-item claim idempotently. Reusing the same token returns
     * the original row instead of creating a duplicate delivery.
     */
    public CompletableFuture<ClaimEntry> createItemClaim(
            UUID playerId, String sourceType, String sourceId, String crateId, String rewardId,
            String idempotencyToken, byte[] itemBytes, int itemAmount, String itemSha256, Instant createdAt) {
        UUID owner = java.util.Objects.requireNonNull(playerId, "playerId");
        String source = requiredText(sourceType, "sourceType");
        String origin = requiredText(sourceId, "sourceId");
        String token = requiredText(idempotencyToken, "idempotencyToken");
        byte[] bytes = copyBytes(itemBytes, "claim item bytes");
        String fingerprint = requiredText(itemSha256, "itemSha256");
        if (itemAmount < 1 || !fingerprint.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid exact-item claim metadata");
        }
        if (!fingerprint.equalsIgnoreCase(sha256(bytes))) {
            throw new IllegalArgumentException("Exact-item claim fingerprint does not match its payload");
        }
        Instant created = java.util.Objects.requireNonNull(createdAt, "createdAt");
        return submitTransactionQuery("create item claim", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO claim_entry(claim_id, idempotency_token, player_uuid, source_type, source_id,
                        crate_id, reward_id, item_bytes, item_amount, item_sha256, state, last_result,
                        created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', '', ?, ?)
                    ON CONFLICT(idempotency_token) DO NOTHING
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, token);
                statement.setString(3, owner.toString());
                statement.setString(4, source);
                statement.setString(5, origin);
                nullableText(statement, 6, crateId);
                nullableText(statement, 7, rewardId);
                statement.setBytes(8, bytes);
                statement.setInt(9, itemAmount);
                statement.setString(10, fingerprint.toLowerCase(java.util.Locale.ROOT));
                statement.setLong(11, created.toEpochMilli());
                statement.setLong(12, created.toEpochMilli());
                statement.executeUpdate();
            }
            return loadClaimByToken(connection, token).orElseThrow();
        });
    }

    public CompletableFuture<List<ClaimEntry>> loadClaims(UUID playerId, int limit, int offset) {
        UUID owner = java.util.Objects.requireNonNull(playerId, "playerId");
        return submitQuery("load claims", connection -> {
            var result = new ArrayList<ClaimEntry>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT claim_id, idempotency_token, player_uuid, source_type, source_id, crate_id, reward_id,
                           item_bytes, item_amount, item_sha256, virtual_key_id, virtual_key_amount, state,
                           attempt_token, last_result, created_at, updated_at
                    FROM claim_entry WHERE player_uuid = ? AND state <> 'CLAIMED'
                    ORDER BY created_at, claim_id LIMIT ? OFFSET ?
                    """)) {
                statement.setString(1, owner.toString());
                statement.setInt(2, Math.max(1, Math.min(limit, 100)));
                statement.setInt(3, Math.max(0, offset));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(claimEntry(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    public CompletableFuture<Integer> pendingClaimCount(UUID playerId) {
        UUID owner = java.util.Objects.requireNonNull(playerId, "playerId");
        return submitQuery("count pending claims", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM claim_entry WHERE player_uuid = ? AND state <> 'CLAIMED'")) {
                statement.setString(1, owner.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }
        });
    }

    /** Reserves a pending claim, or returns the same row for an idempotent retry token. */
    public CompletableFuture<Optional<ClaimEntry>> reserveClaim(UUID playerId, UUID claimId, String attemptToken) {
        UUID owner = java.util.Objects.requireNonNull(playerId, "playerId");
        UUID id = java.util.Objects.requireNonNull(claimId, "claimId");
        String token = requiredText(attemptToken, "attemptToken");
        return submitTransactionQuery("reserve claim", connection -> {
            ClaimEntry current = loadClaim(connection, id).orElse(null);
            if (current == null || !current.playerId().equals(owner)) return Optional.empty();
            if (current.state().equals("CLAIMING") && token.equals(current.attemptToken())) {
                return Optional.of(current);
            }
            if (!current.state().equals("PENDING")) return Optional.empty();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE claim_entry SET state = 'CLAIMING', attempt_token = ?, last_result = '', updated_at = ?
                    WHERE claim_id = ? AND player_uuid = ? AND state = 'PENDING'
                    """)) {
                statement.setString(1, token);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, id.toString());
                statement.setString(4, owner.toString());
                if (statement.executeUpdate() != 1) return Optional.empty();
            }
            return loadClaim(connection, id);
        });
    }

    public CompletableFuture<Optional<ClaimEntry>> completeClaim(UUID claimId, String attemptToken) {
        UUID id = java.util.Objects.requireNonNull(claimId, "claimId");
        String token = requiredText(attemptToken, "attemptToken");
        return submitTransactionQuery("complete claim", connection -> {
            ClaimEntry current = loadClaim(connection, id).orElse(null);
            if (current == null) return Optional.empty();
            if (current.state().equals("CLAIMED")) return Optional.of(current);
            if (!current.state().equals("CLAIMING") || !token.equals(current.attemptToken())) return Optional.empty();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE claim_entry SET state = 'CLAIMED', attempt_token = NULL, last_result = '', updated_at = ?
                    WHERE claim_id = ? AND state = 'CLAIMING' AND attempt_token = ?
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, id.toString());
                statement.setString(3, token);
                if (statement.executeUpdate() != 1) return Optional.empty();
            }
            return loadClaim(connection, id);
        });
    }

    public CompletableFuture<Boolean> releaseClaim(UUID claimId, String attemptToken, String reason) {
        UUID id = java.util.Objects.requireNonNull(claimId, "claimId");
        String token = requiredText(attemptToken, "attemptToken");
        String detail = reason == null ? "" : reason;
        return submitTransactionQuery("release claim reservation", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE claim_entry SET state = 'PENDING', attempt_token = NULL, last_result = ?, updated_at = ?
                    WHERE claim_id = ? AND state = 'CLAIMING' AND attempt_token = ?
                    """)) {
                statement.setString(1, detail);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, id.toString());
                statement.setString(4, token);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> markClaimReview(UUID claimId, String attemptToken, String reason) {
        UUID id = java.util.Objects.requireNonNull(claimId, "claimId");
        String token = requiredText(attemptToken, "attemptToken");
        String detail = reason == null ? "" : reason;
        return submitTransactionQuery("mark claim for review", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE claim_entry SET state = 'REVIEW', attempt_token = NULL, last_result = ?, updated_at = ?
                    WHERE claim_id = ? AND state = 'CLAIMING' AND attempt_token = ?
                    """)) {
                statement.setString(1, detail);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, id.toString());
                statement.setString(4, token);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** Abandoned claims are never retried automatically after a restart. */
    public CompletableFuture<Integer> recoverClaimingClaims() {
        return submitTransactionQuery("recover claiming claims", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE claim_entry SET state = 'REVIEW', attempt_token = NULL,
                        last_result = 'Claim attempt was interrupted; manual review required', updated_at = ?
                    WHERE state = 'CLAIMING'
                    """)) {
                statement.setLong(1, System.currentTimeMillis());
                return statement.executeUpdate();
            }
        });
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

    public CompletableFuture<DefinitionDraft> createOrResumeDefinitionDraft(
            String targetType, String targetId, UUID ownerId, String ownerName,
            long baseRevision, byte[] initialPayload) {
        String normalizedType = requiredText(targetType, "targetType").toUpperCase(java.util.Locale.ROOT);
        String normalizedId = requiredText(targetId, "targetId");
        String normalizedOwner = requiredText(ownerName, "ownerName");
        UUID owner = java.util.Objects.requireNonNull(ownerId, "ownerId");
        byte[] payload = validDraftPayload(initialPayload);
        if (baseRevision < 0) throw new IllegalArgumentException("Base revision cannot be negative");

        return submitTransactionQuery("create or resume definition draft", connection -> {
            DefinitionDraft existing = loadDefinitionDraft(connection, normalizedType, normalizedId).orElse(null);
            if (existing != null) return existing;

            UUID draftId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO definition_draft(draft_uuid, target_type, target_id, owner_uuid, owner_name,
                        base_revision, revision, lease_token, save_state, validation_status, payload,
                        created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, 0, 1, 'SAVED', 'UNVALIDATED', ?, ?, ?)
                    """)) {
                statement.setString(1, draftId.toString());
                statement.setString(2, normalizedType);
                statement.setString(3, normalizedId);
                statement.setString(4, owner.toString());
                statement.setString(5, normalizedOwner);
                statement.setLong(6, baseRevision);
                statement.setBytes(7, payload);
                statement.setLong(8, now);
                statement.setLong(9, now);
                statement.executeUpdate();
            }
            insertDraftRevision(connection, draftId, 0, 0, "CREATE", "Created durable draft", payload, owner, now);
            return loadDefinitionDraft(connection, draftId).orElseThrow();
        });
    }

    public CompletableFuture<Optional<DefinitionDraft>> loadDefinitionDraft(String targetType, String targetId) {
        String normalizedType = requiredText(targetType, "targetType").toUpperCase(java.util.Locale.ROOT);
        String normalizedId = requiredText(targetId, "targetId");
        return submitQuery("load definition draft", connection ->
                loadDefinitionDraft(connection, normalizedType, normalizedId));
    }

    /**
     * Loads the durable draft metadata/payloads needed to rebuild the administrator
     * registry after a restart. Drafts are deliberately queried separately from the
     * published snapshot so they can never leak into player-facing runtime state.
     */
    public CompletableFuture<List<DefinitionDraft>> loadDefinitionDrafts() {
        return submitQuery("load definition drafts", connection -> {
            var drafts = new ArrayList<DefinitionDraft>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT draft_uuid, target_type, target_id, owner_uuid, owner_name, base_revision, revision,
                           lease_token, save_state, validation_status, payload, created_at, updated_at
                    FROM definition_draft WHERE target_type = 'CRATE'
                    ORDER BY updated_at DESC, draft_uuid
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) drafts.add(definitionDraft(rows));
            }
            return List.copyOf(drafts);
        });
    }

    public CompletableFuture<DefinitionDraft> saveDefinitionDraft(UUID draftId, DraftMutation mutation) {
        UUID id = java.util.Objects.requireNonNull(draftId, "draftId");
        DraftMutation change = java.util.Objects.requireNonNull(mutation, "mutation");
        byte[] payload = validDraftPayload(change.payload());
        return submitTransactionQuery("save definition draft revision", connection -> {
            DefinitionDraft current = loadDefinitionDraft(connection, id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown definition draft"));
            requireWritableDraft(current, change.actorId(), change.leaseToken(), change.expectedRevision());
            long nextRevision = Math.addExact(current.revision(), 1);
            long savedAt = change.createdAt().toEpochMilli();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE definition_draft SET revision = ?, save_state = 'SAVED', validation_status = ?,
                        payload = ?, updated_at = ?
                    WHERE draft_uuid = ? AND revision = ? AND lease_token = ? AND owner_uuid = ?
                    """)) {
                statement.setLong(1, nextRevision);
                statement.setString(2, change.validationStatus());
                statement.setBytes(3, payload);
                statement.setLong(4, savedAt);
                statement.setString(5, id.toString());
                statement.setLong(6, change.expectedRevision());
                statement.setLong(7, change.leaseToken());
                statement.setString(8, change.actorId().toString());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Draft changed before this save completed");
            }
            insertDraftRevision(connection, id, nextRevision, nextRevision, change.actionType(), change.summary(),
                    payload, change.actorId(), savedAt);
            trimDraftRevisions(connection, id, 20);
            return loadDefinitionDraft(connection, id).orElseThrow();
        });
    }

    public CompletableFuture<DefinitionDraft> takeoverDefinitionDraft(
            UUID draftId, long expectedLeaseToken, UUID newOwnerId, String newOwnerName) {
        UUID id = java.util.Objects.requireNonNull(draftId, "draftId");
        UUID owner = java.util.Objects.requireNonNull(newOwnerId, "newOwnerId");
        String name = requiredText(newOwnerName, "newOwnerName");
        if (expectedLeaseToken < 0) throw new IllegalArgumentException("Expected lease token cannot be negative");
        return submitTransactionQuery("take over definition draft", connection -> {
            DefinitionDraft current = loadDefinitionDraft(connection, id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown definition draft"));
            if (current.leaseToken() != expectedLeaseToken) {
                throw new IllegalStateException("Draft lease changed before takeover confirmation");
            }
            long nextLease = Math.addExact(current.leaseToken(), 1);
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE definition_draft SET owner_uuid = ?, owner_name = ?, lease_token = ?, updated_at = ?
                    WHERE draft_uuid = ? AND lease_token = ?
                    """)) {
                statement.setString(1, owner.toString());
                statement.setString(2, name);
                statement.setLong(3, nextLease);
                statement.setLong(4, now);
                statement.setString(5, id.toString());
                statement.setLong(6, expectedLeaseToken);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Draft lease changed before takeover");
            }
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO audit_log(actor_uuid, actor_name, action, target_type, target_id, summary, created_at)
                    VALUES(?, ?, 'TAKEOVER', 'DRAFT', ?, ?, ?)
                    """)) {
                audit.setString(1, owner.toString());
                audit.setString(2, name);
                audit.setString(3, id.toString());
                audit.setString(4, "Took writable lease from " + current.ownerName());
                audit.setLong(5, now);
                audit.executeUpdate();
            }
            return loadDefinitionDraft(connection, id).orElseThrow();
        });
    }

    public CompletableFuture<DefinitionDraft> undoDefinitionDraft(
            UUID draftId, long expectedRevision, long leaseToken, UUID actorId, Instant createdAt) {
        UUID id = java.util.Objects.requireNonNull(draftId, "draftId");
        UUID actor = java.util.Objects.requireNonNull(actorId, "actorId");
        Instant now = java.util.Objects.requireNonNull(createdAt, "createdAt");
        return submitTransactionQuery("undo definition draft", connection -> {
            DefinitionDraft current = loadDefinitionDraft(connection, id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown definition draft"));
            requireWritableDraft(current, actor, leaseToken, expectedRevision);
            byte[] previous;
            long sourceRevision;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT revision, payload FROM draft_revision
                    WHERE draft_uuid = ? AND revision < ? ORDER BY revision DESC LIMIT 1
                    """)) {
                statement.setString(1, id.toString());
                statement.setLong(2, expectedRevision);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("This draft has no earlier revision to undo");
                    sourceRevision = rows.getLong(1);
                    previous = rows.getBytes(2);
                }
            }
            long nextRevision = Math.addExact(current.revision(), 1);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE definition_draft SET revision = ?, save_state = 'SAVED', validation_status = 'UNVALIDATED',
                        payload = ?, updated_at = ? WHERE draft_uuid = ? AND revision = ? AND lease_token = ?
                    """)) {
                statement.setLong(1, nextRevision);
                statement.setBytes(2, previous);
                statement.setLong(3, now.toEpochMilli());
                statement.setString(4, id.toString());
                statement.setLong(5, expectedRevision);
                statement.setLong(6, leaseToken);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Draft changed before undo completed");
            }
            insertDraftRevision(connection, id, nextRevision, nextRevision, "UNDO",
                    "Restored revision " + sourceRevision, previous, actor, now.toEpochMilli());
            trimDraftRevisions(connection, id, 20);
            return loadDefinitionDraft(connection, id).orElseThrow();
        });
    }

    public CompletableFuture<Integer> draftRevisionCount(UUID draftId) {
        UUID id = java.util.Objects.requireNonNull(draftId, "draftId");
        return submitQuery("count draft revisions", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM draft_revision WHERE draft_uuid = ?")) {
                statement.setString(1, id.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<Void> discardDefinitionDraft(
            UUID draftId, long expectedRevision, long leaseToken, UUID actorId, String actorName) {
        UUID id = java.util.Objects.requireNonNull(draftId, "draftId");
        UUID actor = java.util.Objects.requireNonNull(actorId, "actorId");
        String name = requiredText(actorName, "actorName");
        return submitTransaction("discard definition draft", connection -> {
            DefinitionDraft current = loadDefinitionDraft(connection, id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown definition draft"));
            requireWritableDraft(current, actor, leaseToken, expectedRevision);
            try (PreparedStatement revisions = connection.prepareStatement(
                    "DELETE FROM draft_revision WHERE draft_uuid = ?")) {
                revisions.setString(1, id.toString());
                revisions.executeUpdate();
            }
            try (PreparedStatement draft = connection.prepareStatement(
                    "DELETE FROM definition_draft WHERE draft_uuid = ? AND revision = ? AND lease_token = ?")) {
                draft.setString(1, id.toString());
                draft.setLong(2, expectedRevision);
                draft.setLong(3, leaseToken);
                if (draft.executeUpdate() != 1) throw new IllegalStateException("Draft changed before discard completed");
            }
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO audit_log(actor_uuid, actor_name, action, target_type, target_id, summary, created_at)
                    VALUES(?, ?, 'DISCARD', 'DRAFT', ?, ?, ?)
                    """)) {
                audit.setString(1, actor.toString());
                audit.setString(2, name);
                audit.setString(3, id.toString());
                audit.setString(4, "Discarded " + current.targetType() + " draft " + current.targetId());
                audit.setLong(5, System.currentTimeMillis());
                audit.executeUpdate();
            }
        });
    }

    /** Imports the validated legacy published graph once; existing canonical data always wins. */
    public CompletableFuture<PublishedSnapshot> bootstrapPublishedDefinitions(List<DefinitionBundle> definitions) {
        List<DefinitionBundle> bundles = List.copyOf(definitions);
        return submitTransactionQuery("bootstrap published definitions", connection -> {
            if (definitionCount(connection) == 0 && !bundles.isEmpty()) {
                long now = System.currentTimeMillis();
                for (DefinitionBundle bundle : bundles) {
                    requirePublished(bundle);
                    writeDefinition(connection, bundle, 1, now);
                    insertAudit(connection, null, "SYSTEM", "MIGRATE", "CRATE", bundle.crateId(),
                            "Imported the validated 2.0 published definition as revision 1", now);
                }
                setRuntimeRevision(connection, Math.max(1, runtimeRevision(connection)));
            }
            return publishedSnapshot(connection);
        });
    }

    public CompletableFuture<PublishedSnapshot> loadPublishedDefinitions() {
        return submitQuery("load published definitions", DatabaseService::publishedSnapshot);
    }

    /**
     * Compares the durable draft lease/revision and base publication revision,
     * then replaces the complete normalized crate graph in one transaction.
     */
    public CompletableFuture<PublishResult> publishDefinitionDraft(PublishRequest request) {
        PublishRequest publication = java.util.Objects.requireNonNull(request, "request");
        requirePublished(publication.definition());
        return submitTransactionQuery("publish definition draft", connection -> {
            DefinitionDraft draft = loadDefinitionDraft(connection, publication.draftId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown definition draft"));
            requireWritableDraft(draft, publication.actorId(), publication.expectedLeaseToken(),
                    publication.expectedDraftRevision());
            if (draft.saveState() != DraftSaveState.SAVED) {
                throw new IllegalStateException("The latest draft revision is not durable");
            }
            if (!draft.targetType().equals("CRATE")
                    || !draft.targetId().equals(publication.definition().crateId())) {
                throw new IllegalStateException("Draft target does not match the definition being published");
            }
            if (!java.util.Arrays.equals(draft.payload(), publication.frozenPayload())) {
                throw new IllegalStateException("The publication payload is not the frozen durable draft");
            }

            long currentRevision = publishedRevision(connection, draft.targetId());
            if (currentRevision != draft.baseRevision()) {
                throw new IllegalStateException("The published crate changed after this draft was opened");
            }
            long nextRevision = Math.addExact(currentRevision, 1);
            long createdAt = publication.createdAt().toEpochMilli();
            writeDefinition(connection, publication.definition(), nextRevision, createdAt);
            long nextRuntimeRevision = Math.addExact(runtimeRevision(connection), 1);
            setRuntimeRevision(connection, nextRuntimeRevision);
            insertAudit(connection, publication.actorId(), publication.actorName(), "PUBLISH", "CRATE",
                    draft.targetId(), "Published draft revision " + draft.revision()
                            + " as definition revision " + nextRevision, createdAt);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM definition_draft WHERE draft_uuid = ? AND revision = ? AND lease_token = ?")) {
                statement.setString(1, draft.draftId().toString());
                statement.setLong(2, draft.revision());
                statement.setLong(3, draft.leaseToken());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Draft changed before publication completed");
                }
            }
            StoredDefinition stored = new StoredDefinition(draft.targetId(), nextRevision,
                    publication.definition().lifecycle(), publication.definition().settingsPayload(),
                    publication.createdAt());
            return new PublishResult(stored, nextRuntimeRevision);
        });
    }

    /** Deletes an archived canonical definition in one transaction, including its normalized children and audit row. */
    public CompletableFuture<DeleteResult> deleteDefinition(String crateId, UUID actorId, String actorName) {
        String id = requiredText(crateId, "crateId");
        UUID actor = java.util.Objects.requireNonNull(actorId, "actorId");
        String name = requiredText(actorName, "actorName");
        return submitTransactionQuery("delete canonical definition", connection -> {
            String lifecycle;
            long definitionRevision;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT lifecycle, published_revision FROM crate_definition WHERE crate_id = ?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return new DeleteResult(id, 0, runtimeRevision(connection), false);
                    lifecycle = rows.getString(1);
                    definitionRevision = rows.getLong(2);
                }
            }
            if (!List.of("DRAFT", "ARCHIVED").contains(lifecycle)) {
                throw new IllegalStateException("Only an archived or unpublished definition can be deleted");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM definition_draft WHERE target_type = 'CRATE' AND target_id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM crate_definition WHERE crate_id = ?")) {
                statement.setString(1, id);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Definition changed before deletion completed");
            }
            long nextRuntimeRevision = Math.addExact(runtimeRevision(connection), 1);
            setRuntimeRevision(connection, nextRuntimeRevision);
            insertAudit(connection, actor, name, "DELETE", "CRATE", id,
                    "Deleted archived canonical definition", System.currentTimeMillis());
            return new DeleteResult(id, definitionRevision, nextRuntimeRevision, true);
        });
    }

    public CompletableFuture<DefinitionCounts> definitionCounts(String crateId) {
        String id = requiredText(crateId, "crateId");
        return submitQuery("count normalized definition rows", connection -> new DefinitionCounts(
                countForCrate(connection, "reward_definition", id),
                countForCrate(connection, "reward_item", id),
                countForCrate(connection, "reward_action", id),
                countForCrate(connection, "crate_key_link", id)));
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
                    "PlexonCrates 3.0 backup\nCreated: " + Instant.now() + "\nSchema: " + SCHEMA_VERSION + "\n",
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

    private <T> CompletableFuture<T> submitTransactionQuery(String description, SqlQuery<T> query) {
        var future = new CompletableFuture<T>();
        if (closed.get()) {
            future.completeExceptionally(new IllegalStateException("Database is closed"));
            return future;
        }
        try {
            writer.execute(() -> {
                try (Connection connection = connect()) {
                    connection.setAutoCommit(false);
                    try {
                        T result = query.run(connection);
                        connection.commit();
                        future.complete(result);
                    } catch (Exception error) {
                        connection.rollback();
                        future.completeExceptionally(error);
                        logger.log(Level.SEVERE, "Could not " + description, error);
                    } finally {
                        connection.setAutoCommit(true);
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

    private static PublishedSnapshot publishedSnapshot(Connection connection) throws SQLException {
        var definitions = new ArrayList<StoredDefinition>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT crate_id, published_revision, lifecycle, settings_payload, updated_at
                FROM crate_definition WHERE lifecycle IN ('PUBLISHED', 'DISABLED', 'ARCHIVED')
                ORDER BY display_order, crate_id
                """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                definitions.add(new StoredDefinition(rows.getString(1), rows.getLong(2), rows.getString(3),
                        rows.getBytes(4), Instant.ofEpochMilli(rows.getLong(5))));
            }
        }
        return new PublishedSnapshot(runtimeRevision(connection), definitions);
    }

    private static void writeDefinition(
            Connection connection, DefinitionBundle bundle, long revision, long publishedAt) throws SQLException {
        for (DefinitionKeyData key : bundle.keys()) upsertDefinitionKey(connection, key, publishedAt);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crate_definition(crate_id, lifecycle, published_revision, display_order, display_name,
                    description, icon_bytes, settings_payload, created_at, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(crate_id) DO UPDATE SET lifecycle=excluded.lifecycle,
                    published_revision=excluded.published_revision, display_order=excluded.display_order,
                    display_name=excluded.display_name, description=excluded.description,
                    icon_bytes=excluded.icon_bytes, settings_payload=excluded.settings_payload,
                    updated_at=excluded.updated_at
                """)) {
            statement.setString(1, bundle.crateId());
            statement.setString(2, bundle.lifecycle());
            statement.setLong(3, revision);
            statement.setInt(4, bundle.displayOrder());
            statement.setString(5, bundle.displayName());
            statement.setString(6, bundle.description());
            statement.setBytes(7, bundle.iconBytes());
            statement.setBytes(8, bundle.settingsPayload());
            statement.setLong(9, bundle.createdAt().toEpochMilli());
            statement.setLong(10, publishedAt);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM reward_definition WHERE crate_id = ?")) {
            statement.setString(1, bundle.crateId());
            statement.executeUpdate();
        }
        for (DefinitionRewardData reward : bundle.rewards()) {
            insertDefinitionReward(connection, bundle.crateId(), reward);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM crate_key_link WHERE crate_id = ?")) {
            statement.setString(1, bundle.crateId());
            statement.executeUpdate();
        }
        if (bundle.keyCost() > 0) {
            Map<String, String> sources = new LinkedHashMap<>();
            bundle.keys().forEach(key -> sources.put(key.keyId(), key.sourceType()));
            int priority = 0;
            for (String keyId : new java.util.LinkedHashSet<>(bundle.acceptedKeyIds())) {
                String source = sources.get(keyId);
                if (source == null) throw new SQLException("Published crate references an unstored key: " + keyId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO crate_key_link(crate_id, key_id, source_type, cost, priority, enabled)
                        VALUES(?, ?, ?, ?, ?, 1)
                        """)) {
                    statement.setString(1, bundle.crateId());
                    statement.setString(2, keyId);
                    statement.setString(3, source);
                    statement.setInt(4, bundle.keyCost());
                    statement.setInt(5, priority++);
                    statement.executeUpdate();
                }
            }
        }
    }

    private static void insertDefinitionReward(
            Connection connection, String crateId, DefinitionRewardData reward) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_definition(crate_id, reward_id, position, enabled, display_name, rarity_id,
                    chance_basis_points, locked, settings_payload) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, crateId);
            statement.setString(2, reward.rewardId());
            statement.setInt(3, reward.position());
            statement.setInt(4, reward.enabled() ? 1 : 0);
            statement.setString(5, reward.displayName());
            statement.setString(6, reward.rarityId());
            statement.setInt(7, reward.chanceBasisPoints());
            statement.setInt(8, reward.locked() ? 1 : 0);
            statement.setBytes(9, reward.settingsPayload());
            statement.executeUpdate();
        }
        for (DefinitionItemData item : reward.items()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reward_item(crate_id, reward_id, action_index, item_bytes, delivery_amount,
                        material, serialized_size, sha256, captured_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, crateId);
                statement.setString(2, reward.rewardId());
                statement.setInt(3, item.actionIndex());
                statement.setBytes(4, item.bytes());
                statement.setInt(5, item.deliveryAmount());
                statement.setString(6, item.material());
                statement.setInt(7, item.serializedSize());
                statement.setString(8, item.sha256());
                statement.setLong(9, item.capturedAt().toEpochMilli());
                statement.executeUpdate();
            }
        }
        for (DefinitionActionData action : reward.actions()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reward_action(crate_id, reward_id, action_index, action_type, action_payload)
                    VALUES(?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, crateId);
                statement.setString(2, reward.rewardId());
                statement.setInt(3, action.actionIndex());
                statement.setString(4, action.actionType());
                statement.setBytes(5, action.payload());
                statement.executeUpdate();
            }
        }
    }

    private static void upsertDefinitionKey(
            Connection connection, DefinitionKeyData key, long updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO key_definition_v3(key_id, source_type, display_name, resolution_state, archived,
                    revision, settings_payload, created_at, updated_at) VALUES(?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(key_id) DO UPDATE SET source_type=excluded.source_type,
                    display_name=excluded.display_name, resolution_state=excluded.resolution_state,
                    archived=excluded.archived, revision=key_definition_v3.revision + 1,
                    settings_payload=excluded.settings_payload, updated_at=excluded.updated_at
                """)) {
            statement.setString(1, key.keyId());
            statement.setString(2, key.sourceType());
            statement.setString(3, key.displayName());
            statement.setString(4, key.resolutionState());
            statement.setInt(5, key.archived() ? 1 : 0);
            statement.setBytes(6, key.settingsPayload());
            statement.setLong(7, updatedAt);
            statement.setLong(8, updatedAt);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM key_template_v3 WHERE key_id = ?")) {
            statement.setString(1, key.keyId());
            statement.executeUpdate();
        }
        for (DefinitionKeyTemplateData template : key.templates()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO key_template_v3(key_id, template_kind, sequence, item_bytes, material,
                        serialized_size, sha256, captured_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, key.keyId());
                statement.setString(2, template.templateKind());
                statement.setInt(3, template.sequence());
                statement.setBytes(4, template.bytes());
                statement.setString(5, template.material());
                statement.setInt(6, template.serializedSize());
                statement.setString(7, template.sha256());
                statement.setLong(8, template.capturedAt().toEpochMilli());
                statement.executeUpdate();
            }
        }
    }

    private static long publishedRevision(Connection connection, String crateId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT published_revision FROM crate_definition WHERE crate_id = ?")) {
            statement.setString(1, crateId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    private static int definitionCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM crate_definition"); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static long runtimeRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM schema_meta WHERE key = 'definition_runtime_revision'");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) return 0L;
            try {
                return Long.parseLong(rows.getString(1));
            } catch (NumberFormatException error) {
                throw new SQLException("Invalid definition runtime revision", error);
            }
        }
    }

    private static void setRuntimeRevision(Connection connection, long revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO schema_meta(key, value) VALUES('definition_runtime_revision', ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """)) {
            statement.setString(1, Long.toString(revision));
            statement.executeUpdate();
        }
    }

    private static int countForCrate(Connection connection, String table, String crateId) throws SQLException {
        String checked = switch (table) {
            case "reward_definition", "reward_item", "reward_action", "crate_key_link" -> table;
            default -> throw new IllegalArgumentException("Unsupported definition table");
        };
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + checked + " WHERE crate_id = ?")) {
            statement.setString(1, crateId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static void insertAudit(Connection connection, UUID actorId, String actorName, String action,
                                    String targetType, String targetId, String summary, long createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_log(actor_uuid, actor_name, action, target_type, target_id, summary, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                """)) {
            nullableUuid(statement, 1, actorId);
            statement.setString(2, actorName);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, summary);
            statement.setLong(7, createdAt);
            statement.executeUpdate();
        }
    }

    private static void requirePublished(DefinitionBundle bundle) {
        if (!List.of("PUBLISHED", "DISABLED", "ARCHIVED").contains(bundle.lifecycle())) {
            throw new IllegalArgumentException("Only published, disabled, or archived definitions can enter the canonical store");
        }
        if (!bundle.lifecycle().equals("PUBLISHED")) return;
        int total = bundle.rewards().stream().filter(DefinitionRewardData::enabled)
                .mapToInt(DefinitionRewardData::chanceBasisPoints).sum();
        if (total != 10_000) {
            throw new IllegalArgumentException("Published reward chances must total exactly 10,000 basis points");
        }
        if (bundle.rewards().stream().noneMatch(reward -> reward.enabled() && !reward.actions().isEmpty())) {
            throw new IllegalArgumentException("Published definition needs an enabled deliverable reward");
        }
    }

    private static Optional<DefinitionDraft> loadDefinitionDraft(
            Connection connection, String targetType, String targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT draft_uuid, target_type, target_id, owner_uuid, owner_name, base_revision, revision,
                       lease_token, save_state, validation_status, payload, created_at, updated_at
                FROM definition_draft WHERE target_type = ? AND target_id = ?
                """)) {
            statement.setString(1, targetType);
            statement.setString(2, targetId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(definitionDraft(rows)) : Optional.empty();
            }
        }
    }

    private static Optional<DefinitionDraft> loadDefinitionDraft(Connection connection, UUID draftId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT draft_uuid, target_type, target_id, owner_uuid, owner_name, base_revision, revision,
                       lease_token, save_state, validation_status, payload, created_at, updated_at
                FROM definition_draft WHERE draft_uuid = ?
                """)) {
            statement.setString(1, draftId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(definitionDraft(rows)) : Optional.empty();
            }
        }
    }

    private static DefinitionDraft definitionDraft(ResultSet rows) throws SQLException {
        return new DefinitionDraft(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getString(3),
                UUID.fromString(rows.getString(4)), rows.getString(5), rows.getLong(6), rows.getLong(7),
                rows.getLong(8), DraftSaveState.valueOf(rows.getString(9)), rows.getString(10), rows.getBytes(11),
                Instant.ofEpochMilli(rows.getLong(12)), Instant.ofEpochMilli(rows.getLong(13)));
    }

    private static void insertDraftRevision(Connection connection, UUID draftId, long revision, long sequence,
                                            String actionType, String summary, byte[] payload,
                                            UUID actorId, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO draft_revision(draft_uuid, revision, sequence, action_type, summary, payload,
                    actor_uuid, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, draftId.toString());
            statement.setLong(2, revision);
            statement.setLong(3, sequence);
            statement.setString(4, actionType);
            statement.setString(5, summary);
            statement.setBytes(6, payload);
            statement.setString(7, actorId.toString());
            statement.setLong(8, createdAt);
            statement.executeUpdate();
        }
    }

    private static void trimDraftRevisions(Connection connection, UUID draftId, int retained) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM draft_revision WHERE draft_uuid = ? AND revision NOT IN (
                    SELECT revision FROM draft_revision WHERE draft_uuid = ? ORDER BY revision DESC LIMIT ?
                )
                """)) {
            statement.setString(1, draftId.toString());
            statement.setString(2, draftId.toString());
            statement.setInt(3, retained);
            statement.executeUpdate();
        }
    }

    private static void requireWritableDraft(
            DefinitionDraft current, UUID actorId, long leaseToken, long expectedRevision) {
        if (!current.ownerId().equals(actorId)) throw new IllegalStateException("Draft is read-only for this administrator");
        if (current.leaseToken() != leaseToken) throw new IllegalStateException("Draft lease is stale; reopen the editor");
        if (current.revision() != expectedRevision) throw new IllegalStateException("Draft revision is stale; reopen the editor");
    }

    private static byte[] validDraftPayload(byte[] input) {
        byte[] payload = java.util.Objects.requireNonNull(input, "payload").clone();
        if (payload.length == 0 || payload.length > 16_000_000) {
            throw new IllegalArgumentException("Draft payload is empty or exceeds 16,000,000 bytes");
        }
        return payload;
    }

    private static byte[] copyBytes(byte[] input, String name) {
        byte[] payload = java.util.Objects.requireNonNull(input, name).clone();
        if (payload.length == 0 || payload.length > 16_000_000) {
            throw new IllegalArgumentException(name + " is empty or exceeds 16,000,000 bytes");
        }
        return payload;
    }

    private static String requiredText(String input, String name) {
        String value = java.util.Objects.requireNonNull(input, name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }

    private static String optionalText(String input) {
        return input == null || input.isBlank() ? null : input.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
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

    private static void nullableText(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.trim());
    }

    private static Optional<ClaimEntry> loadClaim(Connection connection, UUID claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT claim_id, idempotency_token, player_uuid, source_type, source_id, crate_id, reward_id,
                       item_bytes, item_amount, item_sha256, virtual_key_id, virtual_key_amount, state,
                       attempt_token, last_result, created_at, updated_at
                FROM claim_entry WHERE claim_id = ?
                """)) {
            statement.setString(1, claimId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(claimEntry(rows)) : Optional.empty();
            }
        }
    }

    private static Optional<ClaimEntry> loadClaimByToken(Connection connection, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT claim_id, idempotency_token, player_uuid, source_type, source_id, crate_id, reward_id,
                       item_bytes, item_amount, item_sha256, virtual_key_id, virtual_key_amount, state,
                       attempt_token, last_result, created_at, updated_at
                FROM claim_entry WHERE idempotency_token = ?
                """)) {
            statement.setString(1, token);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(claimEntry(rows)) : Optional.empty();
            }
        }
    }

    private static ClaimEntry claimEntry(ResultSet rows) throws SQLException {
        return new ClaimEntry(UUID.fromString(rows.getString(1)), rows.getString(2),
                UUID.fromString(rows.getString(3)), rows.getString(4), rows.getString(5), rows.getString(6),
                rows.getString(7), rows.getBytes(8), rows.getInt(9), rows.getString(10), rows.getString(11),
                rows.getInt(12), rows.getString(13), rows.getString(14), rows.getString(15),
                Instant.ofEpochMilli(rows.getLong(16)), Instant.ofEpochMilli(rows.getLong(17)));
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

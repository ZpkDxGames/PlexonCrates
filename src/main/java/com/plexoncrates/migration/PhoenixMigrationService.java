package com.plexoncrates.migration;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.CrateLocation;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.crate.RewardActionType;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.manager.CrateManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Clean-room, read-only PhoenixCratesLite data importer for PlexonCrates 4.0.
 *
 * <p>Only operator-owned server data is read. Phoenix code, assets, GUI definitions and runtime
 * classes are never loaded or copied into PlexonCrates. Imported definitions are always disabled
 * for administrator review before cutover.</p>
 */
public final class PhoenixMigrationService {
    private static final Pattern MINI_TAG = Pattern.compile("<[^>]+>");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final PlexonCrates plugin;
    private final CrateManager crates;
    private final DatabaseManager database;
    private final ExecutorService executor;
    private final Path pluginsDirectory;
    private final Path sourceDirectory;
    private final Path migrationDirectory;

    public PhoenixMigrationService(PlexonCrates plugin, CrateManager crates,
                                   DatabaseManager database, ExecutorService executor) {
        this.plugin = Objects.requireNonNull(plugin);
        this.crates = Objects.requireNonNull(crates);
        this.database = Objects.requireNonNull(database);
        this.executor = Objects.requireNonNull(executor);
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        this.pluginsDirectory = Objects.requireNonNull(data.getParent(), "plugins directory");
        this.sourceDirectory = pluginsDirectory.resolve("PhoenixCratesLite").normalize();
        this.migrationDirectory = data.resolve("migration/phoenix").normalize();
    }

    public Path sourceDirectory() {
        return sourceDirectory;
    }

    public CompletableFuture<Plan> planAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return plan();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }, executor);
    }

    public Plan plan() throws Exception {
        validateSourceRoot();
        String hash = sourceHash(sourceDirectory);
        Map<String, ItemStack> keys = loadKeys();
        Map<String, List<CrateLocation>> locations = loadLocations();
        List<Crate> imported = new ArrayList<>();
        Map<String, String> crateMap = new LinkedHashMap<>();
        Map<String, String> rewardMap = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int rewards = 0;

        Path crateDirectory = sourceDirectory.resolve("crates");
        List<Path> files;
        try (var stream = Files.list(crateDirectory)) {
            files = stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }

        for (Path file : files) {
            try {
                ParsedCrate parsed = parseCrate(file, keys, locations);
                imported.add(parsed.crate());
                crateMap.put(parsed.phoenixId(), parsed.crate().id());
                for (String rewardId : parsed.rewardIds()) {
                    String previous = rewardMap.putIfAbsent(rewardId, parsed.crate().id());
                    if (previous != null && !previous.equals(parsed.crate().id())) {
                        errors.add("Phoenix reward ID is not globally unique: " + rewardId);
                    }
                }
                rewards += parsed.crate().rewards().size();
                warnings.addAll(parsed.warnings());
            } catch (Exception error) {
                errors.add(file.getFileName() + ": " + message(error));
            }
        }

        History history = readHistory(crateMap, rewardMap);
        warnings.addAll(history.warnings());

        Set<String> occupiedLocations = new LinkedHashSet<>();
        for (Crate current : crates.all()) {
            for (CrateLocation location : current.locations()) occupiedLocations.add(location.key());
        }
        for (Crate crate : imported) {
            if (crates.find(crate.id()).isPresent()) errors.add("Target crate ID already exists: " + crate.id());
            for (CrateLocation location : crate.locations()) {
                if (occupiedLocations.contains(location.key())) {
                    errors.add("Target location is already linked in PlexonCrates: " + location.key());
                }
            }
        }

        return new Plan(sourceDirectory, hash, List.copyOf(imported), rewards,
                locations.values().stream().mapToInt(List::size).sum(), keys.size(), history.playerCount(),
                history.openings(), history.rewardWins(), history.orphanRewardWins(), history.openingRows(),
                history.rewardRows(), List.copyOf(warnings), List.copyOf(errors));
    }

    public CompletableFuture<ImportResult> importConfirmed(Plan plan) {
        if (plan == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Migration plan is required"));
        if (!plan.ready()) return CompletableFuture.failedFuture(
                new IllegalStateException("Phoenix migration plan contains blocking errors"));

        return CompletableFuture.supplyAsync(() -> {
            try {
                validateSourceRoot();
                String currentHash = sourceHash(sourceDirectory);
                if (!currentHash.equals(plan.sourceHash())) {
                    throw new IllegalStateException("Phoenix source changed after planning; run plan again");
                }
                return createBackups(plan.sourceHash());
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        }, executor).thenCompose(backup -> installDefinitions(plan)
                .thenCompose(ignored -> database.importPhoenixHistory(
                        plan.sourceHash(), plan.openingRows(), plan.rewardRows()))
                .thenCompose(history -> writeReportAsync(plan, backup, history)
                        .thenApply(report -> new ImportResult(plan.crates().size(), plan.rewardCount(),
                                plan.locationCount(), plan.playerCount(), plan.openings(), plan.rewardWins(),
                                plan.orphanRewardWins(), history.applied(), backup, report))));
    }

    public CompletableFuture<Path> writePlanReport(Plan plan) {
        return writeReportAsync(plan, null, null);
    }

    private CompletableFuture<Void> installDefinitions(Plan plan) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                CompletableFuture<Void> save = crates.installImportedCrates(plan.crates());
                save.whenComplete((ignored, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            stampLoadedLocations(plan.crates());
                            result.complete(null);
                        } catch (Throwable stampError) {
                            result.completeExceptionally(stampError);
                        }
                    });
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void stampLoadedLocations(List<Crate> imported) {
        for (Crate crate : imported) {
            for (CrateLocation location : crate.locations()) {
                World world = Bukkit.getWorld(location.worldName());
                if (world == null || !world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) continue;
                try {
                    var block = world.getBlockAt(location.x(), location.y(), location.z());
                    if (block.getState() instanceof org.bukkit.block.TileState tile) {
                        tile.getPersistentDataContainer().set(
                                crates.crateBlockKey(), PersistentDataType.STRING, crate.id());
                        tile.update(true, false);
                    }
                } catch (Exception error) {
                    plugin.getLogger().log(Level.WARNING,
                            "Could not stamp imported crate PDC at " + location.key(), error);
                }
            }
        }
    }

    private ParsedCrate parseCrate(Path file, Map<String, ItemStack> keyItems,
                                   Map<String, List<CrateLocation>> locations) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        String phoenixId = required(yaml.getString("identifier"), "crate identifier");
        String id = normalizeId(phoenixId);
        String displayName = legacyText(yaml.getString("display-name", phoenixId));
        List<String> description = yaml.getStringList("item.lore").stream()
                .map(PhoenixMigrationService::legacyText).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        description.add("");
        description.add("&8Migrated from PhoenixCratesLite; review before enabling.");
        ItemStack icon = simpleItem(yaml.getConfigurationSection("item"), Material.CHEST);

        List<String> linkedKeys = yaml.getStringList("linked-keys-ids");
        if (linkedKeys.size() != 1) {
            throw new IllegalStateException("Expected exactly one linked Phoenix key; found " + linkedKeys.size());
        }
        String phoenixKey = linkedKeys.get(0).trim().toLowerCase(Locale.ROOT);
        ItemStack keyItem = keyItems.get(phoenixKey);
        if (keyItem == null) throw new IllegalStateException("Missing linked key definition: " + phoenixKey);
        String keyName = keyItem.hasItemMeta() && keyItem.getItemMeta().hasDisplayName()
                ? keyItem.getItemMeta().getDisplayName() : "&6" + title(id) + " Key";

        ConfigurationSection rewardRoot = yaml.getConfigurationSection("rewards");
        if (rewardRoot == null || rewardRoot.getKeys(false).isEmpty()) {
            throw new IllegalStateException("No Phoenix rewards found");
        }
        List<Reward> rewards = new ArrayList<>();
        Set<String> rewardIds = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<String> slots = rewardRoot.getKeys(false).stream()
                .sorted(Comparator.comparingInt(PhoenixMigrationService::numericOrder)
                        .thenComparing(Comparator.naturalOrder())).toList();

        for (String slot : slots) {
            ConfigurationSection section = rewardRoot.getConfigurationSection(slot);
            if (section == null) continue;
            String rewardId = normalizeId(required(section.getString("identifier"), "reward identifier"));
            if (!rewardIds.add(rewardId)) throw new IllegalStateException("Duplicate reward ID: " + rewardId);
            boolean enabled = section.getBoolean("enabled", true);
            if (!enabled) warnings.add("Reward " + rewardId + " is disabled in Phoenix");
            if (!section.getStringList("win-commands").isEmpty()) {
                throw new IllegalStateException("Reward " + rewardId + " contains unsupported Phoenix command actions");
            }
            ConfigurationSection winItems = section.getConfigurationSection("win-items");
            if (winItems == null || winItems.getKeys(false).size() != 1) {
                throw new IllegalStateException("Reward " + rewardId + " must contain exactly one win item");
            }
            String winSlot = winItems.getKeys(false).iterator().next();
            ItemStack exact = phoenixItem(yaml, winItems.getConfigurationSection(winSlot));
            rewards.add(new Reward(rewardId, enabled, scaleWeight(section.get("weight")), exact,
                    List.of(new RewardAction(RewardActionType.ITEM, "", exact))));
        }

        BigDecimal total = BigDecimal.ZERO;
        for (String slot : rewardRoot.getKeys(false)) {
            ConfigurationSection section = rewardRoot.getConfigurationSection(slot);
            if (section != null && section.get("weight") != null) {
                total = total.add(new BigDecimal(String.valueOf(section.get("weight"))));
            }
        }
        if (total.compareTo(new BigDecimal("100")) != 0) {
            warnings.add("Phoenix reward weights total " + total.toPlainString()
                    + " rather than 100.0; relative probabilities were preserved");
        }

        String animation = switch (yaml.getString("opening-animation.open", "").toUpperCase(Locale.ROOT)) {
            case "SPINNER", "SWIRL", "ROTATING_HEAD" -> "WHEEL";
            default -> "CSGO";
        };
        String idle = inferIdleEffect(yaml);
        Crate crate = new Crate(id, false, displayName, description, icon, keyName, keyItem,
                animation, idle, rewards, locations.getOrDefault(phoenixId, List.of()));
        return new ParsedCrate(phoenixId, crate, Set.copyOf(rewardIds), List.copyOf(warnings));
    }

    private Map<String, ItemStack> loadKeys() {
        Path directory = sourceDirectory.resolve("keys");
        if (!Files.isDirectory(directory)) throw new IllegalStateException("Phoenix keys directory is missing");
        Map<String, ItemStack> result = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted().toList()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
                if (!yaml.getBoolean("enabled", true)) continue;
                String id = required(yaml.getString("identifier"), "key identifier").toLowerCase(Locale.ROOT);
                result.put(id, phoenixItem(yaml, yaml.getConfigurationSection("item")));
            }
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
        return Map.copyOf(result);
    }

    private Map<String, List<CrateLocation>> loadLocations() {
        Path file = sourceDirectory.resolve("locations.yml");
        if (!Files.isRegularFile(file)) return Map.of();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection root = yaml.getConfigurationSection("locations");
        if (root == null) return Map.of();
        Map<String, List<CrateLocation>> result = new LinkedHashMap<>();
        for (String crateId : root.getKeys(false)) {
            List<CrateLocation> parsed = new ArrayList<>();
            for (String raw : root.getStringList(crateId)) {
                CrateLocation location = parseLocation(raw);
                if (location != null) parsed.add(location);
            }
            result.put(crateId, List.copyOf(parsed));
        }
        return Map.copyOf(result);
    }

    private History readHistory(Map<String, String> crateMap, Map<String, String> rewardMap) throws Exception {
        Path file = sourceDirectory.resolve("database.db");
        if (!Files.isRegularFile(file)) return History.empty();
        Class.forName("org.sqlite.JDBC");
        List<DatabaseManager.HistoricalOpening> openings = new ArrayList<>();
        List<DatabaseManager.HistoricalRewardWin> rewards = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long openingTotal = 0L;
        long rewardTotal = 0L;
        long orphanTotal = 0L;
        int players;

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM player_table")) {
                players = rows.next() ? rows.getInt(1) : 0;
            }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                    SELECT p.UniqueId, p.PlayerName, j.key, CAST(j.value AS INTEGER)
                    FROM player_table p, json_each(COALESCE(p.OpenedCratesAmount, '{}')) j
                    """)) {
                while (rows.next()) {
                    UUID player = UUID.fromString(rows.getString(1));
                    String name = rows.getString(2);
                    String target = crateMap.get(rows.getString(3));
                    long count = rows.getLong(4);
                    if (target == null) {
                        warnings.add("Historical openings reference unknown Phoenix crate " + rows.getString(3));
                        continue;
                    }
                    openings.add(new DatabaseManager.HistoricalOpening(player, name, target, count));
                    openingTotal = Math.addExact(openingTotal, count);
                }
            }
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
                    SELECT p.UniqueId, p.PlayerName, j.key, CAST(j.value AS INTEGER)
                    FROM player_table p, json_each(COALESCE(p.RewardsWinAmount, '{}')) j
                    """)) {
                while (rows.next()) {
                    UUID player = UUID.fromString(rows.getString(1));
                    String name = rows.getString(2);
                    String rewardId = normalizeId(rows.getString(3));
                    String target = rewardMap.get(rewardId);
                    long count = rows.getLong(4);
                    rewards.add(new DatabaseManager.HistoricalRewardWin(player, name, rewardId, target, count));
                    rewardTotal = Math.addExact(rewardTotal, count);
                    if (target == null) orphanTotal = Math.addExact(orphanTotal, count);
                }
            }
            for (String column : List.of("VirtualKeys", "Rerolls", "CratesCooldown", "RewardsCooldown")) {
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*) FROM player_table WHERE TRIM(COALESCE(" + column
                                + ", '{}')) NOT IN ('', '{}')")) {
                    if (rows.next() && rows.getLong(1) > 0L) {
                        warnings.add("Phoenix column " + column
                                + " contains non-empty state; retained in the source backup for manual review");
                    }
                }
            }
        }
        return new History(players, openingTotal, rewardTotal, orphanTotal,
                List.copyOf(openings), List.copyOf(rewards), List.copyOf(warnings));
    }

    private ItemStack phoenixItem(YamlConfiguration yaml, ConfigurationSection descriptor) {
        if (descriptor == null) throw new IllegalStateException("Missing Phoenix item descriptor");
        String material = required(descriptor.getString("material"), "item material");
        ItemStack item;
        if (material.regionMatches(true, 0, "custom:", 0, 7)) {
            String internalId = material.substring(7);
            Object raw = yaml.get("internal-storage.items." + internalId);
            ItemStack snapshot = raw instanceof ItemStack direct
                    ? direct : yaml.getItemStack("internal-storage.items." + internalId);
            if (snapshot == null) throw new IllegalStateException("Missing exact internal item snapshot: " + internalId);
            item = snapshot.clone();
        } else {
            Material type = Material.matchMaterial(material);
            if (type == null || type.isAir()) throw new IllegalStateException("Unknown item material: " + material);
            item = new ItemStack(type);
        }
        item.setAmount(Math.max(1, descriptor.getInt("amount", 1)));
        return item;
    }

    private static ItemStack simpleItem(ConfigurationSection descriptor, Material fallback) {
        Material type = fallback;
        if (descriptor != null) {
            Material parsed = Material.matchMaterial(descriptor.getString("material", fallback.name()));
            if (parsed != null && !parsed.isAir()) type = parsed;
        }
        ItemStack item = new ItemStack(type, descriptor == null ? 1 : Math.max(1, descriptor.getInt("amount", 1)));
        if (descriptor == null) return item;
        ItemMeta meta = item.getItemMeta();
        String name = descriptor.getString("display-name");
        if (name != null && !name.isBlank()) meta.setDisplayName(legacyText(name));
        List<String> lore = descriptor.getStringList("lore");
        if (!lore.isEmpty()) meta.setLore(lore.stream().map(PhoenixMigrationService::legacyText).toList());
        item.setItemMeta(meta);
        return item;
    }

    public static int scaleWeight(Object raw) {
        if (raw == null) throw new IllegalArgumentException("Phoenix reward weight is missing");
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw));
            if (value.signum() < 0) throw new IllegalArgumentException("Phoenix reward weight cannot be negative");
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Phoenix reward weight must have at most two decimal places: " + raw, error);
        }
    }

    public static String normalizeId(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_");
        while (normalized.startsWith("_")) normalized = normalized.substring(1);
        while (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank() || normalized.length() > 64) {
            throw new IllegalArgumentException("Phoenix identifier cannot be normalized safely: " + source);
        }
        return normalized;
    }

    public static CrateLocation parseLocation(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(";");
        if (parts.length < 4) throw new IllegalArgumentException("Malformed Phoenix location: " + raw);
        return new CrateLocation(null, parts[0].trim(), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
    }

    public static String legacyText(String raw) {
        return raw == null ? "" : MINI_TAG.matcher(raw).replaceAll("");
    }

    private static int numericOrder(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static String inferIdleEffect(YamlConfiguration yaml) {
        String joined = String.join(" ", yaml.getStringList("idle-effects")).toUpperCase(Locale.ROOT);
        if (joined.contains("PULSE") || joined.contains("DUST")) return "CLOUD";
        if (joined.contains("CIRCLE") || joined.contains("HELIX")) return "HELIX";
        return "NONE";
    }

    private void validateSourceRoot() throws IOException {
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalStateException("Phoenix source directory not found: " + sourceDirectory);
        }
        if (Files.isSymbolicLink(sourceDirectory)) {
            throw new IllegalStateException("Phoenix source directory cannot be a symlink");
        }
        Path realParent = pluginsDirectory.toRealPath();
        Path realSource = sourceDirectory.toRealPath();
        if (!realSource.getParent().equals(realParent)) {
            throw new IllegalStateException("Phoenix source must be the sibling plugins/PhoenixCratesLite directory");
        }
        if (!Files.isDirectory(realSource.resolve("crates")) || !Files.isDirectory(realSource.resolve("keys"))) {
            throw new IllegalStateException("Phoenix source is missing crates/ or keys/");
        }
    }

    private static String sourceHash(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> paths;
        try (var stream = Files.walk(source)) {
            paths = stream.sorted().toList();
        }
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalStateException("Phoenix source contains symlink: " + path);
            }
            if (!Files.isRegularFile(path)) continue;
            String relative = source.relativize(path).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(path));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path createBackups(String hash) throws IOException {
        String timestamp = BACKUP_TIME.format(Instant.now());
        Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path root = data.resolve("backups/phoenix/" + timestamp + "-" + hash.substring(0, 12)).normalize();
        if (!root.startsWith(data)) throw new IllegalStateException("Backup path escaped PlexonCrates data directory");
        copyReadOnlyTree(sourceDirectory, root.resolve("PhoenixCratesLite"));
        Path current = root.resolve("plexoncrates-before-import");
        Files.createDirectories(current);
        copyIfExists(data.resolve("crates.yml"), current.resolve("crates.yml"));
        copyIfExists(data.resolve(plugin.settings().databaseFile()), current.resolve("plexoncrates.db"));
        return root;
    }

    private CompletableFuture<Path> writeReportAsync(Plan plan, Path backup,
                                                       DatabaseManager.HistoricalImportResult history) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(migrationDirectory);
                Path report = migrationDirectory.resolve("report-" + plan.sourceHash().substring(0, 12) + ".md");
                StringBuilder out = new StringBuilder("# PhoenixCratesLite → PlexonCrates 4.0 Migration Report\n\n");
                out.append("- Source: `").append(plan.source()).append("`\n")
                        .append("- SHA-256: `").append(plan.sourceHash()).append("`\n")
                        .append("- Crates: ").append(plan.crates().size()).append("\n")
                        .append("- Rewards: ").append(plan.rewardCount()).append("\n")
                        .append("- Keys: ").append(plan.keyCount()).append("\n")
                        .append("- Locations: ").append(plan.locationCount()).append("\n")
                        .append("- Players: ").append(plan.playerCount()).append("\n")
                        .append("- Historical openings: ").append(plan.openings()).append("\n")
                        .append("- Historical reward wins: ").append(plan.rewardWins()).append("\n")
                        .append("- Orphan/retired reward wins: ").append(plan.orphanRewardWins()).append("\n");
                if (backup != null) out.append("- Backup: `").append(backup).append("`\n");
                if (history != null) out.append("- Historical DB import applied: ").append(history.applied()).append("\n");
                out.append("\n## Crates\n\n");
                for (Crate crate : plan.crates()) {
                    int totalWeight = crate.rewards().stream().mapToInt(Reward::weight).sum();
                    out.append("- `").append(crate.id()).append("`: ").append(crate.rewards().size())
                            .append(" rewards, ").append(crate.locations().size())
                            .append(" locations, total integer weight ").append(totalWeight)
                            .append(", imported **disabled**\n");
                }
                if (!plan.warnings().isEmpty()) {
                    out.append("\n## Warnings\n\n");
                    for (String warning : plan.warnings()) out.append("- ").append(warning).append("\n");
                }
                if (!plan.errors().isEmpty()) {
                    out.append("\n## Blocking errors\n\n");
                    for (String error : plan.errors()) out.append("- ").append(error).append("\n");
                }
                out.append("\nImported definitions are intentionally disabled. Review them in `/crates admin` before enabling and cutover.\n");
                Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
                return report;
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }, executor);
    }

    private static void copyReadOnlyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("Refusing to follow Phoenix symlink: " + dir);
                Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file)) throw new IOException("Refusing to copy Phoenix symlink: " + file);
                Path target = destination.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyIfExists(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source)) return;
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing Phoenix " + field);
        return value;
    }

    private static String title(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.replace('-', '_').split("_+")) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record Plan(Path source, String sourceHash, List<Crate> crates, int rewardCount,
                       int locationCount, int keyCount, int playerCount, long openings,
                       long rewardWins, long orphanRewardWins,
                       List<DatabaseManager.HistoricalOpening> openingRows,
                       List<DatabaseManager.HistoricalRewardWin> rewardRows,
                       List<String> warnings, List<String> errors) {
        public Plan {
            crates = List.copyOf(crates);
            openingRows = List.copyOf(openingRows);
            rewardRows = List.copyOf(rewardRows);
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }

        public boolean ready() {
            return errors.isEmpty() && !crates.isEmpty();
        }
    }

    public record ImportResult(int crates, int rewards, int locations, int players,
                               long openings, long rewardWins, long orphanRewardWins,
                               boolean historyApplied, Path backup, Path report) {}

    private record ParsedCrate(String phoenixId, Crate crate, Set<String> rewardIds, List<String> warnings) {}

    private record History(int playerCount, long openings, long rewardWins, long orphanRewardWins,
                           List<DatabaseManager.HistoricalOpening> openingRows,
                           List<DatabaseManager.HistoricalRewardWin> rewardRows,
                           List<String> warnings) {
        private static History empty() {
            return new History(0, 0L, 0L, 0L, List.of(), List.of(), List.of());
        }
    }
}

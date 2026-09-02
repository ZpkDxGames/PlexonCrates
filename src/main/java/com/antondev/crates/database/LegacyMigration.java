package com.antondev.crates.database;

import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.service.CrateRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds and commits the reversible 1.0 -> 2.0 definition/data migration. */
public final class LegacyMigration {
    private static final String MARKER = "plexoncrates-1.0.0-to-2.0.0";
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    public record Result(boolean migrated, Path backupDirectory, int locations, long openings) {}

    private LegacyMigration() {}

    public static Result migrate(Path dataFolder, DatabaseService database) throws Exception {
        Path config = dataFolder.resolve("config.yml");
        if (!Files.exists(config)) return new Result(false, null, 0, 0);
        YamlConfiguration root = read(config);
        if (root.getInt("config-version", 1) >= 2) return new Result(false, null, 0, 0);

        Instant now = Instant.now();
        Path backup = dataFolder.resolve("backups").resolve("migration-1.0.0-" + BACKUP_TIME.format(now));
        Map<Path, String> replacements = new LinkedHashMap<>();
        backupYaml(dataFolder, backup);

        replacements.put(config, convertConfig(root));
        Path keysFile = dataFolder.resolve("keys.yml");
        replacements.put(keysFile, convertKeys(read(keysFile), now));
        Path cratesDirectory = dataFolder.resolve("crates");
        try (var files = Files.list(cratesDirectory)) {
            for (Path crate : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                replacements.put(crate, convertCrate(read(crate), crate, now));
            }
        }

        List<DatabaseService.StoredLocation> locations = readLocations(dataFolder.resolve("locations.yml"), now);
        DatabaseService.StatsSnapshot statistics = readStatistics(dataFolder.resolve("statistics.yml"));
        validateReplacements(replacements);
        database.importLegacy(MARKER, locations, statistics, () -> {
            try {
                for (Map.Entry<Path, String> replacement : replacements.entrySet()) {
                    AtomicFiles.write(replacement.getKey(), replacement.getValue());
                }
            } catch (Exception error) {
                restoreYaml(dataFolder, backup);
                throw new IllegalStateException("2.0 migration files could not be committed; 1.0 YAML was restored from " + backup, error);
            }
        });
        long openings = statistics.global().values().stream().mapToLong(Long::longValue).sum();
        return new Result(true, backup, locations.size(), openings);
    }

    private static String convertConfig(YamlConfiguration yaml) {
        yaml.set("config-version", 2);
        setDefault(yaml, "database.file", "data/plexoncrates.db");
        setDefault(yaml, "database.maximum-queued-writes", 4096);
        setDefault(yaml, "database.shutdown-timeout-seconds", 8);
        setDefault(yaml, "interaction.consume-offhand-keys", false);
        setDefault(yaml, "opening.default-animation", "ROULETTE");
        setDefault(yaml, "opening.bulk-summary-threshold", 5);
        setDefault(yaml, "opening.recovery-policy", "MANUAL_REVIEW");
        setDefault(yaml, "editing.input-timeout-seconds", 60);
        setDefault(yaml, "editing.session-timeout-minutes", 30);
        setDefault(yaml, "locations.denied-materials", List.of("AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "FIRE", "SOUL_FIRE", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY"));
        setDefault(yaml, "locations.allowed-worlds", List.of());
        setDefault(yaml, "integrations.placeholderapi", true);
        setDefault(yaml, "integrations.vault", true);
        return yaml.saveToString();
    }

    private static String convertKeys(YamlConfiguration legacy, Instant now) {
        if (legacy.getInt("config-version", 1) >= 2) return legacy.saveToString();
        ConfigurationSection oldKeys = legacy.getConfigurationSection("keys");
        if (oldKeys == null || oldKeys.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("keys.yml contains no legacy keys");
        }
        YamlConfiguration next = new YamlConfiguration();
        next.set("config-version", 2);
        for (String rawId : oldKeys.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!CrateRegistry.validId(id)) throw new IllegalArgumentException("Invalid legacy key ID: " + rawId);
            ConfigurationSection item = oldKeys.getConfigurationSection(rawId);
            if (item == null) throw new IllegalArgumentException("Legacy key is not an item section: " + rawId);
            String base = "keys." + id;
            next.set(base + ".enabled", true);
            next.set(base + ".display-name", item.getString("name", "<white><bold>" + pretty(id) + " Key</bold></white>"));
            next.set(base + ".source", "PLEXONKEYS");
            next.set(base + ".external-id", id);
            next.set(base + ".match-mode", "EXACT");
            next.set(base + ".cache-last-known-good", true);
            next.set(base + ".fallback", new LinkedHashMap<>(item.getValues(true)));
            next.set(base + ".legacy-templates", List.of());
            next.set(base + ".created-at", now.toString());
            next.set(base + ".updated-at", now.toString());
        }
        return next.saveToString();
    }

    private static String convertCrate(YamlConfiguration yaml, Path file, Instant now) {
        if (yaml.getInt("config-version", 1) >= 2) return yaml.saveToString();
        String id = yaml.getString("id", file.getFileName().toString().replaceFirst("\\.yml$", ""));
        if (!CrateRegistry.validId(id)) throw new IllegalArgumentException(file.getFileName() + ": invalid crate ID");
        boolean enabled = yaml.getBoolean("enabled", true);
        String keyId = yaml.getString("key-id", id);
        String permission = yaml.getString("permission", "");
        int cooldown = yaml.getInt("open-cooldown-seconds", 0);
        yaml.set("config-version", 2);
        yaml.set("state", enabled ? "PUBLISHED" : "DISABLED");
        yaml.set("display-order", defaultOrder(id));
        setDefault(yaml, "description", List.of());
        yaml.set("access.permission", permission);
        setDefault(yaml, "access.worlds", List.of());
        setDefault(yaml, "access.excluded-worlds", List.of());
        yaml.set("keys.cost", 1);
        yaml.set("keys.accepted", List.of(keyId));
        yaml.set("opening.cooldown-seconds", cooldown);
        setDefault(yaml, "opening.bulk-enabled", true);
        setDefault(yaml, "opening.bulk-maximum", 64);
        setDefault(yaml, "opening.animation", "ROULETTE");
        setDefault(yaml, "pity.enabled", false);
        setDefault(yaml, "pity.threshold", 0);
        setDefault(yaml, "pity.reward-ids", List.of());
        setDefault(yaml, "audit.created-at", now.toString());
        setDefault(yaml, "audit.updated-at", now.toString());
        yaml.set("enabled", null);
        yaml.set("key-id", null);
        yaml.set("permission", null);
        yaml.set("open-cooldown-seconds", null);

        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards != null) {
            for (String rewardId : rewards.getKeys(false)) {
                String path = "rewards." + rewardId;
                setDefault(yaml, path + ".rarity", "COMMON");
                setDefault(yaml, path + ".experience.points", 0);
                setDefault(yaml, path + ".experience.levels", 0);
                setDefault(yaml, path + ".money.amount", 0.0);
                setDefault(yaml, path + ".limits", Map.of());
                setDefault(yaml, path + ".personal-message", "");
                List<String> commands = yaml.getStringList(path + ".commands").stream().map(command -> {
                    String normalized = command.trim();
                    if (normalized.startsWith("/")) normalized = normalized.substring(1).trim();
                    if (normalized.isBlank() || normalized.contains("\n") || normalized.contains("\r")) {
                        throw new IllegalArgumentException(file.getFileName() + ": invalid legacy command in " + path);
                    }
                    return normalized;
                }).toList();
                yaml.set(path + ".commands", commands);
            }
        }
        return yaml.saveToString();
    }

    private static List<DatabaseService.StoredLocation> readLocations(Path file, Instant now) throws IOException {
        if (!Files.exists(file)) return List.of();
        YamlConfiguration yaml = read(file);
        var result = new ArrayList<DatabaseService.StoredLocation>();
        for (Map<?, ?> raw : yaml.getMapList("locations")) {
            if (!(raw.get("world") instanceof String world) || world.isBlank()
                    || !(raw.get("crate") instanceof String crate)
                    || !(raw.get("x") instanceof Number x)
                    || !(raw.get("y") instanceof Number y)
                    || !(raw.get("z") instanceof Number z)) {
                throw new IllegalArgumentException("Every legacy location needs world, crate, x, y, and z");
            }
            result.add(new DatabaseService.StoredLocation(null, world, exact(x), exact(y), exact(z),
                    crate.toLowerCase(Locale.ROOT), now));
        }
        return List.copyOf(result);
    }

    private static DatabaseService.StatsSnapshot readStatistics(Path file) throws IOException {
        if (!Files.exists(file)) return new DatabaseService.StatsSnapshot(Map.of(), Map.of());
        YamlConfiguration yaml = read(file);
        var global = new LinkedHashMap<String, Long>();
        var players = new LinkedHashMap<UUID, Map<String, Long>>();
        ConfigurationSection globalSection = yaml.getConfigurationSection("global");
        if (globalSection != null) {
            for (String id : globalSection.getKeys(false)) global.put(id, nonNegative(globalSection.getLong(id)));
        }
        ConfigurationSection playerSection = yaml.getConfigurationSection("players");
        if (playerSection != null) {
            for (String rawUuid : playerSection.getKeys(false)) {
                UUID uuid = UUID.fromString(rawUuid);
                var values = new LinkedHashMap<String, Long>();
                ConfigurationSection section = playerSection.getConfigurationSection(rawUuid);
                if (section != null) {
                    for (String id : section.getKeys(false)) values.put(id, nonNegative(section.getLong(id)));
                }
                players.put(uuid, values);
            }
        }
        return new DatabaseService.StatsSnapshot(global, players);
    }

    private static void validateReplacements(Map<Path, String> replacements) {
        for (Map.Entry<Path, String> replacement : replacements.entrySet()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(replacement.getValue());
            } catch (Exception error) {
                throw new IllegalArgumentException("Converted YAML is invalid: " + replacement.getKey(), error);
            }
            if (yaml.getInt("config-version") != 2) {
                throw new IllegalArgumentException("Converted file is not config-version 2: " + replacement.getKey());
            }
        }
    }

    private static void backupYaml(Path dataFolder, Path backup) throws IOException {
        Files.createDirectories(backup);
        try (var files = Files.walk(dataFolder)) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml")).toList()) {
                if (source.startsWith(dataFolder.resolve("backups"))) continue;
                Path target = backup.resolve(dataFolder.relativize(source));
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void restoreYaml(Path dataFolder, Path backup) throws IOException {
        try (var files = Files.walk(backup)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                Path target = dataFolder.resolve(backup.relativize(source));
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException(file.getFileName() + ": invalid YAML", error);
        }
        return yaml;
    }

    private static void setDefault(YamlConfiguration yaml, String path, Object value) {
        if (!yaml.contains(path)) yaml.set(path, value);
    }

    private static int defaultOrder(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "basic" -> 10;
            case "rare" -> 20;
            case "epic" -> 30;
            case "legendary" -> 40;
            default -> 50;
        };
    }

    private static int exact(Number value) {
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < -30_000_000 || number > 30_000_000) {
            throw new IllegalArgumentException("Invalid legacy location coordinate");
        }
        return (int) number;
    }

    private static long nonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Legacy statistics cannot be negative");
        return value;
    }

    private static String pretty(String id) {
        StringBuilder result = new StringBuilder();
        for (String word : id.split("[_-]")) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}

package com.antondev.crates.migration.phoenix;

import com.antondev.crates.config.Text;
import com.antondev.crates.service.CrateRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * Parser for the operator-owned PhoenixCratesLite schema supplied from PlexonCraft.
 *
 * <p>This adapter intentionally supports the observed data contract only. Unknown
 * non-empty state is reported instead of guessed. Source files are opened read-only
 * and are never rewritten.</p>
 */
public final class PhoenixFixtureAdapter {
    private static final Pattern JSON_ENTRY = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*(-?[0-9]+)");

    public Fixture load(Path configuredRoot) throws Exception {
        Path sourceRoot = Objects.requireNonNull(configuredRoot, "configuredRoot").toAbsolutePath().normalize();
        Path root = fixtureRoot(sourceRoot);
        var warnings = new ArrayList<String>();

        Map<String, PhoenixKey> keys = loadKeys(root.resolve("keys"));
        List<PhoenixCrate> crates = loadCrates(root.resolve("crates"), warnings);
        Map<String, String> rewardToCrate = new LinkedHashMap<>();
        for (PhoenixCrate crate : crates) {
            for (PhoenixReward reward : crate.rewards()) {
                String previous = rewardToCrate.putIfAbsent(reward.sourceId(), crate.sourceId());
                if (previous != null && !previous.equals(crate.sourceId())) {
                    throw new IllegalArgumentException("Phoenix reward ID is reused across crates: " + reward.sourceId());
                }
            }
        }

        List<PhoenixLocation> locations = loadLocations(root.resolve("locations.yml"));
        PlayerData playerData = loadPlayers(root.resolve("database.db"), rewardToCrate, warnings);
        return new Fixture(root, List.copyOf(crates), Map.copyOf(keys), List.copyOf(locations),
                playerData.players(), rewardToCrate, playerData.orphanRewardWins(), List.copyOf(warnings));
    }

    private static Path fixtureRoot(Path configuredRoot) throws Exception {
        if (!Files.isDirectory(configuredRoot)) {
            throw new IllegalArgumentException("Phoenix import source directory does not exist: " + configuredRoot);
        }
        if (Files.isDirectory(configuredRoot.resolve("crates")) && Files.isDirectory(configuredRoot.resolve("keys"))) {
            return configuredRoot;
        }
        try (var children = Files.list(configuredRoot)) {
            List<Path> candidates = children.filter(Files::isDirectory)
                    .filter(path -> Files.isDirectory(path.resolve("crates")) && Files.isDirectory(path.resolve("keys")))
                    .sorted().toList();
            if (candidates.size() == 1) return candidates.getFirst().toAbsolutePath().normalize();
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("Expected PhoenixCratesLite crates/ and keys/ below imports/phoenix");
            }
            throw new IllegalArgumentException("Multiple PhoenixCratesLite source roots were found; keep one fixture only");
        }
    }

    private static Map<String, PhoenixKey> loadKeys(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Phoenix keys directory is missing");
        var result = new LinkedHashMap<String, PhoenixKey>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration yaml = yaml(file);
                String sourceId = required(yaml.getString("identifier"), file + " identifier");
                if (!yaml.getBoolean("enabled", true)) continue;
                ItemStack template = item(yaml, yaml.getConfigurationSection("item"), true,
                        file + " item");
                String targetId = keyTarget(sourceId);
                PhoenixKey previous = result.putIfAbsent(sourceId.toLowerCase(Locale.ROOT),
                        new PhoenixKey(sourceId, targetId, template));
                if (previous != null) throw new IllegalArgumentException("Duplicate Phoenix key ID: " + sourceId);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No enabled Phoenix keys were found");
        return result;
    }

    private static List<PhoenixCrate> loadCrates(Path directory, List<String> warnings) throws Exception {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Phoenix crates directory is missing");
        var crates = new ArrayList<PhoenixCrate>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration yaml = yaml(file);
                if (!yaml.getBoolean("enabled", true)) {
                    warnings.add("Disabled Phoenix crate skipped: " + file.getFileName());
                    continue;
                }
                String sourceId = required(yaml.getString("identifier"), file + " identifier");
                String targetId = normalizeId(sourceId);
                if (!CrateRegistry.validId(targetId)) {
                    throw new IllegalArgumentException("Phoenix crate ID cannot be mapped safely: " + sourceId);
                }
                String displayName = required(yaml.getString("display-name"), sourceId + " display-name");
                ItemStack icon = item(yaml, yaml.getConfigurationSection("item"), true, sourceId + " icon");
                List<String> description = new ArrayList<>();
                ConfigurationSection itemSection = yaml.getConfigurationSection("item");
                if (itemSection != null) {
                    for (String line : itemSection.getStringList("lore")) description.add(legacyToMini(line));
                }
                if (description.isEmpty()) description.add("<gray>Imported from PhoenixCratesLite.</gray>");

                boolean permissionRequired = yaml.getBoolean("permission.required", false);
                String permission = permissionRequired ? yaml.getString("permission.permission", "").trim() : "";
                int cooldown = Math.max(0, yaml.getInt("open-cooldown", 0));
                List<String> keyIds = yaml.getStringList("linked-keys-ids");
                boolean keyRequired = yaml.getBoolean("key-required", true);
                if (keyRequired && keyIds.isEmpty()) {
                    throw new IllegalArgumentException(sourceId + " requires a key but has no linked key ID");
                }
                int keyCost = keyRequired ? 1 : 0;

                List<String> hologram = yaml.getStringList("hologram.lines").stream()
                        .map(PhoenixFixtureAdapter::legacyToMini).toList();
                if (hologram.isEmpty()) hologram = List.of(legacyToMini(displayName),
                        "<gray>Left-click to preview</gray>", "<white>Right-click to open</white>");

                String sourceAnimation = yaml.getString("opening-animation.open", "CSGO_GUI");
                String animation = mapAnimation(sourceAnimation);
                if (!animation.equalsIgnoreCase(sourceAnimation)) {
                    warnings.add(sourceId + " animation " + sourceAnimation + " maps to PlexonCrates " + animation + ".");
                }

                ConfigurationSection rewardsSection = yaml.getConfigurationSection("rewards");
                if (rewardsSection == null || rewardsSection.getKeys(false).isEmpty()) {
                    throw new IllegalArgumentException(sourceId + " has no rewards");
                }
                var rewards = new ArrayList<PhoenixReward>();
                int totalBasisPoints = 0;
                for (String index : sortedNumeric(rewardsSection.getKeys(false))) {
                    ConfigurationSection reward = rewardsSection.getConfigurationSection(index);
                    if (reward == null || !reward.getBoolean("enabled", true)) continue;
                    String rewardId = normalizeId(required(reward.getString("identifier"), sourceId + " reward identifier"));
                    if (!CrateRegistry.validId(rewardId)) {
                        throw new IllegalArgumentException(sourceId + " contains invalid reward ID " + rewardId);
                    }
                    int basisPoints = basisPoints(reward.get("weight"));
                    totalBasisPoints = Math.addExact(totalBasisPoints, basisPoints);
                    ItemStack display = item(yaml, reward.getConfigurationSection("display-item"), true,
                            sourceId + "/" + rewardId + " display-item");
                    String rewardName = displayName(display, rewardId);
                    ConfigurationSection winItems = reward.getConfigurationSection("win-items");
                    if (winItems == null || winItems.getKeys(false).isEmpty()) {
                        throw new IllegalArgumentException(sourceId + "/" + rewardId + " has no win-items in the supplied schema");
                    }
                    var items = new ArrayList<ItemStack>();
                    for (String itemIndex : sortedNumeric(winItems.getKeys(false))) {
                        items.add(item(yaml, winItems.getConfigurationSection(itemIndex), false,
                                sourceId + "/" + rewardId + "/win-items/" + itemIndex));
                    }
                    List<String> commands = reward.getStringList("win-commands");
                    if (!commands.isEmpty()) {
                        warnings.add(sourceId + "/" + rewardId + " contains Phoenix commands; review command semantics before publish.");
                    }
                    rewards.add(new PhoenixReward(rewardId, rewardName, basisPoints, display, items, commands));
                }
                if (totalBasisPoints != 10_000) {
                    throw new IllegalArgumentException(sourceId + " reward weights convert to " + totalBasisPoints
                            + " basis points instead of exactly 10000");
                }
                crates.add(new PhoenixCrate(sourceId, targetId, displayName, description, icon, permission,
                        keyCost, keyIds, cooldown, animation, hologram, List.copyOf(rewards)));
            }
        }
        if (crates.isEmpty()) throw new IllegalArgumentException("No enabled Phoenix crates were found");
        crates.sort(Comparator.comparing(PhoenixCrate::targetId));
        return List.copyOf(crates);
    }

    private static List<PhoenixLocation> loadLocations(Path file) throws Exception {
        if (!Files.isRegularFile(file)) return List.of();
        YamlConfiguration yaml = yaml(file);
        ConfigurationSection section = yaml.getConfigurationSection("locations");
        if (section == null) return List.of();
        var result = new ArrayList<PhoenixLocation>();
        for (String crateId : section.getKeys(false)) {
            for (String raw : section.getStringList(crateId)) {
                String[] parts = raw.split(";", 5);
                if (parts.length < 4) throw new IllegalArgumentException("Invalid Phoenix location: " + raw);
                result.add(new PhoenixLocation(crateId, parts[0], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), parts.length == 5 ? parts[4] : ""));
            }
        }
        return List.copyOf(result);
    }

    private static PlayerData loadPlayers(Path database, Map<String, String> rewardToCrate,
                                          List<String> warnings) throws Exception {
        if (!Files.isRegularFile(database)) return new PlayerData(List.of(), Map.of());
        var players = new ArrayList<PhoenixPlayer>();
        var orphans = new LinkedHashMap<String, Long>();
        String url = "jdbc:sqlite:" + database.toAbsolutePath();
        try (var connection = DriverManager.getConnection(url); Statement queryOnly = connection.createStatement()) {
            queryOnly.execute("PRAGMA query_only=ON");
            Set<String> columns = new LinkedHashSet<>();
            try (ResultSet rows = queryOnly.executeQuery("PRAGMA table_info(player_table)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            for (String required : List.of("UniqueId", "PlayerName", "OpenedCratesAmount", "RewardsWinAmount",
                    "VirtualKeys", "Rerolls", "CratesCooldown", "RewardsCooldown")) {
                if (!columns.contains(required)) throw new IllegalArgumentException("Phoenix player_table is missing " + required);
            }
            try (ResultSet rows = queryOnly.executeQuery("SELECT UniqueId, PlayerName, OpenedCratesAmount, RewardsWinAmount, "
                    + "VirtualKeys, Rerolls, CratesCooldown, RewardsCooldown FROM player_table ORDER BY Id")) {
                while (rows.next()) {
                    UUID playerId = UUID.fromString(rows.getString(1));
                    String name = rows.getString(2) == null ? "unknown" : rows.getString(2);
                    Map<String, Long> opened = jsonLongMap(rows.getString(3));
                    Map<String, Long> wins = jsonLongMap(rows.getString(4));
                    for (String field : List.of(rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8))) {
                        if (!jsonLongMap(field).isEmpty()) {
                            warnings.add("Phoenix contains non-empty virtual-key/reroll/cooldown state for " + name
                                    + "; that state requires manual review before import.");
                        }
                    }
                    for (Map.Entry<String, Long> win : wins.entrySet()) {
                        if (!rewardToCrate.containsKey(win.getKey())) orphans.merge(win.getKey(), win.getValue(), Long::sum);
                    }
                    players.add(new PhoenixPlayer(playerId, name, opened, wins));
                }
            }
            if (tableExists(queryOnly, "reward_global_stats")) {
                try (ResultSet rows = queryOnly.executeQuery("SELECT COUNT(*) FROM reward_global_stats")) {
                    if (rows.next() && rows.getLong(1) > 0) {
                        warnings.add("Phoenix reward_global_stats is non-empty; global timestamp semantics require manual review.");
                    }
                }
            }
        }
        return new PlayerData(List.copyOf(players), Map.copyOf(orphans));
    }

    private static boolean tableExists(Statement statement, String table) throws Exception {
        String escaped = table.replace("'", "''");
        try (ResultSet rows = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + escaped + "'")) {
            return rows.next();
        }
    }

    private static Map<String, Long> jsonLongMap(String json) {
        if (json == null || json.isBlank() || json.trim().equals("{}")) return Map.of();
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IllegalArgumentException("Expected a flat JSON object in Phoenix player data");
        }
        var result = new LinkedHashMap<String, Long>();
        Matcher matcher = JSON_ENTRY.matcher(trimmed);
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(2));
            if (value < 0) throw new IllegalArgumentException("Phoenix player counters cannot be negative");
            result.put(matcher.group(1), value);
        }
        if (result.isEmpty() && !trimmed.equals("{}")) {
            throw new IllegalArgumentException("Unsupported Phoenix player JSON payload: " + trimmed);
        }
        return Map.copyOf(result);
    }

    private static YamlConfiguration yaml(Path file) throws Exception {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Missing Phoenix file: " + file);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        return yaml;
    }

    private static ItemStack item(YamlConfiguration root, ConfigurationSection spec, boolean normalizeAmount,
                                  String description) {
        if (spec == null) throw new IllegalArgumentException("Missing Phoenix item section: " + description);
        String materialName = required(spec.getString("material"), description + " material");
        ItemStack result;
        if (materialName.toLowerCase(Locale.ROOT).startsWith("custom:")) {
            String customId = materialName.substring("custom:".length());
            String path = "internal-storage.items." + customId;
            Object raw = root.get(path);
            result = raw instanceof ItemStack stack ? stack.clone() : root.getItemStack(path);
            if (result == null || result.getType().isAir()) {
                throw new IllegalArgumentException("Unresolved Phoenix custom item " + materialName + " at " + description);
            }
        } else {
            Material material = Material.matchMaterial(materialName);
            if (material == null || material.isAir() || !material.isItem()) {
                throw new IllegalArgumentException("Invalid Phoenix material " + materialName + " at " + description);
            }
            result = new ItemStack(material);
            if (spec.contains("display-name") || spec.contains("lore") || spec.contains("glow")) {
                result.editMeta(meta -> {
                    if (spec.contains("display-name")) meta.displayName(Text.parse(legacyToMini(spec.getString("display-name", ""))));
                    if (spec.contains("lore")) meta.lore(spec.getStringList("lore").stream()
                            .map(PhoenixFixtureAdapter::legacyToMini).map(Text::parse).toList());
                    if (spec.contains("glow")) meta.setEnchantmentGlintOverride(spec.getBoolean("glow"));
                });
            }
        }
        int amount = normalizeAmount ? 1 : spec.getInt("amount", result.getAmount());
        if (amount < 1 || amount > 6400) {
            throw new IllegalArgumentException("Invalid Phoenix item amount " + amount + " at " + description);
        }
        result.setAmount(amount);
        return result;
    }

    private static String displayName(ItemStack item, String fallbackId) {
        Component display = item.getItemMeta().displayName();
        if (display != null) return Text.serialize(display);
        return "<white>" + pretty(fallbackId) + "</white>";
    }

    private static int basisPoints(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            throw new IllegalArgumentException("Phoenix reward weight is missing or non-numeric");
        }
        try {
            BigDecimal value = new BigDecimal(String.valueOf(raw));
            if (value.signum() < 0) throw new ArithmeticException();
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Phoenix reward weight must have at most two decimal places: " + raw, error);
        }
    }

    private static List<String> sortedNumeric(Set<String> values) {
        Comparator<String> order = Comparator.comparingInt((String value) -> {
            try { return Integer.parseInt(value); }
            catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
        }).thenComparing(Comparator.naturalOrder());
        return values.stream().sorted(order).toList();
    }

    public static String normalizeId(String source) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_");
        while (normalized.startsWith("_")) normalized = normalized.substring(1);
        while (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String keyTarget(String source) {
        String normalized = normalizeId(source);
        return normalized.endsWith("_key") && normalized.length() > 4
                ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private static String mapAnimation(String source) {
        return switch (source == null ? "" : source.toUpperCase(Locale.ROOT)) {
            case "CSGO_GUI", "SPINNER" -> "ROULETTE";
            case "SWIRL", "ROTATING_HEAD" -> "REVEAL";
            default -> "ROULETTE";
        };
    }

    public static String legacyToMini(String source) {
        if (source == null || source.isEmpty()) return "";
        String result = source;
        String[][] replacements = {
                {"&0", "<black>"}, {"&1", "<dark_blue>"}, {"&2", "<dark_green>"}, {"&3", "<dark_aqua>"},
                {"&4", "<dark_red>"}, {"&5", "<dark_purple>"}, {"&6", "<gold>"}, {"&7", "<gray>"},
                {"&8", "<dark_gray>"}, {"&9", "<blue>"}, {"&a", "<green>"}, {"&b", "<aqua>"},
                {"&c", "<red>"}, {"&d", "<light_purple>"}, {"&e", "<yellow>"}, {"&f", "<white>"},
                {"&l", "<bold>"}, {"&o", "<italic>"}, {"&n", "<underlined>"}, {"&m", "<strikethrough>"},
                {"&r", "<reset>"}
        };
        for (String[] replacement : replacements) {
            result = result.replace(replacement[0], replacement[1])
                    .replace(replacement[0].toUpperCase(Locale.ROOT), replacement[1]);
        }
        return result;
    }

    private static String required(String value, String description) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + description);
        return value.trim();
    }

    private static String pretty(String id) {
        String value = id.replace('_', ' ').replace('-', ' ');
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (char c : value.toCharArray()) {
            result.append(upper ? Character.toUpperCase(c) : c);
            upper = Character.isWhitespace(c);
        }
        return result.toString();
    }

    public record Fixture(Path root, List<PhoenixCrate> crates, Map<String, PhoenixKey> keys,
                          List<PhoenixLocation> locations, List<PhoenixPlayer> players,
                          Map<String, String> rewardToCrate, Map<String, Long> orphanRewardWins,
                          List<String> warnings) {
        public Fixture {
            root = Objects.requireNonNull(root).toAbsolutePath().normalize();
            crates = List.copyOf(crates);
            keys = Map.copyOf(keys);
            locations = List.copyOf(locations);
            players = List.copyOf(players);
            rewardToCrate = Map.copyOf(rewardToCrate);
            orphanRewardWins = Map.copyOf(orphanRewardWins);
            warnings = List.copyOf(warnings);
        }
    }

    public record PhoenixKey(String sourceId, String targetId, ItemStack template) {
        public PhoenixKey {
            template = template.clone();
            template.setAmount(1);
        }
        @Override public ItemStack template() { return template.clone(); }
    }

    public record PhoenixCrate(String sourceId, String targetId, String displayName,
                              List<String> description, ItemStack icon, String permission,
                              int keyCost, List<String> sourceKeyIds, int cooldownSeconds,
                              String animation, List<String> hologramLines, List<PhoenixReward> rewards) {
        public PhoenixCrate {
            description = List.copyOf(description);
            icon = icon.clone();
            sourceKeyIds = List.copyOf(sourceKeyIds);
            hologramLines = List.copyOf(hologramLines);
            rewards = List.copyOf(rewards);
        }
        @Override public ItemStack icon() { return icon.clone(); }
    }

    public record PhoenixReward(String sourceId, String displayName, int basisPoints,
                                ItemStack displayItem, List<ItemStack> items, List<String> commands) {
        public PhoenixReward {
            displayItem = displayItem.clone();
            items = items.stream().map(ItemStack::clone).toList();
            commands = List.copyOf(commands);
        }
        @Override public ItemStack displayItem() { return displayItem.clone(); }
        @Override public List<ItemStack> items() { return items.stream().map(ItemStack::clone).toList(); }
    }

    public record PhoenixLocation(String sourceCrateId, String worldName, int x, int y, int z, String face) {}

    public record PhoenixPlayer(UUID playerId, String playerName, Map<String, Long> openedCrates,
                                Map<String, Long> rewardWins) {
        public PhoenixPlayer {
            openedCrates = Map.copyOf(openedCrates);
            rewardWins = Map.copyOf(rewardWins);
        }
    }

    private record PlayerData(List<PhoenixPlayer> players, Map<String, Long> orphanRewardWins) {}
}

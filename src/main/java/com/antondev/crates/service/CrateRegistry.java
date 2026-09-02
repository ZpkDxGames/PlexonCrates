package com.antondev.crates.service;

import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.api.event.CrateDefinitionChangeEvent;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.reward.PityPolicy;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Immutable, validate-before-swap registry for 2.0 crate definitions. */
public final class CrateRegistry {
    public record Snapshot(Map<String, Crate> crates, Map<String, Path> files) {
        public Snapshot {
            crates = Collections.unmodifiableMap(new LinkedHashMap<>(crates));
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private final Path directory;
    private Map<String, Crate> crates;
    private Map<String, Path> files;

    public CrateRegistry(Path directory, Snapshot snapshot) {
        this.directory = directory;
        apply(snapshot);
    }

    public static Snapshot load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Missing crates directory");
        var loaded = new LinkedHashMap<String, Crate>();
        var paths = new LinkedHashMap<String, Path>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration yaml = read(file);
                Crate crate = parse(file, yaml);
                if (loaded.putIfAbsent(crate.id(), crate) != null) throw path(file, "duplicate crate ID " + crate.id());
                paths.put(crate.id(), file);
            }
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No crate files were found in " + directory);
        return new Snapshot(loaded, paths);
    }

    public void apply(Snapshot snapshot) {
        crates = snapshot.crates();
        files = snapshot.files();
    }

    public Snapshot snapshot() {
        return new Snapshot(crates, files);
    }

    public Optional<Crate> find(String id) { return Optional.ofNullable(crates.get(normalize(id))); }

    public List<Crate> ordered() {
        return crates.values().stream().filter(crate -> crate.state().playerVisible()).sorted(Comparator
                .comparingInt(Crate::displayOrder).thenComparing(Crate::id)).toList();
    }

    public List<Crate> orderedAdmin() {
        return crates.values().stream().sorted(Comparator.comparingInt(Crate::displayOrder).thenComparing(Crate::id)).toList();
    }

    public Collection<Crate> all() { return crates.values(); }
    public int rewardCount() { return crates.values().stream().mapToInt(crate -> crate.rewards().size()).sum(); }
    public String ids() { return String.join(", ", orderedAdmin().stream().map(Crate::id).toList()); }

    public long referencesToKey(String keyId) {
        return crates.values().stream().filter(crate -> crate.acceptedKeyIds().contains(normalize(keyId))).count();
    }

    public String serialized(String crateId) throws IOException {
        Path file = files.get(normalize(crateId));
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public Crate createDraft(String rawId, String editor) throws Exception {
        String id = normalize(rawId);
        if (!validId(id)) throw new IllegalArgumentException("Invalid crate ID");
        if (crates.containsKey(id)) throw new IllegalArgumentException("Crate already exists");
        Path file = directory.resolve(id + ".yml").normalize();
        if (!file.getParent().equals(directory.normalize())) throw new IllegalArgumentException("Invalid crate path");
        Instant now = Instant.now();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 2);
        yaml.set("id", id);
        yaml.set("state", "DRAFT");
        yaml.set("display-order", nextDisplayOrder());
        yaml.set("display-name", "<white><bold>" + pretty(id) + " Crate</bold></white>");
        yaml.set("description", List.of("<gray>A new crate draft.</gray>"));
        yaml.set("icon.material", "CHEST");
        yaml.set("icon.name", "<white><bold>" + pretty(id) + " Crate</bold></white>");
        yaml.set("icon.lore", List.of("<yellow>Draft — finish setup before publishing.</yellow>"));
        yaml.set("access.permission", "");
        yaml.set("access.worlds", List.of());
        yaml.set("access.excluded-worlds", List.of());
        yaml.set("keys.cost", 1);
        yaml.set("keys.accepted", List.of());
        yaml.set("opening.cooldown-seconds", 1);
        yaml.set("opening.bulk-enabled", true);
        yaml.set("opening.bulk-maximum", 64);
        yaml.set("opening.animation", "ROULETTE");
        yaml.set("opening.broadcast", "");
        yaml.set("hologram.enabled", true);
        yaml.set("hologram.lines", List.of("<white><bold>" + pretty(id).toUpperCase(Locale.ROOT) + " CRATE</bold></white>",
                "<gray>Left-click to preview</gray>", "<white>Right-click with its key</white>"));
        yaml.set("pity.enabled", false);
        yaml.set("pity.threshold", 0);
        yaml.set("pity.reward-ids", List.of());
        yaml.createSection("rewards");
        yaml.set("audit.created-at", now.toString());
        yaml.set("audit.updated-at", now.toString());
        yaml.set("audit.last-editor", editor);
        Crate parsed = parse(file, yaml);
        AtomicFiles.write(file, yaml.saveToString());
        install(id, file, parsed);
        fireChange(parsed, CrateDefinitionChangeEvent.ChangeType.CREATED);
        return parsed;
    }

    public Crate cloneAsDraft(String sourceId, String rawNewId, String editor) throws Exception {
        Crate source = find(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown source crate"));
        String newId = normalize(rawNewId);
        if (!validId(newId) || crates.containsKey(newId)) throw new IllegalArgumentException("Invalid or existing new crate ID");
        YamlConfiguration yaml = read(files.get(source.id()));
        yaml.set("id", newId);
        yaml.set("state", "DRAFT");
        yaml.set("display-order", nextDisplayOrder());
        yaml.set("audit.created-at", Instant.now().toString());
        yaml.set("audit.updated-at", Instant.now().toString());
        yaml.set("audit.last-editor", editor);
        Path file = directory.resolve(newId + ".yml");
        Crate parsed = parse(file, yaml);
        AtomicFiles.write(file, yaml.saveToString());
        install(newId, file, parsed);
        fireChange(parsed, CrateDefinitionChangeEvent.ChangeType.CREATED);
        return parsed;
    }

    public Crate importAsDraft(Path sourceFile, String rawNewId, String editor) throws Exception {
        String newId = normalize(rawNewId);
        if (!validId(newId) || crates.containsKey(newId)) {
            throw new IllegalArgumentException("Invalid or existing imported crate ID");
        }
        Path source = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".yml")) {
            throw new IllegalArgumentException("Import source must be an existing .yml file");
        }
        YamlConfiguration yaml = read(source);
        yaml.set("id", newId);
        yaml.set("state", "DRAFT");
        yaml.set("display-order", nextDisplayOrder());
        yaml.set("audit.created-at", Instant.now().toString());
        yaml.set("audit.updated-at", Instant.now().toString());
        yaml.set("audit.last-editor", editor);
        Path destination = directory.resolve(newId + ".yml").normalize();
        if (!destination.getParent().equals(directory.normalize())) {
            throw new IllegalArgumentException("Invalid imported crate path");
        }
        Crate parsed = parse(destination, yaml);
        AtomicFiles.write(destination, yaml.saveToString());
        install(newId, destination, parsed);
        fireChange(parsed, CrateDefinitionChangeEvent.ChangeType.CREATED);
        return parsed;
    }

    public Path exportDefinition(String crateId, Path exportDirectory) throws Exception {
        String id = normalize(crateId);
        Path source = files.get(id);
        if (source == null) throw new IllegalArgumentException("Unknown crate");
        Path root = exportDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path destination = root.resolve(id + ".yml").normalize();
        if (!destination.getParent().equals(root)) throw new IllegalArgumentException("Invalid export path");
        AtomicFiles.write(destination, Files.readString(source, StandardCharsets.UTF_8));
        return destination;
    }

    public void setDisplayName(String crateId, Component displayName, String editor) throws Exception {
        mutate(crateId, yaml -> {
            yaml.set("display-name", Text.serialize(displayName));
            touch(yaml, editor);
        });
    }

    public void setDescription(String crateId, List<Component> description, String editor) throws Exception {
        if (description.isEmpty() || description.size() > 12) throw new IllegalArgumentException("Description needs 1-12 lines");
        mutate(crateId, yaml -> {
            yaml.set("description", description.stream().map(Text::serialize).toList());
            touch(yaml, editor);
        });
    }

    public void setDisplayOrder(String crateId, int order, String editor) throws Exception {
        if (order < 0 || order > 1_000_000) throw new IllegalArgumentException("Display order must be 0-1000000");
        mutate(crateId, yaml -> {
            yaml.set("display-order", order);
            touch(yaml, editor);
        });
    }

    public void setIcon(String crateId, ItemStack icon, String editor) throws Exception {
        if (icon == null || icon.getType().isAir()) throw new IllegalArgumentException("Crate icon cannot be empty");
        mutate(crateId, yaml -> {
            yaml.set("icon", null);
            yaml.set("icon.base64", ItemCodec.capture(icon, true));
            touch(yaml, editor);
        });
    }

    public void setAcceptedKeys(String crateId, List<String> keyIds, int cost, String editor) throws Exception {
        List<String> normalized = keyIds.stream().map(CrateRegistry::normalize).distinct().toList();
        if (cost < 0 || cost > 64) throw new IllegalArgumentException("Key cost must be between 0 and 64");
        if (cost > 0 && normalized.isEmpty()) throw new IllegalArgumentException("A keyed crate needs at least one accepted key");
        for (String id : normalized) if (!validId(id)) throw new IllegalArgumentException("Invalid key ID: " + id);
        mutate(crateId, yaml -> {
            yaml.set("keys.accepted", normalized);
            yaml.set("keys.cost", cost);
            touch(yaml, editor);
        });
    }

    public void replaceKeyReferences(String oldKeyId, String newKeyId, String editor) throws Exception {
        String oldId = normalize(oldKeyId);
        String replacement = normalize(newKeyId);
        if (!validId(oldId) || !validId(replacement) || oldId.equals(replacement)) {
            throw new IllegalArgumentException("Choose a different valid replacement key ID");
        }
        for (Crate crate : List.copyOf(crates.values())) {
            if (!crate.acceptedKeyIds().contains(oldId)) continue;
            List<String> next = crate.acceptedKeyIds().stream().map(id -> id.equals(oldId) ? replacement : id)
                    .distinct().toList();
            setAcceptedKeys(crate.id(), next, crate.keyCost(), editor);
        }
    }

    public void setOpening(String crateId, int cooldownSeconds, boolean bulkEnabled, int bulkMaximum,
                           AnimationType animation, String editor) throws Exception {
        if (cooldownSeconds < 0 || cooldownSeconds > 86_400) throw new IllegalArgumentException("Cooldown must be 0-86400 seconds");
        if (bulkMaximum < 1 || bulkMaximum > 10_000) throw new IllegalArgumentException("Bulk maximum must be 1-10000");
        mutate(crateId, yaml -> {
            yaml.set("opening.cooldown-seconds", cooldownSeconds);
            yaml.set("opening.bulk-enabled", bulkEnabled);
            yaml.set("opening.bulk-maximum", bulkMaximum);
            yaml.set("opening.animation", animation.name());
            touch(yaml, editor);
        });
    }

    public void setHologramLines(String crateId, List<Component> lines, String editor) throws Exception {
        if (lines.isEmpty() || lines.size() > 12) throw new IllegalArgumentException("Hologram needs 1-12 lines");
        mutate(crateId, yaml -> {
            yaml.set("hologram.enabled", true);
            yaml.set("hologram.lines", lines.stream().map(Text::serialize).toList());
            touch(yaml, editor);
        });
    }

    public void setAccess(String crateId, String permission, Set<String> worlds, Set<String> excludedWorlds,
                          String editor) throws Exception {
        String normalizedPermission = permission == null ? "" : permission.trim();
        if (normalizedPermission.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Permission cannot contain control characters");
        }
        Set<String> allowed = worlds.stream().map(CrateRegistry::normalize).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> excluded = excludedWorlds.stream().map(CrateRegistry::normalize).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        mutate(crateId, yaml -> {
            yaml.set("access.permission", normalizedPermission);
            yaml.set("access.worlds", allowed.stream().sorted().toList());
            yaml.set("access.excluded-worlds", excluded.stream().sorted().toList());
            touch(yaml, editor);
        });
    }

    public List<String> publishingIssues(String crateId, KeyService keys) {
        Crate crate = find(crateId).orElseThrow(() -> new IllegalArgumentException("Unknown crate"));
        var issues = new ArrayList<String>();
        if (crate.keyCost() > 0) {
            if (crate.acceptedKeyIds().isEmpty()) issues.add("Select at least one key.");
            for (String keyId : crate.acceptedKeyIds()) {
                if (keys.definition(keyId).isEmpty()) issues.add("Unknown key: " + keyId);
                else if (keys.resolve(keyId).isEmpty()) issues.add("Unresolved key: " + keyId);
                if (keys.collisions().contains(keyId)) issues.add("Ambiguous exact key template: " + keyId);
            }
        }
        List<CrateReward> reachable = crate.rewards().values().stream()
                .filter(CrateReward::enabled).filter(CrateReward::hasDelivery).toList();
        if (reachable.isEmpty()) issues.add("Add at least one enabled deliverable reward.");
        double weight = reachable.stream().mapToDouble(CrateReward::weight).sum();
        if (!Double.isFinite(weight) || weight <= 0) issues.add("The enabled reward pool has no positive weight.");
        return List.copyOf(issues);
    }

    public void publish(String crateId, KeyService keys, String editor) throws Exception {
        List<String> issues = publishingIssues(crateId, keys);
        if (!issues.isEmpty()) throw new IllegalStateException(String.join(" ", issues));
        setState(crateId, CrateState.PUBLISHED, editor);
    }

    public void setState(String crateId, CrateState state, String editor) throws Exception {
        mutate(crateId, yaml -> {
            yaml.set("state", state.name());
            touch(yaml, editor);
        });
    }

    public void delete(String crateId) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        Crate deleted = crates.get(id);
        Files.delete(file);
        var nextCrates = new LinkedHashMap<>(crates);
        var nextFiles = new LinkedHashMap<>(files);
        nextCrates.remove(id);
        nextFiles.remove(id);
        crates = Collections.unmodifiableMap(nextCrates);
        files = Collections.unmodifiableMap(nextFiles);
        fireChange(deleted, CrateDefinitionChangeEvent.ChangeType.DELETED);
    }

    public void addCapturedReward(String crateId, String rewardId, double weight, ItemStack held) throws Exception {
        validateRewardInput(rewardId, weight);
        if (held == null || held.getType().isAir()) throw new IllegalArgumentException("Hold the exact reward item first");
        addBundleReward(crateId, rewardId, Text.parse("<white><bold>" + pretty(held.getType().name()) + "</bold></white>"),
                weight, RewardRarity.COMMON, List.of(held), List.of(), 0, 0, 0, "CONSOLE");
    }

    public String addGeneratedCapturedReward(String crateId, ItemStack held, double weight) throws Exception {
        String base = held.getType().name().toLowerCase(Locale.ROOT);
        Crate crate = find(crateId).orElseThrow(() -> new IllegalArgumentException("Unknown crate"));
        String id = base;
        int number = 2;
        while (crate.rewards().containsKey(id)) id = base + "_" + number++;
        addCapturedReward(crateId, id, weight, held);
        return id;
    }

    public void addCommandReward(String crateId, String rewardId, double weight, String command) throws Exception {
        String normalized = command == null ? "" : command.trim();
        if (normalized.startsWith("/")) throw new IllegalArgumentException("Reward commands must not begin with /");
        if (normalized.isBlank() || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException("Command cannot be empty or contain newlines");
        }
        addBundleReward(crateId, rewardId, Text.parse("<gold><bold>Command Reward</bold></gold>"), weight,
                RewardRarity.COMMON, List.of(), List.of(normalized), 0, 0, 0, "CONSOLE");
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double weight,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, String editor) throws Exception {
        addBundleReward(crateId, rewardId, displayName, weight, rarity, items, commands, experiencePoints,
                experienceLevels, money, RewardLimits.unlimited(), "", "", "", "", editor);
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double weight,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, RewardLimits limits,
                                String requiredPermission, String blockedPermission, String personalMessage,
                                String broadcast, String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, weight, true, rarity, null, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                RewardPresentation.none(), personalMessage, broadcast, editor, false);
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double weight,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, RewardLimits limits,
                                String requiredPermission, String blockedPermission, RewardPresentation presentation,
                                String personalMessage, String broadcast, String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, weight, true, rarity, null, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                presentation, personalMessage, broadcast, editor, false);
    }

    public void updateBundleReward(String crateId, String rewardId, Component displayName, double weight,
                                   boolean enabled, RewardRarity rarity, ItemStack displayItem,
                                   List<ItemStack> items, List<String> commands, int experiencePoints,
                                   int experienceLevels, double money, RewardLimits limits,
                                   String requiredPermission, String blockedPermission,
                                   RewardPresentation presentation, String personalMessage, String broadcast,
                                   String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, weight, enabled, rarity, displayItem, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                presentation, personalMessage, broadcast, editor, true);
    }

    private void saveBundleReward(String crateId, String rewardId, Component displayName, double weight,
                                  boolean enabled, RewardRarity rarity, ItemStack displayItem,
                                  List<ItemStack> items, List<String> commands, int experiencePoints,
                                  int experienceLevels, double money, RewardLimits limits,
                                  String requiredPermission, String blockedPermission,
                                  RewardPresentation presentation, String personalMessage, String broadcast,
                                  String editor, boolean updating) throws Exception {
        validateRewardInput(rewardId, weight);
        if (items.isEmpty() && commands.isEmpty() && experiencePoints <= 0 && experienceLevels <= 0 && money <= 0) {
            throw new IllegalArgumentException("A reward bundle needs at least one delivery action");
        }
        for (String command : commands) {
            if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r")) {
                throw new IllegalArgumentException("Reward commands must be non-empty, single-line, and omit /");
            }
        }
        Text.parse(personalMessage);
        Text.parse(broadcast);
        Text.parse(presentation.title());
        Text.parse(presentation.subtitle());
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (updating && !yaml.contains(path)) throw new IllegalArgumentException("Reward no longer exists");
            if (!updating && yaml.contains(path)) throw new IllegalArgumentException("Reward already exists");
            yaml.set(path + ".enabled", enabled);
            yaml.set(path + ".display-name", Text.serialize(displayName));
            yaml.set(path + ".rarity", rarity.name());
            yaml.set(path + ".weight", weight);
            yaml.set(path + ".display", null);
            ItemStack display = displayItem != null ? displayItem
                    : items.isEmpty() ? new ItemStack(Material.COMMAND_BLOCK) : items.getFirst();
            yaml.set(path + ".display.base64", ItemCodec.capture(display, false));
            yaml.set(path + ".items", null);
            int index = 1;
            for (ItemStack item : items) yaml.set(path + ".items.item_" + index++ + ".base64", ItemCodec.capture(item, false));
            yaml.set(path + ".commands", commands);
            yaml.set(path + ".experience.points", experiencePoints);
            yaml.set(path + ".experience.levels", experienceLevels);
            yaml.set(path + ".money.amount", money);
            yaml.set(path + ".limits.player-lifetime", limits.playerLifetime());
            yaml.set(path + ".limits.player-window", limits.playerWindow());
            yaml.set(path + ".limits.player-window-seconds", limits.playerWindowSeconds());
            yaml.set(path + ".limits.global-lifetime", limits.globalLifetime());
            yaml.set(path + ".limits.global-window", limits.globalWindow());
            yaml.set(path + ".limits.global-window-seconds", limits.globalWindowSeconds());
            yaml.set(path + ".limits.cooldown-seconds", limits.cooldownSeconds());
            yaml.set(path + ".required-permission", requiredPermission == null ? "" : requiredPermission.trim());
            yaml.set(path + ".blocked-permission", blockedPermission == null ? "" : blockedPermission.trim());
            yaml.set(path + ".presentation.title", presentation.title());
            yaml.set(path + ".presentation.subtitle", presentation.subtitle());
            yaml.set(path + ".presentation.sound", presentation.sound());
            yaml.set(path + ".presentation.sound-volume", presentation.soundVolume());
            yaml.set(path + ".presentation.sound-pitch", presentation.soundPitch());
            yaml.set(path + ".presentation.firework", presentation.firework());
            yaml.set(path + ".personal-message", personalMessage == null ? "" : personalMessage);
            yaml.set(path + ".broadcast", broadcast == null ? "" : broadcast);
            touch(yaml, editor);
        });
    }

    public void moveReward(String crateId, String rewardId, int requestedIndex, String editor) throws Exception {
        mutate(crateId, yaml -> {
            ConfigurationSection section = yaml.getConfigurationSection("rewards");
            if (section == null) throw new IllegalArgumentException("Crate has no reward section");
            String id = normalize(rewardId);
            List<String> order = new ArrayList<>(section.getKeys(false));
            if (!order.remove(id)) throw new IllegalArgumentException("Reward not found");
            int index = Math.max(0, Math.min(requestedIndex, order.size()));
            order.add(index, id);

            var values = new LinkedHashMap<String, Map<String, Object>>();
            for (String current : section.getKeys(false)) {
                ConfigurationSection reward = section.getConfigurationSection(current);
                if (reward == null) throw new IllegalArgumentException("Invalid reward section: " + current);
                var leaves = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> entry : reward.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) leaves.put(entry.getKey(), entry.getValue());
                }
                values.put(current, leaves);
            }
            yaml.set("rewards", null);
            yaml.createSection("rewards");
            for (String current : order) {
                for (Map.Entry<String, Object> entry : values.get(current).entrySet()) {
                    yaml.set("rewards." + current + "." + entry.getKey(), entry.getValue());
                }
            }
            touch(yaml, editor);
        });
    }

    public void copyReward(String sourceCrateId, String rewardId, String targetCrateId,
                           String rawNewRewardId, String editor) throws Exception {
        String sourceId = normalize(sourceCrateId);
        String targetId = normalize(targetCrateId);
        String sourceReward = normalize(rewardId);
        String newReward = normalize(rawNewRewardId);
        if (!validId(newReward)) throw new IllegalArgumentException("Invalid copied reward ID");
        Path sourceFile = files.get(sourceId);
        Path targetFile = files.get(targetId);
        if (sourceFile == null || targetFile == null) throw new IllegalArgumentException("Unknown source or target crate");
        YamlConfiguration source = read(sourceFile);
        ConfigurationSection section = source.getConfigurationSection("rewards." + sourceReward);
        if (section == null) throw new IllegalArgumentException("Unknown source reward");
        mutate(targetId, yaml -> {
            String targetPath = "rewards." + newReward;
            if (yaml.contains(targetPath)) throw new IllegalArgumentException("Target reward already exists");
            for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection)) {
                    yaml.set(targetPath + "." + entry.getKey(), entry.getValue());
                }
            }
            touch(yaml, editor);
        });
    }

    public void removeReward(String crateId, String rewardId) throws Exception {
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path, null);
            touch(yaml, "CONSOLE");
        });
    }

    public void setWeight(String crateId, String rewardId, double weight) throws Exception {
        if (!Double.isFinite(weight) || weight <= 0 || weight > 1_000_000_000) {
            throw new IllegalArgumentException("Weight must be positive and finite");
        }
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path + ".weight", weight);
            touch(yaml, "CONSOLE");
        });
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    private void mutate(String crateId, ThrowingConsumer<YamlConfiguration> change) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        Crate previous = crates.get(id);
        YamlConfiguration yaml = read(file);
        change.accept(yaml);
        Crate parsed = parse(file, yaml);
        AtomicFiles.write(file, yaml.saveToString());
        install(id, file, parsed);
        fireChange(parsed, changeType(previous, parsed));
    }

    private void install(String id, Path file, Crate parsed) {
        var nextCrates = new LinkedHashMap<>(crates);
        var nextFiles = new LinkedHashMap<>(files);
        nextCrates.put(id, parsed);
        nextFiles.put(id, file);
        crates = Collections.unmodifiableMap(nextCrates);
        files = Collections.unmodifiableMap(nextFiles);
    }

    private static CrateDefinitionChangeEvent.ChangeType changeType(Crate previous, Crate current) {
        if (previous == null || previous.state() == current.state()) {
            return CrateDefinitionChangeEvent.ChangeType.UPDATED;
        }
        return switch (current.state()) {
            case PUBLISHED -> CrateDefinitionChangeEvent.ChangeType.PUBLISHED;
            case DISABLED -> CrateDefinitionChangeEvent.ChangeType.DISABLED;
            case ARCHIVED -> CrateDefinitionChangeEvent.ChangeType.ARCHIVED;
            case DRAFT -> CrateDefinitionChangeEvent.ChangeType.UPDATED;
        };
    }

    private static void fireChange(Crate crate, CrateDefinitionChangeEvent.ChangeType type) {
        if (crate != null && Bukkit.getServer() != null && Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(new CrateDefinitionChangeEvent(crate, type));
        }
    }

    private int nextDisplayOrder() {
        return crates.values().stream().mapToInt(Crate::displayOrder).max().orElse(0) + 10;
    }

    private static Crate parse(Path file, YamlConfiguration yaml) {
        if (yaml.getInt("config-version") != 2) throw path(file, "unsupported config-version; expected 2");
        String id = normalize(yaml.getString("id", file.getFileName().toString().replaceFirst("\\.yml$", "")));
        if (!validId(id)) throw path(file, "invalid crate ID");
        CrateState state = enumValue(CrateState.class, yaml.getString("state", "DRAFT"), file, "state");
        int displayOrder = integer(yaml.get("display-order"), file, "display-order", 0, 1_000_000);
        Component display = Text.parse(required(yaml, file, "display-name"));
        var description = yaml.getStringList("description").stream().map(Text::parse).toList();
        ItemStack icon = ItemCodec.configured(yaml, "icon");
        String permission = yaml.getString("access.permission", "").trim();
        Set<String> worlds = lower(yaml.getStringList("access.worlds"));
        Set<String> excludedWorlds = lower(yaml.getStringList("access.excluded-worlds"));
        List<String> acceptedKeys = yaml.getStringList("keys.accepted").stream().map(CrateRegistry::normalize).distinct().toList();
        for (String key : acceptedKeys) if (!validId(key)) throw path(file, "keys.accepted contains invalid ID " + key);
        int keyCost = integer(yaml.get("keys.cost"), file, "keys.cost", 0, 64);
        if (state == CrateState.PUBLISHED && keyCost > 0 && acceptedKeys.isEmpty()) {
            throw path(file, "published crate needs an accepted key");
        }
        int cooldown = integer(yaml.get("opening.cooldown-seconds"), file, "opening.cooldown-seconds", 0, 86_400);
        boolean bulkEnabled = yaml.getBoolean("opening.bulk-enabled", true);
        int bulkMaximum = integer(yaml.get("opening.bulk-maximum"), file, "opening.bulk-maximum", 1, 10_000);
        AnimationType animation = enumValue(AnimationType.class, yaml.getString("opening.animation", "ROULETTE"), file, "opening.animation");
        String crateBroadcast = yaml.getString("opening.broadcast", yaml.getString("broadcast", ""));
        Text.parse(crateBroadcast);

        var hologram = new ArrayList<Component>();
        for (String line : yaml.getStringList("hologram.lines")) hologram.add(Text.parse(line));
        if (hologram.isEmpty()) hologram.add(display);

        boolean pityEnabled = yaml.getBoolean("pity.enabled", false);
        int pityThreshold = integer(yaml.get("pity.threshold", 0), file, "pity.threshold", 0, 1_000_000);
        Set<String> pityRewards = new LinkedHashSet<>(yaml.getStringList("pity.reward-ids").stream().map(CrateRegistry::normalize).toList());
        RewardRarity pityRarity = null;
        String rawPityRarity = yaml.getString("pity.rarity", "").trim();
        if (!rawPityRarity.isEmpty()) pityRarity = enumValue(RewardRarity.class, rawPityRarity, file, "pity.rarity");
        PityPolicy pity = new PityPolicy(pityEnabled, pityThreshold, pityRewards, pityRarity,
                yaml.getBoolean("pity.administrative-openings-count", false));

        var rewards = parseRewards(file, yaml);
        for (String pityReward : pityRewards) if (!rewards.containsKey(pityReward)) {
            throw path(file, "pity references unknown reward " + pityReward);
        }
        if (state == CrateState.PUBLISHED && rewards.values().stream().noneMatch(reward -> reward.enabled() && reward.hasDelivery())) {
            throw path(file, "published crate needs at least one enabled deliverable reward");
        }
        return new Crate(id, state, displayOrder, display, description, icon, permission, worlds, excludedWorlds,
                acceptedKeys, keyCost, cooldown, bulkEnabled, bulkMaximum, animation, hologram, crateBroadcast, pity, rewards);
    }

    private static Map<String, CrateReward> parseRewards(Path file, YamlConfiguration yaml) {
        ConfigurationSection rewardsSection = yaml.getConfigurationSection("rewards");
        if (rewardsSection == null) throw path(file, "missing rewards section");
        var rewards = new LinkedHashMap<String, CrateReward>();
        for (String rawRewardId : rewardsSection.getKeys(false)) {
            String rewardId = normalize(rawRewardId);
            if (!validId(rewardId) || !rewardId.equals(rawRewardId)) throw path(file, "invalid reward ID " + rawRewardId);
            String path = "rewards." + rewardId;
            ConfigurationSection rewardSection = yaml.getConfigurationSection(path);
            if (rewardSection == null) throw path(file, path + " must be a section");
            double weight = number(rewardSection.get("weight"), file, path + ".weight", 0, 1_000_000_000, false);
            Component rewardName = Text.parse(required(yaml, file, path + ".display-name"));
            RewardRarity rarity = enumValue(RewardRarity.class, yaml.getString(path + ".rarity", "COMMON"), file, path + ".rarity");
            var items = new ArrayList<ItemStack>();
            ConfigurationSection itemSection = yaml.getConfigurationSection(path + ".items");
            if (itemSection != null) for (String itemId : itemSection.getKeys(false)) {
                items.addAll(ItemCodec.readMany(itemSection.getConfigurationSection(itemId)));
            }
            List<String> commands = yaml.getStringList(path + ".commands");
            for (String command : commands) {
                if (command.isBlank() || command.startsWith("/") || command.contains("\n") || command.contains("\r")) {
                    throw path(file, path + ".commands entries must be non-empty, single-line, and omit /");
                }
            }
            int points = integer(yaml.get(path + ".experience.points", 0), file, path + ".experience.points", 0, 1_000_000_000);
            int levels = integer(yaml.get(path + ".experience.levels", 0), file, path + ".experience.levels", 0, 1_000_000);
            double money = number(yaml.get(path + ".money.amount", 0.0), file, path + ".money.amount", 0, 1_000_000_000_000.0, true);
            if (items.isEmpty() && commands.isEmpty() && points == 0 && levels == 0 && money == 0) {
                throw path(file, path + " must contain an item, command, experience, or money");
            }

            ItemStack rewardDisplay;
            ConfigurationSection displaySection = yaml.getConfigurationSection(path + ".display");
            if (displaySection != null) rewardDisplay = ItemCodec.read(displaySection);
            else if (!items.isEmpty()) rewardDisplay = items.getFirst().clone();
            else rewardDisplay = new ItemStack(Material.COMMAND_BLOCK);
            rewardDisplay.setAmount(Math.max(1, Math.min(rewardDisplay.getAmount(), rewardDisplay.getMaxStackSize())));
            rewardDisplay.editMeta(meta -> meta.displayName(rewardName.decoration(TextDecoration.ITALIC, false)));

            RewardLimits limits = new RewardLimits(
                    nonNegativeLong(yaml.get(path + ".limits.player-lifetime", 0), file, path + ".limits.player-lifetime"),
                    nonNegativeLong(yaml.get(path + ".limits.player-window", 0), file, path + ".limits.player-window"),
                    nonNegativeLong(yaml.get(path + ".limits.player-window-seconds", 0), file, path + ".limits.player-window-seconds"),
                    nonNegativeLong(yaml.get(path + ".limits.global-lifetime", 0), file, path + ".limits.global-lifetime"),
                    nonNegativeLong(yaml.get(path + ".limits.global-window", 0), file, path + ".limits.global-window"),
                    nonNegativeLong(yaml.get(path + ".limits.global-window-seconds", 0), file, path + ".limits.global-window-seconds"),
                    nonNegativeLong(yaml.get(path + ".limits.cooldown-seconds", 0), file, path + ".limits.cooldown-seconds"));
            String personal = yaml.getString(path + ".personal-message", "");
            String broadcast = yaml.getString(path + ".broadcast", "");
            RewardPresentation presentation = new RewardPresentation(
                    yaml.getString(path + ".presentation.title", ""),
                    yaml.getString(path + ".presentation.subtitle", ""),
                    yaml.getString(path + ".presentation.sound", ""),
                    (float) number(yaml.get(path + ".presentation.sound-volume", 1.0), file,
                            path + ".presentation.sound-volume", 0, 10, true),
                    (float) number(yaml.get(path + ".presentation.sound-pitch", 1.0), file,
                            path + ".presentation.sound-pitch", 0, 2, false),
                    yaml.getBoolean(path + ".presentation.firework", false));
            Text.parse(personal);
            Text.parse(broadcast);
            Text.parse(presentation.title());
            Text.parse(presentation.subtitle());
            rewards.put(rewardId, new CrateReward(rewardId, rewardName, weight,
                    yaml.getBoolean(path + ".enabled", true), rarity, rewardDisplay, items, commands, points, levels,
                    money, yaml.getString(path + ".required-permission", ""),
                    yaml.getString(path + ".blocked-permission", ""), limits, presentation, personal, broadcast));
        }
        return rewards;
    }

    private static YamlConfiguration read(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw path(file, "invalid YAML", error); }
        return yaml;
    }

    private static void validateRewardInput(String id, double weight) {
        if (!validId(normalize(id))) throw new IllegalArgumentException("Invalid reward ID");
        if (!Double.isFinite(weight) || weight <= 0 || weight > 1_000_000_000) {
            throw new IllegalArgumentException("Weight must be positive and finite");
        }
    }

    private static String required(YamlConfiguration yaml, Path file, String path) {
        String value = yaml.getString(path, "").trim();
        if (value.isEmpty()) throw path(file, "missing value " + path);
        return value;
    }

    private static int integer(Object raw, Path file, String path, int minimum, int maximum) {
        if (!(raw instanceof Number number)) throw path(file, path + " must be numeric");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < minimum || value > maximum) {
            throw path(file, path + " must be a whole number between " + minimum + " and " + maximum);
        }
        return (int) value;
    }

    private static long nonNegativeLong(Object raw, Path file, String path) {
        if (!(raw instanceof Number number)) throw path(file, path + " must be numeric");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > Long.MAX_VALUE) {
            throw path(file, path + " must be a non-negative whole number");
        }
        return number.longValue();
    }

    private static double number(Object raw, Path file, String path, double minimum, double maximum, boolean zeroAllowed) {
        if (!(raw instanceof Number number)) throw path(file, path + " must be numeric");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < minimum || value > maximum || (!zeroAllowed && value == 0)) {
            throw path(file, path + " is outside its valid range");
        }
        return value;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, Path file, String path) {
        try { return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException error) { throw path(file, "invalid " + path + ": " + raw, error); }
    }

    private static Set<String> lower(List<String> values) {
        return values.stream().map(CrateRegistry::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void touch(YamlConfiguration yaml, String editor) {
        yaml.set("audit.updated-at", Instant.now().toString());
        yaml.set("audit.last-editor", editor);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String pretty(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String word : value.split("[_-]")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static IllegalArgumentException path(Path file, String message) {
        return new IllegalArgumentException(file.getFileName() + ": " + message);
    }

    private static IllegalArgumentException path(Path file, String message, Throwable cause) {
        return new IllegalArgumentException(file.getFileName() + ": " + message, cause);
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}

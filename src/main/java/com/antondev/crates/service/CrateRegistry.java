package com.antondev.crates.service;

import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class CrateRegistry {
    public record Snapshot(Map<String, Crate> crates, Map<String, Path> files) {}

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final List<String> DEFAULT_ORDER = List.of("basic", "rare", "epic", "legendary");
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
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
                } catch (Exception error) {
                    throw new IllegalArgumentException(file.getFileName() + ": invalid YAML", error);
                }
                Crate crate = parse(file, yaml);
                if (loaded.putIfAbsent(crate.id(), crate) != null) throw new IllegalArgumentException("Duplicate crate ID: " + crate.id());
                paths.put(crate.id(), file);
            }
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No crate files were found in " + directory);
        return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(loaded)),
                Collections.unmodifiableMap(new LinkedHashMap<>(paths)));
    }

    public void apply(Snapshot snapshot) {
        crates = snapshot.crates();
        files = snapshot.files();
    }

    public Optional<Crate> find(String id) {
        return Optional.ofNullable(crates.get(normalize(id)));
    }

    public List<Crate> ordered() {
        return crates.values().stream().sorted(Comparator
                .comparingInt((Crate crate) -> {
                    int index = DEFAULT_ORDER.indexOf(crate.id());
                    return index < 0 ? Integer.MAX_VALUE : index;
                })
                .thenComparing(Crate::id)).toList();
    }

    public Collection<Crate> all() {
        return crates.values();
    }

    public int rewardCount() {
        return crates.values().stream().mapToInt(crate -> crate.rewards().size()).sum();
    }

    public String ids() {
        return String.join(", ", ordered().stream().map(Crate::id).toList());
    }

    public void addCapturedReward(String crateId, String rewardId, double weight, ItemStack held) throws Exception {
        validateRewardInput(rewardId, weight);
        if (held == null || held.getType().isAir()) throw new IllegalArgumentException("Hold the exact reward item first");
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (yaml.contains(path)) throw new IllegalArgumentException("Reward already exists");
            yaml.set(path + ".enabled", true);
            yaml.set(path + ".display-name", "<white><bold>" + pretty(held.getType()) + "</bold></white>");
            yaml.set(path + ".weight", weight);
            yaml.set(path + ".display.base64", ItemCodec.capture(held, false));
            yaml.set(path + ".items.captured.base64", ItemCodec.capture(held, false));
            yaml.set(path + ".commands", List.of());
        });
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
        validateRewardInput(rewardId, weight);
        String normalizedCommand = command.startsWith("/") ? command.substring(1) : command;
        if (normalizedCommand.isBlank()) throw new IllegalArgumentException("Command cannot be empty");
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (yaml.contains(path)) throw new IllegalArgumentException("Reward already exists");
            yaml.set(path + ".enabled", true);
            yaml.set(path + ".display-name", "<gold><bold>Command Reward</bold></gold>");
            yaml.set(path + ".weight", weight);
            yaml.set(path + ".display.material", "COMMAND_BLOCK");
            yaml.set(path + ".display.amount", 1);
            yaml.createSection(path + ".items");
            yaml.set(path + ".commands", List.of(normalizedCommand));
        });
    }

    public void removeReward(String crateId, String rewardId) throws Exception {
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path, null);
        });
    }

    public void setWeight(String crateId, String rewardId, double weight) throws Exception {
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Weight must be greater than zero");
        mutate(crateId, yaml -> {
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path + ".weight", weight);
        });
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    private void mutate(String crateId, ThrowingConsumer<YamlConfiguration> change) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        change.accept(yaml);
        Crate parsed = parse(file, yaml);
        AtomicFiles.write(file, yaml.saveToString());
        var next = new LinkedHashMap<>(crates);
        next.put(id, parsed);
        crates = Collections.unmodifiableMap(new LinkedHashMap<>(next));
    }

    private static Crate parse(Path file, YamlConfiguration yaml) {
        if (yaml.getInt("config-version") != 1) throw new IllegalArgumentException(file.getFileName() + ": unsupported config-version");
        String id = normalize(yaml.getString("id", file.getFileName().toString().replaceFirst("\\.yml$", "")));
        if (!validId(id)) throw new IllegalArgumentException(file.getFileName() + ": invalid crate ID");
        String keyId = normalize(required(yaml, "key-id"));
        if (!validId(keyId)) throw new IllegalArgumentException(file.getFileName() + ": invalid key-id");
        String displayText = required(yaml, "display-name");
        Component display = Text.parse(displayText);
        ItemStack icon = ItemCodec.configured(yaml, "icon");
        int cooldown = yaml.getInt("open-cooldown-seconds");
        if (cooldown < 0 || cooldown > 86_400) throw new IllegalArgumentException(id + ": open-cooldown-seconds must be 0-86400");

        var hologram = new ArrayList<Component>();
        for (String line : yaml.getStringList("hologram.lines")) hologram.add(Text.parse(line));
        if (hologram.isEmpty()) hologram.add(display);
        String crateBroadcast = yaml.getString("broadcast", "");
        Text.parse(crateBroadcast);

        ConfigurationSection rewardsSection = yaml.getConfigurationSection("rewards");
        if (rewardsSection == null) throw new IllegalArgumentException(id + ": missing rewards section");
        var rewards = new LinkedHashMap<String, CrateReward>();
        for (String rawRewardId : rewardsSection.getKeys(false)) {
            String rewardId = normalize(rawRewardId);
            if (!validId(rewardId) || !rewardId.equals(rawRewardId)) throw new IllegalArgumentException(id + ": invalid reward ID " + rawRewardId);
            String path = "rewards." + rewardId;
            ConfigurationSection rewardSection = yaml.getConfigurationSection(path);
            if (rewardSection == null) throw new IllegalArgumentException(path + " must be a section");
            double weight = number(rewardSection.get("weight"), path + ".weight");
            String rewardName = required(yaml, path + ".display-name");
            Component rewardDisplayName = Text.parse(rewardName);
            var items = new ArrayList<ItemStack>();
            ConfigurationSection itemSection = yaml.getConfigurationSection(path + ".items");
            if (itemSection != null) {
                for (String itemId : itemSection.getKeys(false)) {
                    items.add(ItemCodec.read(itemSection.getConfigurationSection(itemId)));
                }
            }
            List<String> commands = yaml.getStringList(path + ".commands").stream()
                    .map(command -> command.startsWith("/") ? command.substring(1) : command)
                    .filter(command -> !command.isBlank()).toList();
            if (items.isEmpty() && commands.isEmpty()) throw new IllegalArgumentException(path + " must contain at least one item or command");

            ItemStack rewardDisplay;
            ConfigurationSection displaySection = yaml.getConfigurationSection(path + ".display");
            if (displaySection != null) rewardDisplay = ItemCodec.read(displaySection);
            else if (!items.isEmpty()) rewardDisplay = items.getFirst().clone();
            else rewardDisplay = new ItemStack(Material.COMMAND_BLOCK);
            rewardDisplay.setAmount(Math.max(1, Math.min(rewardDisplay.getAmount(), rewardDisplay.getMaxStackSize())));
            rewardDisplay.editMeta(meta -> meta.displayName(rewardDisplayName.decoration(TextDecoration.ITALIC, false)));

            String requiredPermission = yaml.getString(path + ".required-permission", "");
            String blockedPermission = yaml.getString(path + ".blocked-permission", "");
            String broadcast = yaml.getString(path + ".broadcast", "");
            Text.parse(broadcast);
            rewards.put(rewardId, new CrateReward(rewardId, rewardDisplayName, weight,
                    yaml.getBoolean(path + ".enabled", true), rewardDisplay, items, commands,
                    requiredPermission, blockedPermission, broadcast));
        }
        return new Crate(id, yaml.getBoolean("enabled"), display, keyId, yaml.getString("permission", ""),
                cooldown, icon, hologram, crateBroadcast, rewards);
    }

    private static void validateRewardInput(String id, double weight) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid reward ID");
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("Weight must be greater than zero");
    }

    private static double number(Object raw, String path) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException(path + " must be numeric");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value <= 0 || value > 1_000_000_000) {
            throw new IllegalArgumentException(path + " must be greater than zero and finite");
        }
        return value;
    }

    private static String required(YamlConfiguration yaml, String path) {
        String value = yaml.getString(path, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing value: " + path);
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String pretty(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}

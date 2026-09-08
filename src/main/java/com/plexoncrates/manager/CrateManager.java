package com.plexoncrates.manager;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.CrateLocation;
import com.plexoncrates.crate.CrateValidator;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.crate.RewardActionType;
import com.plexoncrates.util.ColorUtil;
import com.plexoncrates.util.ItemCodec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class CrateManager {
    private static final List<String> ANIMATIONS = List.of("CSGO", "WHEEL");
    private static final List<String> IDLE_EFFECTS = List.of("HELIX", "CLOUD", "FOUNTAIN", "NONE");

    private final PlexonCrates plugin;
    private final ExecutorService executor;
    private final File file;
    private final NamespacedKey crateBlockKey;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> locationIndex = new LinkedHashMap<>();
    private final Object persistenceMonitor = new Object();
    private CompletableFuture<Void> saveTail = CompletableFuture.completedFuture(null);

    public CrateManager(PlexonCrates plugin, ExecutorService executor) {
        this.plugin = plugin;
        this.executor = executor;
        this.file = new File(plugin.getDataFolder(), "crates.yml");
        this.crateBlockKey = new NamespacedKey(plugin, "crate_id");
    }

    public void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("crates");
        Map<String, Crate> loaded = new LinkedHashMap<>();
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                try {
                    Crate crate = parseCrate(rawId, root.getConfigurationSection(rawId));
                    loaded.put(crate.id(), crate);
                } catch (Exception error) {
                    plugin.getLogger().log(Level.SEVERE, "Skipping invalid crate definition " + rawId, error);
                }
            }
        }

        lock.writeLock().lock();
        try {
            crates.clear();
            crates.putAll(loaded);
            rebuildLocationIndex();
        } finally {
            lock.writeLock().unlock();
        }
        plugin.getLogger().info("Loaded " + loaded.size() + " crate definition(s).");
    }

    public Collection<Crate> all() {
        lock.readLock().lock();
        try {
            return crates.values().stream().map(Crate::copy).sorted(Comparator.comparing(Crate::id)).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Crate> find(String id) {
        lock.readLock().lock();
        try {
            Crate crate = crates.get(normalize(id));
            return crate == null ? Optional.empty() : Optional.of(crate.copy());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Crate> at(Block block) {
        String id = null;
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            id = tile.getPersistentDataContainer().get(crateBlockKey, PersistentDataType.STRING);
        }
        lock.readLock().lock();
        try {
            if (id == null || !crates.containsKey(normalize(id))) {
                id = locationIndex.get(CrateLocation.of(block).key());
            }
            Crate crate = id == null ? null : crates.get(normalize(id));
            return crate == null ? Optional.empty() : Optional.of(crate.copy());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Creates a disabled draft definition. New crates never become live merely by closing a GUI. */
    public Crate create(String rawId) {
        String id = normalizeId(rawId);
        lock.writeLock().lock();
        try {
            if (crates.containsKey(id)) throw new IllegalArgumentException("Crate already exists: " + id);
            ItemStack icon = named(new ItemStack(Material.CHEST), "&6" + title(id) + " Crate");
            ItemStack key = named(new ItemStack(Material.TRIPWIRE_HOOK), "&6" + title(id) + " Key");
            Crate crate = new Crate(id, false, "&6&l" + title(id) + " Crate",
                    List.of("&7New PlexonCrates draft definition."), icon, "&6" + title(id) + " Key", key,
                    "CSGO", "HELIX", List.of(), List.of());
            crates.put(id, crate);
            saveAsync();
            return crate.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Crate createGenerated() {
        String id;
        do {
            id = "crate_" + Long.toString(System.currentTimeMillis(), 36);
        } while (find(id).isPresent());
        return create(id);
    }

    /**
     * Atomically installs a batch of externally migrated crate definitions as disabled review drafts.
     * Existing crate IDs or occupied locations abort the complete batch before in-memory mutation.
     */
    public CompletableFuture<Void> installImportedCrates(Collection<Crate> imported) {
        if (imported == null || imported.isEmpty()) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> save;
        lock.writeLock().lock();
        try {
            Map<String, Crate> prepared = new LinkedHashMap<>();
            Map<String, String> occupied = new LinkedHashMap<>(locationIndex);

            for (Crate source : imported) {
                if (source == null) throw new IllegalArgumentException("Imported crate cannot be null");
                Crate crate = source.copy();
                crate.setEnabled(false);
                String id = normalizeId(crate.id());
                if (crates.containsKey(id) || prepared.containsKey(id)) {
                    throw new IllegalStateException("Imported crate ID already exists: " + id);
                }
                // Validate all exact item payloads now, while allowing draft-level missing/zero-weight
                // concerns to remain visible for administrator review.
                CrateValidator.Result validation = CrateValidator.validate(crate);
                for (CrateValidator.Issue issue : validation.issues()) {
                    if (issue.code().startsWith("crate.icon") || issue.code().startsWith("crate.key")
                            || issue.code().startsWith("reward.display") || issue.code().startsWith("reward.action")) {
                        throw new IllegalStateException("Imported crate " + id + " has invalid exact item data: "
                                + issue.message());
                    }
                }
                for (CrateLocation location : crate.locations()) {
                    String existing = occupied.putIfAbsent(location.key(), id);
                    if (existing != null && !existing.equals(id)) {
                        throw new IllegalStateException("Imported location " + location.key()
                                + " is already assigned to " + existing);
                    }
                }
                prepared.put(id, crate);
            }

            crates.putAll(prepared);
            rebuildLocationIndex();
            save = saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
        return save;
    }

    public Reward addCapturedReward(String crateId, ItemStack source, int weight) {
        if (source == null || source.getType().isAir()) throw new IllegalArgumentException("A real item is required");
        // Hard gate: accept only a native Paper snapshot that survives an exact byte round-trip.
        ItemStack captured = ItemCodec.snapshot(source).toItemStack();
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(crateId);
            String id = nextRewardId(crate);
            List<RewardAction> actions = List.of(new RewardAction(RewardActionType.ITEM, "", null));
            Reward reward = new Reward(id, true, Math.max(1, weight), captured, actions);
            crate.putReward(reward);
            try {
                saveAsync();
            } catch (RuntimeException persistenceFailure) {
                crate.removeReward(id);
                throw persistenceFailure;
            }
            return reward.copy();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeReward(String crateId, String rewardId) {
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(crateId);
            if (!crate.removeReward(rewardId)) throw new IllegalArgumentException("Unknown reward: " + rewardId);
            saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setRewardWeight(String crateId, String rewardId, int weight) {
        lock.writeLock().lock();
        try {
            Reward reward = requireMutable(crateId).reward(rewardId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown reward: " + rewardId));
            reward.setWeight(Math.max(0, weight));
            saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void adjustRewardWeight(String crateId, String rewardId, int delta) {
        lock.writeLock().lock();
        try {
            Reward reward = requireMutable(crateId).reward(rewardId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown reward: " + rewardId));
            reward.setWeight(Math.max(0, reward.weight() + delta));
            saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addRewardAction(String crateId, String rewardId, RewardAction action) {
        if (action == null) throw new IllegalArgumentException("Reward action is required");
        if (action.item() != null) ItemCodec.snapshot(action.item());
        lock.writeLock().lock();
        try {
            Reward reward = requireMutable(crateId).reward(rewardId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown reward: " + rewardId));
            reward.addAction(action);
            saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CrateValidator.Result validate(String crateId) {
        lock.readLock().lock();
        try {
            Crate crate = crates.get(normalize(crateId));
            if (crate == null) throw new IllegalArgumentException("Unknown crate: " + crateId);
            return CrateValidator.validate(crate.copy());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Toggles published runtime status. Enabling is refused unless central validation passes;
     * disabling is always permitted.
     */
    public boolean toggleEnabled(String crateId) {
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(crateId);
            if (!crate.enabled()) {
                CrateValidator.Result validation = CrateValidator.validate(crate.copy());
                if (!validation.valid()) {
                    plugin.getLogger().warning("Refused to enable invalid crate " + crate.id() + ": "
                            + validation.summary());
                    return false;
                }
            }
            crate.setEnabled(!crate.enabled());
            saveAsync();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String cycleAnimation(String crateId) {
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(crateId);
            int current = ANIMATIONS.indexOf(crate.animation());
            String next = ANIMATIONS.get((current + 1 + ANIMATIONS.size()) % ANIMATIONS.size());
            crate.setAnimation(next);
            saveAsync();
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String cycleIdleEffect(String crateId) {
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(crateId);
            int current = IDLE_EFFECTS.indexOf(crate.idleEffect());
            String next = IDLE_EFFECTS.get((current + 1 + IDLE_EFFECTS.size()) % IDLE_EFFECTS.size());
            crate.setIdleEffect(next);
            saveAsync();
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void link(Block block, String crateId) {
        String id = normalize(crateId);
        lock.writeLock().lock();
        try {
            Crate crate = requireMutable(id);
            BlockState state = block.getState();
            if (!(state instanceof TileState tile)) {
                throw new IllegalArgumentException("Physical crate blocks must be TileState blocks");
            }
            CrateLocation location = CrateLocation.of(block);
            String occupied = locationIndex.get(location.key());
            if (occupied != null && !occupied.equals(id)) {
                throw new IllegalStateException("This block is already linked to " + occupied);
            }
            crate.addLocation(location);
            locationIndex.put(location.key(), id);
            tile.getPersistentDataContainer().set(crateBlockKey, PersistentDataType.STRING, id);
            tile.update(true, false);
            saveAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<String> unlink(Block block) {
        lock.writeLock().lock();
        try {
            CrateLocation location = CrateLocation.of(block);
            String id = locationIndex.remove(location.key());
            if (id == null) {
                BlockState state = block.getState();
                if (state instanceof TileState tile) {
                    id = tile.getPersistentDataContainer().get(crateBlockKey, PersistentDataType.STRING);
                }
            }
            if (id == null) return Optional.empty();
            Crate crate = crates.get(normalize(id));
            if (crate != null) crate.removeLocation(location);
            BlockState state = block.getState();
            if (state instanceof TileState tile) {
                tile.getPersistentDataContainer().remove(crateBlockKey);
                tile.update(true, false);
            }
            saveAsync();
            return Optional.of(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isRegisteredLocation(Block block) {
        lock.readLock().lock();
        try {
            return locationIndex.containsKey(CrateLocation.of(block).key());
        } finally {
            lock.readLock().unlock();
        }
    }

    public NamespacedKey crateBlockKey() {
        return crateBlockKey;
    }

    public CompletableFuture<Void> saveAsync() {
        String serialized;
        lock.readLock().lock();
        try {
            serialized = serialize();
        } finally {
            lock.readLock().unlock();
        }
        synchronized (persistenceMonitor) {
            saveTail = saveTail.handle((ignored, previousError) -> null).thenRunAsync(() -> {
                try {
                    Files.createDirectories(file.toPath().getParent());
                    PathWriter.atomicWrite(file.toPath(), serialized);
                } catch (Exception error) {
                    plugin.getLogger().log(Level.SEVERE, "Could not save crates.yml", error);
                    throw new RuntimeException(error);
                }
            }, executor);
            return saveTail;
        }
    }

    public CompletableFuture<Void> pendingSaves() {
        synchronized (persistenceMonitor) {
            return saveTail;
        }
    }

    private Crate parseCrate(String rawId, ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("Missing crate section");
        String id = normalizeId(rawId);
        boolean enabled = section.getBoolean("enabled", false);
        String displayName = section.getString("display-name", "&6" + title(id) + " Crate");
        List<String> description = section.getStringList("description");
        ItemStack icon = ItemCodec.read(section.getConfigurationSection("icon"));

        ConfigurationSection key = section.getConfigurationSection("key");
        if (key == null) throw new IllegalArgumentException("Missing key configuration");
        String keyDisplayName = key.getString("display-name", "&6" + title(id) + " Key");
        ItemStack keyItem = ItemCodec.read(key.getConfigurationSection("item"));

        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rewardRoot = section.getConfigurationSection("rewards");
        if (rewardRoot != null) {
            for (String rawRewardId : rewardRoot.getKeys(false)) {
                String rewardId = normalizeId(rawRewardId);
                ConfigurationSection rewardSection = rewardRoot.getConfigurationSection(rawRewardId);
                if (rewardSection == null) continue;
                ItemStack displayItem = ItemCodec.read(rewardSection.getConfigurationSection("display-item"));
                List<RewardAction> actions = readActions(rewardSection.getMapList("actions"));
                if (actions.isEmpty()) actions = List.of(new RewardAction(RewardActionType.ITEM, "", null));
                rewards.add(new Reward(rewardId, rewardSection.getBoolean("enabled", true),
                        Math.max(0, rewardSection.getInt("weight", 1)), displayItem, actions));
            }
        }

        List<CrateLocation> locations = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("locations")) {
            try {
                UUID worldId = map.get("world-uuid") == null ? null : UUID.fromString(String.valueOf(map.get("world-uuid")));
                String world = String.valueOf(map.containsKey("world") ? map.get("world") : "");
                int x = Integer.parseInt(String.valueOf(map.containsKey("x") ? map.get("x") : "0"));
                int y = Integer.parseInt(String.valueOf(map.containsKey("y") ? map.get("y") : "0"));
                int z = Integer.parseInt(String.valueOf(map.containsKey("z") ? map.get("z") : "0"));
                if (!world.isBlank()) locations.add(new CrateLocation(worldId, world, x, y, z));
            } catch (Exception error) {
                plugin.getLogger().warning("Ignoring malformed location in crate " + id + ": " + error.getMessage());
            }
        }

        Crate crate = new Crate(id, enabled, displayName, description, icon, keyDisplayName, keyItem,
                section.getString("animation", "CSGO"), section.getString("idle-effect", "HELIX"), rewards, locations);
        if (enabled) {
            CrateValidator.Result validation = CrateValidator.validate(crate);
            if (!validation.valid()) {
                crate.setEnabled(false);
                plugin.getLogger().warning("Loaded crate " + id + " as disabled because validation failed: "
                        + validation.summary());
            }
        }
        return crate;
    }

    private List<RewardAction> readActions(List<Map<?, ?>> maps) {
        List<RewardAction> actions = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            try {
                RewardActionType type = RewardActionType.valueOf(
                        String.valueOf(map.containsKey("type") ? map.get("type") : "ITEM").toUpperCase(Locale.ROOT));
                String value = String.valueOf(map.containsKey("value") ? map.get("value") : "");
                ItemStack item = null;
                Object encoded = map.get("item-base64");
                if (encoded != null && !String.valueOf(encoded).isBlank()) {
                    item = ItemCodec.decode(String.valueOf(encoded));
                    Object expectedHash = map.get("item-sha256");
                    if (expectedHash != null && !String.valueOf(expectedHash).isBlank()
                            && !String.valueOf(expectedHash).equalsIgnoreCase(ItemCodec.fingerprint(item))) {
                        throw new IllegalArgumentException("Reward action item fingerprint mismatch");
                    }
                }
                actions.add(new RewardAction(type, value, item));
            } catch (Exception error) {
                plugin.getLogger().warning("Ignoring invalid reward action: " + error.getMessage());
            }
        }
        return actions;
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Crate crate : crates.values()) {
            String path = "crates." + crate.id();
            yaml.set(path + ".enabled", crate.enabled());
            yaml.set(path + ".display-name", crate.displayName());
            yaml.set(path + ".description", crate.description());
            writeExact(yaml, path + ".icon", crate.icon());
            yaml.set(path + ".key.display-name", crate.keyDisplayName());
            writeExact(yaml, path + ".key.item", crate.keyItem());
            yaml.set(path + ".animation", crate.animation());
            yaml.set(path + ".idle-effect", crate.idleEffect());

            List<Map<String, Object>> locations = new ArrayList<>();
            for (CrateLocation location : crate.locations()) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (location.worldId() != null) map.put("world-uuid", location.worldId().toString());
                map.put("world", location.worldName());
                map.put("x", location.x());
                map.put("y", location.y());
                map.put("z", location.z());
                locations.add(map);
            }
            yaml.set(path + ".locations", locations);

            for (Reward reward : crate.rewards()) {
                String rewardPath = path + ".rewards." + reward.id();
                yaml.set(rewardPath + ".enabled", reward.enabled());
                yaml.set(rewardPath + ".weight", reward.weight());
                writeExact(yaml, rewardPath + ".display-item", reward.displayItem());
                List<Map<String, Object>> actions = new ArrayList<>();
                for (RewardAction action : reward.actions()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("type", action.type().name());
                    if (!action.value().isBlank()) map.put("value", action.value());
                    if (action.item() != null) {
                        map.put("item-base64", ItemCodec.encode(action.item()));
                        map.put("item-sha256", ItemCodec.fingerprint(action.item()));
                        map.put("item-format", com.plexoncrates.item.ExactItemSnapshot.FORMAT_NAME);
                    }
                    actions.add(map);
                }
                yaml.set(rewardPath + ".actions", actions);
            }
        }
        return "# PlexonCrates 4.0 - managed crate definitions.\n"
                + "# Use /crates admin for safe in-game editing. Exact items use Paper-native NBT snapshots + SHA-256.\n\n"
                + yaml.saveToString();
    }

    private static void writeExact(YamlConfiguration yaml, String path, ItemStack item) {
        yaml.set(path + ".base64", ItemCodec.encode(item));
        yaml.set(path + ".sha256", ItemCodec.fingerprint(item));
        yaml.set(path + ".format", com.plexoncrates.item.ExactItemSnapshot.FORMAT_NAME);
    }

    private Crate requireMutable(String crateId) {
        Crate crate = crates.get(normalize(crateId));
        if (crate == null) throw new IllegalArgumentException("Unknown crate: " + crateId);
        return crate;
    }

    private void rebuildLocationIndex() {
        locationIndex.clear();
        for (Crate crate : crates.values()) {
            for (CrateLocation location : crate.locations()) {
                String previous = locationIndex.putIfAbsent(location.key(), crate.id());
                if (previous != null && !previous.equals(crate.id())) {
                    plugin.getLogger().warning("Duplicate linked crate location " + location.key()
                            + " claimed by " + previous + " and " + crate.id() + "; keeping " + previous);
                }
            }
        }
    }

    private static String nextRewardId(Crate crate) {
        int index = 1;
        while (crate.reward("reward_" + index).isPresent()) index++;
        return "reward_" + index;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String value) {
        String normalized = normalize(value).replaceAll("[^a-z0-9_-]+", "_").replaceAll("_+", "_");
        while (normalized.startsWith("_")) normalized = normalized.substring(1);
        while (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isBlank() || normalized.length() > 64) throw new IllegalArgumentException("Invalid crate/reward ID");
        return normalized;
    }

    private static String title(String id) {
        String[] parts = id.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private static ItemStack named(ItemStack item, String name) {
        ItemStack result = item.clone();
        var meta = result.getItemMeta();
        meta.setDisplayName(ColorUtil.color(name));
        result.setItemMeta(meta);
        return result;
    }

    /** Small atomic text-file writer used by async persistence. */
    private static final class PathWriter {
        private static void atomicWrite(java.nio.file.Path target, String content) throws Exception {
            java.nio.file.Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            java.nio.file.Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            boolean moved = false;
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
            } finally {
                if (!moved) Files.deleteIfExists(temp);
            }
        }
    }
}

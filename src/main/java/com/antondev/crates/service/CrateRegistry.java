package com.antondev.crates.service;

import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.api.event.CrateDefinitionChangeEvent;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.crate.AnimationType;
import com.antondev.crates.domain.crate.CrateState;
import com.antondev.crates.domain.reward.PityPolicy;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.item.ItemSnapshotCodec;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
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
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Immutable, validate-before-swap registry for editable crate definitions and canonical runtime mirrors. */
public final class CrateRegistry {
    public record Snapshot(Map<String, Crate> crates, Map<String, Path> files,
                           Map<String, byte[]> payloads) {
        public Snapshot(Map<String, Crate> crates, Map<String, Path> files) {
            this(crates, files, Map.of());
        }

        public Snapshot {
            crates = Collections.unmodifiableMap(new LinkedHashMap<>(crates));
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            var copiedPayloads = new LinkedHashMap<String, byte[]>();
            payloads.forEach((id, payload) -> copiedPayloads.put(id, payload.clone()));
            payloads = Collections.unmodifiableMap(copiedPayloads);
        }

        @Override public Map<String, byte[]> payloads() {
            var copied = new LinkedHashMap<String, byte[]>();
            payloads.forEach((id, payload) -> copied.put(id, payload.clone()));
            return Collections.unmodifiableMap(copied);
        }
    }

    public record PreparedPublication(String crateId, Crate crate, byte[] payload, Path file) {
        public PreparedPublication {
            crateId = java.util.Objects.requireNonNull(crateId, "crateId");
            crate = java.util.Objects.requireNonNull(crate, "crate");
            payload = java.util.Objects.requireNonNull(payload, "payload").clone();
            file = java.util.Objects.requireNonNull(file, "file");
        }

        @Override public byte[] payload() { return payload.clone(); }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final ItemSnapshotCodec ITEM_SNAPSHOTS = new ItemSnapshotCodec();
    private final Path directory;
    private Map<String, Crate> crates;
    private Map<String, Path> files;
    private Map<String, byte[]> payloads;

    public CrateRegistry(Path directory, Snapshot snapshot) {
        this.directory = directory.toAbsolutePath().normalize();
        apply(snapshot);
    }

    public static Snapshot load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Missing crates directory");
        var loaded = new LinkedHashMap<String, Crate>();
        var paths = new LinkedHashMap<String, Path>();
        var payloads = new LinkedHashMap<String, byte[]>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                YamlConfiguration yaml = read(file);
                Crate crate = parse(file, yaml);
                if (loaded.putIfAbsent(crate.id(), crate) != null) throw path(file, "duplicate crate ID " + crate.id());
                paths.put(crate.id(), file);
                payloads.put(crate.id(), Files.readAllBytes(file));
            }
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("No crate files were found in " + directory);
        return new Snapshot(loaded, paths, payloads);
    }

    /** Builds a registry from the canonical published payloads without reading any YAML mirror. */
    public static CrateRegistry fromPublished(Path directory,
                                              List<DatabaseService.StoredDefinition> definitions) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Missing crates directory");
        var loaded = new LinkedHashMap<String, Crate>();
        var paths = new LinkedHashMap<String, Path>();
        var payloads = new LinkedHashMap<String, byte[]>();
        for (DatabaseService.StoredDefinition definition : definitions) {
            String id = normalize(definition.crateId());
            if (!validId(id)) throw new IllegalArgumentException("Canonical definition has an invalid crate ID: " + id);
            if (loaded.containsKey(id)) throw new IllegalArgumentException("Duplicate canonical crate ID: " + id);
            Path file = root.resolve(id + ".yml").normalize();
            if (!file.getParent().equals(root)) throw new IllegalArgumentException("Canonical crate path is invalid: " + id);
            YamlConfiguration yaml = decode(definition.payload());
            Crate crate = parse(file, yaml);
            if (!crate.id().equals(id) || crate.state() != CrateState.PUBLISHED) {
                throw new IllegalArgumentException("Canonical definition is not a published crate: " + id);
            }
            loaded.put(id, crate);
            paths.put(id, file);
            payloads.put(id, definition.payload());
        }
        // Unpublished drafts are still editable after a restart. Their mirrors are only a recovery aid and
        // are therefore parsed opportunistically; malformed or stale published mirrors never enter this path.
        try (var stream = Files.list(root)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                String candidateId = normalize(file.getFileName().toString().replaceFirst("\\.yml$", ""));
                if (loaded.containsKey(candidateId)) continue;
                try {
                    YamlConfiguration yaml = read(file);
                    Crate draft = parse(file, yaml);
                    if (draft.state() == CrateState.DRAFT && loaded.putIfAbsent(draft.id(), draft) == null) {
                        paths.put(draft.id(), file);
                        payloads.put(draft.id(), Files.readAllBytes(file));
                    }
                } catch (RuntimeException | IOException ignored) {
                    // A non-canonical mirror must not prevent SQLite-backed startup.
                }
            }
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("Canonical store contains no published crates");
        return new CrateRegistry(root, new Snapshot(loaded, paths, payloads));
    }

    public void apply(Snapshot snapshot) {
        crates = snapshot.crates();
        files = snapshot.files();
        payloads = new LinkedHashMap<>();
        snapshot.payloads().forEach((id, payload) -> payloads.put(id, payload.clone()));
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            if (payloads.containsKey(entry.getKey())) continue;
            try {
                if (Files.isRegularFile(entry.getValue())) payloads.put(entry.getKey(), Files.readAllBytes(entry.getValue()));
            } catch (IOException ignored) {
                // A canonical snapshot may intentionally have no writable YAML mirror.
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(crates, files, payloads);
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
        String id = normalize(crateId);
        if (!files.containsKey(id)) throw new IllegalArgumentException("Unknown crate");
        byte[] payload = payloads.get(id);
        if (payload != null) return new String(payload, StandardCharsets.UTF_8);
        Path file = files.get(id);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public PreparedPublication preparePublished(
            String crateId, byte[] frozenDraftPayload, String editor) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        YamlConfiguration yaml = decode(frozenDraftPayload);
        String payloadId = normalize(yaml.getString("id", id));
        if (!id.equals(payloadId)) throw new IllegalArgumentException("Draft snapshot targets a different crate ID");
        yaml.set("state", CrateState.PUBLISHED.name());
        touch(yaml, editor);
        Crate published = parse(file, yaml);
        byte[] payload = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        return new PreparedPublication(id, published, payload, file);
    }

    public Crate parsePublished(String crateId, byte[] payload) throws Exception {
        String id = normalize(crateId);
        YamlConfiguration yaml = decode(payload);
        Crate parsed = parse(files.getOrDefault(id, directory.resolve(id + ".yml")), yaml);
        if (!parsed.id().equals(id) || parsed.state() != CrateState.PUBLISHED) {
            throw new IllegalArgumentException("Stored runtime definition is not the requested published crate");
        }
        return parsed;
    }

    public void installPublished(PreparedPublication publication) throws IOException {
        AtomicFiles.write(publication.file(), new String(publication.payload(), StandardCharsets.UTF_8));
        Crate previous = crates.get(publication.crateId());
        install(publication.crateId(), publication.file(), publication.crate());
        payloads.put(publication.crateId(), publication.payload());
        fireChange(publication.crate(), changeType(previous, publication.crate()));
    }

    public Crate restoreDraftSnapshot(String crateId, byte[] payload) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        YamlConfiguration yaml = decode(payload);
        Crate previous = crates.get(id);
        Crate restored = parse(file, yaml);
        if (!restored.id().equals(id)) {
            throw new IllegalArgumentException("Draft snapshot targets a different crate ID");
        }
        String serialized = yaml.saveToString();
        AtomicFiles.write(file, serialized);
        install(id, file, restored);
        payloads.put(id, serialized.getBytes(StandardCharsets.UTF_8));
        fireChange(restored, changeType(previous, restored));
        return restored;
    }

    public Crate createDraft(String rawId, String editor) throws Exception {
        String id = normalize(rawId);
        if (!validId(id)) throw new IllegalArgumentException("Invalid crate ID");
        if (crates.containsKey(id)) throw new IllegalArgumentException("Crate already exists");
        Path file = directory.resolve(id + ".yml").normalize();
        if (!file.getParent().equals(directory.normalize())) throw new IllegalArgumentException("Invalid crate path");
        Instant now = Instant.now();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 3);
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
        String serialized = yaml.saveToString();
        AtomicFiles.write(file, serialized);
        install(id, file, parsed);
        payloads.put(id, serialized.getBytes(StandardCharsets.UTF_8));
        fireChange(parsed, CrateDefinitionChangeEvent.ChangeType.CREATED);
        return parsed;
    }

    public Crate cloneAsDraft(String sourceId, String rawNewId, String editor) throws Exception {
        Crate source = find(sourceId).orElseThrow(() -> new IllegalArgumentException("Unknown source crate"));
        String newId = normalize(rawNewId);
        if (!validId(newId) || crates.containsKey(newId)) throw new IllegalArgumentException("Invalid or existing new crate ID");
        YamlConfiguration yaml = decode(serialized(source.id()).getBytes(StandardCharsets.UTF_8));
        yaml.set("id", newId);
        yaml.set("state", "DRAFT");
        yaml.set("display-order", nextDisplayOrder());
        yaml.set("audit.created-at", Instant.now().toString());
        yaml.set("audit.updated-at", Instant.now().toString());
        yaml.set("audit.last-editor", editor);
        Path file = directory.resolve(newId + ".yml");
        Crate parsed = parse(file, yaml);
        String serialized = yaml.saveToString();
        AtomicFiles.write(file, serialized);
        install(newId, file, parsed);
        payloads.put(newId, serialized.getBytes(StandardCharsets.UTF_8));
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
        String serialized = yaml.saveToString();
        AtomicFiles.write(destination, serialized);
        install(newId, destination, parsed);
        payloads.put(newId, serialized.getBytes(StandardCharsets.UTF_8));
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
        AtomicFiles.write(destination, serialized(id));
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
        return publishingIssues(crate, keys);
    }

    public List<String> publishingIssues(Crate crate, KeyService keys) {
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
        if (crate.rewards().values().stream().anyMatch(reward -> reward.enabled() && !reward.hasDelivery())) {
            issues.add("Every enabled reward needs at least one deliverable action.");
        }
        int chanceTotal = crate.rewards().values().stream().filter(CrateReward::enabled)
                .mapToInt(CrateReward::chanceBasisPoints).sum();
        if (chanceTotal != ChanceAllocator.TOTAL_BASIS_POINTS) {
            issues.add("Enabled reward chances total " + String.format(Locale.ROOT, "%.2f%%", chanceTotal / 100.0)
                    + "; balance the pool to exactly 100.00%.");
        }
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
        Files.deleteIfExists(file);
        var nextCrates = new LinkedHashMap<>(crates);
        var nextFiles = new LinkedHashMap<>(files);
        nextCrates.remove(id);
        nextFiles.remove(id);
        crates = Collections.unmodifiableMap(nextCrates);
        files = Collections.unmodifiableMap(nextFiles);
        payloads.remove(id);
        fireChange(deleted, CrateDefinitionChangeEvent.ChangeType.DELETED);
    }

    public void addCapturedReward(String crateId, String rewardId, double baseChancePercent, ItemStack held) throws Exception {
        addCapturedReward(crateId, rewardId, baseChancePercent, held, "CONSOLE");
    }

    public void addCapturedReward(String crateId, String rewardId, double baseChancePercent, ItemStack held,
                                  String editor) throws Exception {
        validateRewardInput(rewardId, baseChancePercent);
        if (held == null || held.getType().isAir()) throw new IllegalArgumentException("Hold the exact reward item first");
        ITEM_SNAPSHOTS.capture(held);
        Component customName = held.hasItemMeta() ? held.getItemMeta().displayName() : null;
        Component displayName = customName == null
                ? Text.parse("<white><bold>" + pretty(held.getType().name()) + "</bold></white>") : customName;
        addBundleReward(crateId, rewardId, displayName, baseChancePercent, RewardRarity.COMMON,
                List.of(held), List.of(), 0, 0, 0, editor);
    }

    public String addGeneratedCapturedReward(String crateId, ItemStack held, double baseChancePercent) throws Exception {
        return addGeneratedCapturedReward(crateId, held, baseChancePercent, "CONSOLE");
    }

    public String addGeneratedCapturedReward(String crateId, ItemStack held, double baseChancePercent,
                                             String editor) throws Exception {
        String base = held.getType().name().toLowerCase(Locale.ROOT);
        Crate crate = find(crateId).orElseThrow(() -> new IllegalArgumentException("Unknown crate"));
        String id;
        do {
            id = base + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        } while (crate.rewards().containsKey(id));
        addCapturedReward(crateId, id, baseChancePercent, held, editor);
        return id;
    }

    public String addGeneratedCapturedReward(String crateId, ItemStack held) throws Exception {
        return addGeneratedCapturedReward(crateId, held, "CONSOLE");
    }

    public String addGeneratedCapturedReward(String crateId, ItemStack held, String editor) throws Exception {
        Crate crate = find(crateId).orElseThrow(() -> new IllegalArgumentException("Unknown crate"));
        int defaultBasisPoints = ChanceAllocator.defaultNewRewardBasisPoints(crate.rewards().size() + 1);
        return addGeneratedCapturedReward(crateId, held, defaultBasisPoints / 100.0, editor);
    }

    public void addCommandReward(String crateId, String rewardId, double baseChancePercent, String command) throws Exception {
        addCommandReward(crateId, rewardId, baseChancePercent, command, "CONSOLE");
    }

    public void addCommandReward(String crateId, String rewardId, double baseChancePercent, String command,
                                 String editor) throws Exception {
        String normalized = command == null ? "" : command.trim();
        if (normalized.startsWith("/")) throw new IllegalArgumentException("Reward commands must not begin with /");
        if (normalized.isBlank() || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException("Command cannot be empty or contain newlines");
        }
        addBundleReward(crateId, rewardId, Text.parse("<gold><bold>Command Reward</bold></gold>"), baseChancePercent,
                RewardRarity.COMMON, List.of(), List.of(normalized), 0, 0, 0, editor);
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double baseChancePercent,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, String editor) throws Exception {
        addBundleReward(crateId, rewardId, displayName, baseChancePercent, rarity, items, commands, experiencePoints,
                experienceLevels, money, RewardLimits.unlimited(), "", "", "", "", editor);
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double baseChancePercent,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, RewardLimits limits,
                                String requiredPermission, String blockedPermission, String personalMessage,
                                String broadcast, String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, baseChancePercent, true, rarity, null, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                RewardPresentation.none(), personalMessage, broadcast, editor, false);
    }

    public void addBundleReward(String crateId, String rewardId, Component displayName, double baseChancePercent,
                                RewardRarity rarity, List<ItemStack> items, List<String> commands,
                                int experiencePoints, int experienceLevels, double money, RewardLimits limits,
                                String requiredPermission, String blockedPermission, RewardPresentation presentation,
                                String personalMessage, String broadcast, String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, baseChancePercent, true, rarity, null, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                presentation, personalMessage, broadcast, editor, false);
    }

    public void updateBundleReward(String crateId, String rewardId, Component displayName, double baseChancePercent,
                                   boolean enabled, RewardRarity rarity, ItemStack displayItem,
                                   List<ItemStack> items, List<String> commands, int experiencePoints,
                                   int experienceLevels, double money, RewardLimits limits,
                                   String requiredPermission, String blockedPermission,
                                   RewardPresentation presentation, String personalMessage, String broadcast,
                                   String editor) throws Exception {
        saveBundleReward(crateId, rewardId, displayName, baseChancePercent, enabled, rarity, displayItem, items, commands,
                experiencePoints, experienceLevels, money, limits, requiredPermission, blockedPermission,
                presentation, personalMessage, broadcast, editor, true);
    }

    private void saveBundleReward(String crateId, String rewardId, Component displayName, double baseChancePercent,
                                  boolean enabled, RewardRarity rarity, ItemStack displayItem,
                                  List<ItemStack> items, List<String> commands, int experiencePoints,
                                  int experienceLevels, double money, RewardLimits limits,
                                  String requiredPermission, String blockedPermission,
                                  RewardPresentation presentation, String personalMessage, String broadcast,
                                  String editor, boolean updating) throws Exception {
        validateRewardInput(rewardId, baseChancePercent);
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
            upgradeChancePool(yaml);
            String path = "rewards." + normalize(rewardId);
            if (updating && !yaml.contains(path)) throw new IllegalArgumentException("Reward no longer exists");
            if (!updating && yaml.contains(path)) throw new IllegalArgumentException("Reward already exists");
            int requestedBasisPoints = enabled ? percentageToBasisPoints(baseChancePercent) : 0;
            List<ChanceAllocator.Chance> current = chancePool(yaml);
            ChanceAllocator.Allocation allocation;
            if (updating) {
                if (current.size() == 1 && requestedBasisPoints == 0) {
                    allocation = new ChanceAllocator.Allocation(List.of(
                            current.getFirst().withBasisPoints(0)));
                } else {
                    allocation = ChanceAllocator.setChance(current, normalize(rewardId), requestedBasisPoints);
                }
            } else {
                if (current.isEmpty() && requestedBasisPoints == 0) {
                    allocation = new ChanceAllocator.Allocation(List.of(
                            new ChanceAllocator.Chance(normalize(rewardId), 0, false)));
                } else {
                    allocation = ChanceAllocator.addReward(current, normalize(rewardId));
                }
                if (allocation.basisPoints(normalize(rewardId)) != requestedBasisPoints
                        && allocation.chances().size() > 1) {
                    allocation = ChanceAllocator.setChance(allocation.chances(), normalize(rewardId), requestedBasisPoints);
                }
            }
            applyChancePool(yaml, allocation);
            yaml.set(path + ".enabled", enabled);
            yaml.set(path + ".display-name", Text.serialize(displayName));
            yaml.set(path + ".rarity", rarity.name());
            yaml.set(path + ".chance-basis-points", allocation.basisPoints(normalize(rewardId)));
            yaml.set(path + ".weight", null);
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
        if (!files.containsKey(sourceId) || !files.containsKey(targetId)) {
            throw new IllegalArgumentException("Unknown source or target crate");
        }
        YamlConfiguration source = decode(serialized(sourceId).getBytes(StandardCharsets.UTF_8));
        ConfigurationSection section = source.getConfigurationSection("rewards." + sourceReward);
        if (section == null) throw new IllegalArgumentException("Unknown source reward");
        mutate(targetId, yaml -> {
            upgradeChancePool(yaml);
            String targetPath = "rewards." + newReward;
            if (yaml.contains(targetPath)) throw new IllegalArgumentException("Target reward already exists");
            boolean copiedEnabled = section.getBoolean("enabled", true);
            List<ChanceAllocator.Chance> current = chancePool(yaml);
            ChanceAllocator.Allocation allocation;
            if (current.isEmpty() && !copiedEnabled) {
                allocation = new ChanceAllocator.Allocation(List.of(
                        new ChanceAllocator.Chance(newReward, 0, false)));
            } else {
                allocation = ChanceAllocator.addReward(current, newReward);
                if (!copiedEnabled) {
                    allocation = ChanceAllocator.setChance(allocation.chances(), newReward, 0);
                }
            }
            for (Map.Entry<String, Object> entry : section.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection)
                        && !entry.getKey().equals("weight")
                        && !entry.getKey().equals("chance-basis-points")
                        && !entry.getKey().equals("chance-locked")) {
                    yaml.set(targetPath + "." + entry.getKey(), entry.getValue());
                }
            }
            applyChancePool(yaml, allocation);
            touch(yaml, editor);
        });
    }

    public void removeReward(String crateId, String rewardId) throws Exception {
        removeReward(crateId, rewardId, "CONSOLE");
    }

    public void removeReward(String crateId, String rewardId, String editor) throws Exception {
        mutate(crateId, yaml -> {
            upgradeChancePool(yaml);
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path, null);
            List<ChanceAllocator.Chance> remaining = chancePool(yaml);
            if (!remaining.isEmpty() && remaining.stream().anyMatch(chance -> chance.basisPoints() > 0)) {
                applyChancePool(yaml, ChanceAllocator.normalizeUnlocked(remaining));
            }
            touch(yaml, editor);
        });
    }

    /** @deprecated Use {@link #setChanceBasisPoints(String, String, int)}. */
    @Deprecated(forRemoval = false)
    public void setWeight(String crateId, String rewardId, double legacyChanceValue) throws Exception {
        setChanceBasisPoints(crateId, rewardId, percentageToBasisPoints(legacyChanceValue));
    }

    public void setChanceBasisPoints(String crateId, String rewardId, int basisPoints) throws Exception {
        setChanceBasisPoints(crateId, rewardId, basisPoints, "CONSOLE");
    }

    public void setChanceBasisPoints(String crateId, String rewardId, int basisPoints, String editor) throws Exception {
        if (basisPoints < 0 || basisPoints > ChanceAllocator.TOTAL_BASIS_POINTS) {
            throw new IllegalArgumentException("Chance must be between 0.00% and 100.00%");
        }
        mutate(crateId, yaml -> {
            upgradeChancePool(yaml);
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            if (!yaml.getBoolean(path + ".enabled", true) && basisPoints > 0) {
                throw new IllegalArgumentException("Enable the reward before assigning a positive chance");
            }
            ChanceAllocator.Allocation allocation = ChanceAllocator.setChance(chancePool(yaml),
                    normalize(rewardId), basisPoints);
            applyChancePool(yaml, allocation);
            touch(yaml, editor);
        });
    }

    public void balanceChances(String crateId, ChanceBalanceMode mode, String editor) throws Exception {
        mutate(crateId, yaml -> {
            upgradeChancePool(yaml);
            List<ChanceAllocator.Chance> current = enabledChancePool(yaml);
            ChanceAllocator.Allocation allocation = switch (mode) {
                case PRESERVE_RELATIVE -> ChanceAllocator.normalize(current);
                case EQUAL -> ChanceAllocator.equalize(current);
                case NORMALIZE_UNLOCKED -> ChanceAllocator.normalizeUnlocked(current);
                case RARITY_CURVE -> rarityCurve(yaml, current);
            };
            applyChancePool(yaml, allocation);
            zeroDisabledChances(yaml);
            touch(yaml, editor);
        });
    }

    public void setChanceLocked(String crateId, String rewardId, boolean locked, String editor) throws Exception {
        mutate(crateId, yaml -> {
            upgradeChancePool(yaml);
            String path = "rewards." + normalize(rewardId);
            if (!yaml.contains(path)) throw new IllegalArgumentException("Reward not found");
            yaml.set(path + ".chance-locked", locked);
            touch(yaml, editor);
        });
    }

    public enum ChanceBalanceMode {
        PRESERVE_RELATIVE,
        EQUAL,
        NORMALIZE_UNLOCKED,
        RARITY_CURVE
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    private void mutate(String crateId, ThrowingConsumer<YamlConfiguration> change) throws Exception {
        String id = normalize(crateId);
        Path file = files.get(id);
        if (file == null) throw new IllegalArgumentException("Unknown crate");
        Crate previous = crates.get(id);
        YamlConfiguration yaml = decode(serialized(id).getBytes(StandardCharsets.UTF_8));
        change.accept(yaml);
        Crate parsed = parse(file, yaml);
        String serialized = yaml.saveToString();
        AtomicFiles.write(file, serialized);
        install(id, file, parsed);
        payloads.put(id, serialized.getBytes(StandardCharsets.UTF_8));
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
        int configVersion = yaml.getInt("config-version");
        if (configVersion != 2 && configVersion != 3) {
            throw path(file, "unsupported config-version; expected 2 or 3");
        }
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
        if (state == CrateState.PUBLISHED
                && rewards.values().stream().anyMatch(reward -> reward.enabled() && !reward.hasDelivery())) {
            throw path(file, "every enabled reward needs a deliverable action");
        }
        int publishedTotal = rewards.values().stream().filter(CrateReward::enabled)
                .mapToInt(CrateReward::chanceBasisPoints).sum();
        if (state == CrateState.PUBLISHED && publishedTotal != ChanceAllocator.TOTAL_BASIS_POINTS) {
            throw path(file, "published reward chances must total exactly 100.00%");
        }
        return new Crate(id, state, displayOrder, display, description, icon, permission, worlds, excludedWorlds,
                acceptedKeys, keyCost, cooldown, bulkEnabled, bulkMaximum, animation, hologram, crateBroadcast, pity, rewards);
    }

    private static Map<String, CrateReward> parseRewards(Path file, YamlConfiguration yaml) {
        ConfigurationSection rewardsSection = yaml.getConfigurationSection("rewards");
        if (rewardsSection == null) throw path(file, "missing rewards section");
        boolean anyBasisPoints = rewardsSection.getKeys(false).stream().anyMatch(id ->
                yaml.contains("rewards." + id + ".chance-basis-points"));
        boolean allBasisPoints = rewardsSection.getKeys(false).stream().allMatch(id ->
                yaml.contains("rewards." + id + ".chance-basis-points"));
        if (anyBasisPoints && !allBasisPoints) {
            throw path(file, "reward pools cannot mix legacy weights and chance-basis-points");
        }
        var rewards = new LinkedHashMap<String, CrateReward>();
        var legacyWeights = new LinkedHashMap<String, BigDecimal>();
        for (String rawRewardId : rewardsSection.getKeys(false)) {
            String rewardId = normalize(rawRewardId);
            if (!validId(rewardId) || !rewardId.equals(rawRewardId)) throw path(file, "invalid reward ID " + rawRewardId);
            String path = "rewards." + rewardId;
            ConfigurationSection rewardSection = yaml.getConfigurationSection(path);
            if (rewardSection == null) throw path(file, path + " must be a section");
            boolean enabled = yaml.getBoolean(path + ".enabled", true);
            double chancePercent;
            if (allBasisPoints) {
                int basisPoints = integer(rewardSection.get("chance-basis-points"), file,
                        path + ".chance-basis-points", 0, ChanceAllocator.TOTAL_BASIS_POINTS);
                chancePercent = enabled ? basisPoints / 100.0 : 0.0;
            } else {
                if (enabled) {
                    double legacyWeight = number(rewardSection.get("weight"), file, path + ".weight",
                            0, 1_000_000_000, false);
                    legacyWeights.put(rewardId, BigDecimal.valueOf(legacyWeight));
                } else {
                    legacyWeights.put(rewardId, BigDecimal.ZERO);
                }
                chancePercent = 0.0;
            }
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
            rewards.put(rewardId, new CrateReward(rewardId, rewardName, chancePercent,
                    enabled, rarity, rewardDisplay, items, commands, points, levels,
                    money, yaml.getString(path + ".required-permission", ""),
                    yaml.getString(path + ".blocked-permission", ""), limits, presentation, personal, broadcast));
        }
        if (!allBasisPoints) {
            List<ChanceAllocator.WeightedChance> activeWeights = rewards.values().stream()
                    .filter(CrateReward::enabled)
                    .map(reward -> new ChanceAllocator.WeightedChance(reward.id(), legacyWeights.get(reward.id())))
                    .toList();
            if (!activeWeights.isEmpty()) {
                ChanceAllocator.Allocation converted = ChanceAllocator.fromWeights(activeWeights);
                var migrated = new LinkedHashMap<String, CrateReward>();
                rewards.forEach((id, reward) -> migrated.put(id,
                        reward.withChanceBasisPoints(reward.enabled() ? converted.basisPoints(id) : 0)));
                rewards = migrated;
            }
        }
        return rewards;
    }

    private static YamlConfiguration read(Path file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (Exception error) { throw path(file, "invalid YAML", error); }
        return yaml;
    }

    private static YamlConfiguration decode(byte[] payload) throws Exception {
        String serialized = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(java.util.Objects.requireNonNull(payload, "payload"))).toString();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(serialized);
        return yaml;
    }

    private static void upgradeChancePool(YamlConfiguration yaml) {
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards == null || rewards.getKeys(false).isEmpty()) return;
        boolean any = rewards.getKeys(false).stream().anyMatch(id ->
                yaml.contains("rewards." + id + ".chance-basis-points"));
        boolean all = rewards.getKeys(false).stream().allMatch(id ->
                yaml.contains("rewards." + id + ".chance-basis-points"));
        if (any && !all) throw new IllegalArgumentException("Reward pool mixes weights and percentages");
        if (all) {
            zeroDisabledChances(yaml);
            return;
        }

        var weighted = new ArrayList<ChanceAllocator.WeightedChance>();
        for (String id : rewards.getKeys(false)) {
            if (!yaml.getBoolean("rewards." + id + ".enabled", true)) continue;
            Object raw = yaml.get("rewards." + id + ".weight");
            if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() <= 0) {
                throw new IllegalArgumentException("Legacy reward weight must be positive and finite: " + id);
            }
            weighted.add(new ChanceAllocator.WeightedChance(id, new BigDecimal(number.toString())));
        }
        ChanceAllocator.Allocation allocation = weighted.isEmpty() ? null : ChanceAllocator.fromWeights(weighted);
        for (String id : rewards.getKeys(false)) {
            int basisPoints = allocation != null && yaml.getBoolean("rewards." + id + ".enabled", true)
                    ? allocation.basisPoints(id) : 0;
            yaml.set("rewards." + id + ".chance-basis-points", basisPoints);
            yaml.set("rewards." + id + ".weight", null);
        }
    }

    private static List<ChanceAllocator.Chance> chancePool(YamlConfiguration yaml) {
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards == null) return List.of();
        var result = new ArrayList<ChanceAllocator.Chance>();
        for (String id : rewards.getKeys(false)) {
            Object raw = yaml.get("rewards." + id + ".chance-basis-points");
            if (!(raw instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new IllegalArgumentException("Invalid chance basis points for reward " + id);
            }
            int basisPoints = number.intValue();
            result.add(new ChanceAllocator.Chance(id, basisPoints,
                    yaml.getBoolean("rewards." + id + ".chance-locked", false)));
        }
        return List.copyOf(result);
    }

    private static List<ChanceAllocator.Chance> enabledChancePool(YamlConfiguration yaml) {
        return chancePool(yaml).stream().filter(chance ->
                yaml.getBoolean("rewards." + chance.id() + ".enabled", true)).toList();
    }

    private static void zeroDisabledChances(YamlConfiguration yaml) {
        ConfigurationSection rewards = yaml.getConfigurationSection("rewards");
        if (rewards == null) return;
        for (String id : rewards.getKeys(false)) {
            if (!yaml.getBoolean("rewards." + id + ".enabled", true)) {
                yaml.set("rewards." + id + ".chance-basis-points", 0);
            }
        }
    }

    private static ChanceAllocator.Allocation rarityCurve(YamlConfiguration yaml,
                                                            List<ChanceAllocator.Chance> chances) {
        List<ChanceAllocator.WeightedChance> weights = chances.stream().map(chance -> {
            RewardRarity rarity;
            try {
                rarity = RewardRarity.valueOf(yaml.getString(
                        "rewards." + chance.id() + ".rarity", "COMMON").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid rarity for reward " + chance.id(), error);
            }
            long weight = switch (rarity) {
                case COMMON -> 64;
                case UNCOMMON -> 32;
                case RARE -> 16;
                case EPIC -> 8;
                case LEGENDARY -> 4;
                case MYTHIC -> 2;
            };
            return new ChanceAllocator.WeightedChance(chance.id(), weight);
        }).toList();
        return ChanceAllocator.fromWeights(weights);
    }

    private static void applyChancePool(YamlConfiguration yaml, ChanceAllocator.Allocation allocation) {
        for (ChanceAllocator.Chance chance : allocation.chances()) {
            String path = "rewards." + chance.id();
            yaml.set(path + ".chance-basis-points", chance.basisPoints());
            yaml.set(path + ".chance-locked", chance.locked());
            yaml.set(path + ".weight", null);
        }
    }

    private static int percentageToBasisPoints(double percentage) {
        if (!Double.isFinite(percentage) || percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Chance must be between 0.00% and 100.00%");
        }
        try {
            return BigDecimal.valueOf(percentage).movePointRight(2)
                    .setScale(0, java.math.RoundingMode.UNNECESSARY).intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Chance supports exact 0.01% precision", error);
        }
    }

    private static void validateRewardInput(String id, double baseChancePercent) {
        if (!validId(normalize(id))) throw new IllegalArgumentException("Invalid reward ID");
        percentageToBasisPoints(baseChancePercent);
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
        yaml.set("config-version", 3);
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

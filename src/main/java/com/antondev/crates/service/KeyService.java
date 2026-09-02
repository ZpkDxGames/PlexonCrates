package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.domain.key.ExternalKeyDescriptor;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.domain.key.KeyMatchMode;
import com.antondev.crates.domain.key.KeySource;
import com.antondev.crates.domain.key.ProviderStatus;
import com.antondev.crates.domain.key.ResolvedKey;
import com.antondev.crates.integration.plexonkeys.PlexonKeysKeyProvider;
import java.io.File;
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
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Provider-backed exact key registry. A KeyTransaction freezes one template for count + consume. */
public final class KeyService {
    public record Snapshot(Map<String, KeyDefinition> definitions) {
        public Snapshot {
            definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        }
    }

    public record KeyTransaction(
            KeyDefinition definition,
            ResolvedKey resolved,
            List<ItemStack> acceptedTemplates) {
        public KeyTransaction {
            acceptedTemplates = acceptedTemplates.stream().map(ItemCodec::one).toList();
        }

        @Override public List<ItemStack> acceptedTemplates() {
            return acceptedTemplates.stream().map(ItemCodec::one).toList();
        }
    }

    private final PlexonCrates plugin;
    private final DatabaseService database;
    private final Path file;
    private PlexonKeysKeyProvider provider;
    private final NamespacedKey identityTag;
    private Map<String, KeyDefinition> definitions;
    private Map<String, ItemStack> lastKnownGood;
    private Map<String, ExternalKeyDescriptor> discovered = Map.of();
    private Set<String> collisions = Set.of();

    public KeyService(PlexonCrates plugin, DatabaseService database, Path file, Snapshot snapshot,
                      Map<String, ItemStack> lastKnownGood) {
        this.plugin = plugin;
        this.database = database;
        this.file = file;
        this.definitions = snapshot.definitions();
        this.lastKnownGood = normalizedCopies(lastKnownGood);
        this.provider = new PlexonKeysKeyProvider(plugin, plugin.settings().plexonKeysPlugin());
        this.identityTag = new NamespacedKey(plugin, "key_identity");
        syncDiscovery();
    }

    public static Snapshot load(File file) {
        return load(file.toPath());
    }

    public static Snapshot load(Path file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        if (yaml.getInt("config-version") != 2) throw new IllegalArgumentException("Unsupported keys.yml config-version; expected 2");
        ConfigurationSection keys = yaml.getConfigurationSection("keys");
        if (keys == null || keys.getKeys(false).isEmpty()) throw new IllegalArgumentException("keys.yml contains no keys");
        var loaded = new LinkedHashMap<String, KeyDefinition>();
        for (String rawId : keys.getKeys(false)) {
            String id = normalize(rawId);
            if (!CrateRegistry.validId(id) || !id.equals(rawId)) throw new IllegalArgumentException("Invalid key ID: " + rawId);
            String path = "keys." + id;
            KeySource source = enumValue(KeySource.class, yaml.getString(path + ".source", "CONFIG"), path + ".source");
            KeyMatchMode matchMode = enumValue(KeyMatchMode.class, yaml.getString(path + ".match-mode", "EXACT"), path + ".match-mode");
            Component displayName = Text.parse(yaml.getString(path + ".display-name", "<white>" + id + " key</white>"));
            ItemStack owned = null;
            if (source != KeySource.PLEXONKEYS) {
                ConfigurationSection item = yaml.getConfigurationSection(path + ".item");
                if (item == null) throw new IllegalArgumentException(path + ".item is required for " + source);
                owned = ItemCodec.read(item);
            }
            ItemStack fallback = null;
            ConfigurationSection fallbackSection = yaml.getConfigurationSection(path + ".fallback");
            if (fallbackSection != null && !fallbackSection.getKeys(false).isEmpty()) fallback = ItemCodec.read(fallbackSection);
            ItemStack icon;
            ConfigurationSection iconSection = yaml.getConfigurationSection(path + ".icon");
            if (iconSection != null) icon = ItemCodec.read(iconSection);
            else if (owned != null) icon = owned.clone();
            else if (fallback != null) icon = fallback.clone();
            else icon = new ItemStack(org.bukkit.Material.TRIPWIRE_HOOK);
            icon.editMeta(meta -> meta.displayName(displayName));

            var legacy = new ArrayList<ItemStack>();
            for (String encoded : yaml.getStringList(path + ".legacy-templates")) legacy.add(ItemCodec.decode(encoded, true));
            String externalId = normalize(yaml.getString(path + ".external-id", id));
            if (source == KeySource.PLEXONKEYS && !CrateRegistry.validId(externalId)) {
                throw new IllegalArgumentException(path + ".external-id is invalid");
            }
            Instant created = instant(yaml.getString(path + ".created-at"));
            Instant updated = instant(yaml.getString(path + ".updated-at"));
            loaded.put(id, new KeyDefinition(id, yaml.getBoolean(path + ".enabled", true), displayName,
                    icon, source, externalId, matchMode, yaml.getBoolean(path + ".cache-last-known-good", true),
                    owned, fallback, legacy, created, updated));
        }
        return new Snapshot(loaded);
    }

    public void apply(Snapshot snapshot) {
        definitions = snapshot.definitions();
        provider = new PlexonKeysKeyProvider(plugin, plugin.settings().plexonKeysPlugin());
        syncDiscovery();
    }

    public Snapshot snapshot() {
        return new Snapshot(definitions);
    }

    public Optional<ResolvedKey> resolve(String keyId) {
        KeyDefinition definition = definitions.get(normalize(keyId));
        if (definition == null || !definition.enabled()) return Optional.empty();
        Instant now = Instant.now();
        if (definition.source() == KeySource.PLEXONKEYS) {
            if (plugin.settings().plexonKeysEnabled() && plugin.settings().plexonKeysMode().equals("LIVE_FIRST")) {
                Optional<ItemStack> live = provider.resolve(definition.externalId());
                if (live.isPresent()) {
                    ItemStack template = ItemCodec.one(live.get());
                    if (definition.cacheLastKnownGood()) cache(definition.id(), template);
                    return Optional.of(new ResolvedKey(definition.id(), template, ResolvedKey.ResolutionSource.LIVE, now));
                }
            }
            ItemStack cached = lastKnownGood.get(definition.id());
            if (cached != null) return Optional.of(new ResolvedKey(definition.id(), cached,
                    ResolvedKey.ResolutionSource.LAST_KNOWN_GOOD, now));
            ItemStack fallback = definition.fallbackTemplate();
            if (fallback != null) return Optional.of(new ResolvedKey(definition.id(), fallback,
                    ResolvedKey.ResolutionSource.FALLBACK, now));
            return Optional.empty();
        }
        ItemStack owned = definition.ownedTemplate();
        return owned == null ? Optional.empty() : Optional.of(new ResolvedKey(definition.id(), owned,
                ResolvedKey.ResolutionSource.OWNED, now));
    }

    public Optional<KeyTransaction> begin(String keyId) {
        KeyDefinition definition = definitions.get(normalize(keyId));
        Optional<ResolvedKey> resolved = resolve(keyId);
        if (definition == null || resolved.isEmpty()) return Optional.empty();
        var accepted = new ArrayList<ItemStack>();
        accepted.add(resolved.get().template());
        accepted.addAll(definition.legacyTemplates());
        return Optional.of(new KeyTransaction(definition, resolved.get(), accepted));
    }

    public Optional<ItemStack> template(String id) {
        return resolve(id).map(ResolvedKey::template);
    }

    public boolean matches(ItemStack candidate, String keyId) {
        return begin(keyId).map(transaction -> matches(candidate, transaction)).orElse(false);
    }

    public boolean matches(ItemStack candidate, KeyTransaction transaction) {
        if (candidate == null || candidate.getType().isAir()) return false;
        if (transaction.definition().matchMode() == KeyMatchMode.IDENTITY_TAG) {
            String identity = candidate.getItemMeta().getPersistentDataContainer().get(identityTag, PersistentDataType.STRING);
            return transaction.definition().id().equals(identity);
        }
        ItemStack normalized = ItemCodec.one(candidate);
        return transaction.acceptedTemplates().stream().anyMatch(normalized::isSimilar);
    }

    public int count(Player player, String keyId) {
        return begin(keyId).map(transaction -> count(player, transaction)).orElse(0);
    }

    public int count(Player player, KeyTransaction transaction) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, transaction)) amount += item.getAmount();
        }
        if (plugin.settings().consumeOffhandKeys()) {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (matches(offhand, transaction)) amount += offhand.getAmount();
        }
        return amount;
    }

    public boolean consume(Player player, String keyId, int requested) {
        Optional<KeyTransaction> transaction = begin(keyId);
        return transaction.isPresent() && consume(player, transaction.get(), requested);
    }

    public boolean consume(Player player, KeyTransaction transaction, int requested) {
        if (requested < 1 || count(player, transaction) < requested) return false;
        int remaining = requested;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack item = storage[slot];
            if (!matches(item, transaction)) continue;
            int removed = Math.min(item.getAmount(), remaining);
            remaining -= removed;
            if (removed == item.getAmount()) storage[slot] = null;
            else item.setAmount(item.getAmount() - removed);
        }
        player.getInventory().setStorageContents(storage);
        if (remaining > 0 && plugin.settings().consumeOffhandKeys()) {
            ItemStack item = player.getInventory().getItemInOffHand();
            if (matches(item, transaction)) {
                int removed = Math.min(item.getAmount(), remaining);
                remaining -= removed;
                if (removed == item.getAmount()) player.getInventory().setItemInOffHand(null);
                else item.setAmount(item.getAmount() - removed);
            }
        }
        return remaining == 0;
    }

    public void give(Player player, String keyId, int amount) {
        KeyDefinition definition = definition(keyId).orElseThrow(() -> new IllegalArgumentException("Unknown key ID"));
        ItemStack template = template(keyId).orElseThrow(() -> new IllegalArgumentException("Unresolved key ID"));
        if (definition.matchMode() == KeyMatchMode.IDENTITY_TAG) {
            template.editMeta(meta -> meta.getPersistentDataContainer().set(identityTag, PersistentDataType.STRING, definition.id()));
        }
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(stack.getMaxStackSize(), remaining));
            remaining -= stack.getAmount();
            player.getInventory().addItem(stack).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public void createCaptured(String rawId, Component displayName, ItemStack item, String editor) throws Exception {
        String id = normalize(rawId);
        if (!CrateRegistry.validId(id)) throw new IllegalArgumentException("Invalid key ID");
        if (definitions.containsKey(id)) throw new IllegalArgumentException("Key already exists");
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("A physical key template is required");
        Instant now = Instant.now();
        mutate(yaml -> {
            String path = "keys." + id;
            yaml.set(path + ".enabled", true);
            yaml.set(path + ".display-name", Text.serialize(displayName));
            yaml.set(path + ".source", "CAPTURED");
            yaml.set(path + ".external-id", "");
            yaml.set(path + ".match-mode", "EXACT");
            yaml.set(path + ".cache-last-known-good", false);
            yaml.set(path + ".item.base64", ItemCodec.capture(item, true));
            yaml.set(path + ".legacy-templates", List.of());
            yaml.set(path + ".created-at", now.toString());
            yaml.set(path + ".updated-at", now.toString());
        });
        database.audit(new DatabaseService.AuditRecord(null, editor, "CREATE", "KEY", id,
                "Captured an exact physical key template", now));
    }

    public void replaceCaptured(String rawId, Component displayName, ItemStack item,
                                boolean keepPreviousAsLegacy, String editor) throws Exception {
        String id = normalize(rawId);
        KeyDefinition definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown key ID");
        if (definition.source() == KeySource.PLEXONKEYS) {
            throw new IllegalStateException("Live PlexonKeys templates are read-only");
        }
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("A replacement template is required");
        ItemStack previous = resolve(id).map(ResolvedKey::template)
                .orElseThrow(() -> new IllegalStateException("The previous exact template is unresolved"));
        ItemStack replacement = ItemCodec.one(item);
        var legacy = new ArrayList<>(definition.legacyTemplates());
        if (keepPreviousAsLegacy && legacy.stream().noneMatch(previous::isSimilar)
                && !previous.isSimilar(replacement)) legacy.add(previous);
        Instant now = Instant.now();
        mutate(yaml -> {
            String path = "keys." + id;
            yaml.set(path + ".display-name", Text.serialize(displayName));
            yaml.set(path + ".item", null);
            yaml.set(path + ".item.base64", ItemCodec.capture(replacement, true));
            yaml.set(path + ".legacy-templates", legacy.stream().map(value -> ItemCodec.capture(value, true)).toList());
            yaml.set(path + ".updated-at", now.toString());
        });
        database.audit(new DatabaseService.AuditRecord(null, editor, "ROTATE", "KEY", id,
                keepPreviousAsLegacy ? "Replaced exact template and retained the previous template as legacy"
                        : "Replaced exact template and retired the previous template", now));
    }

    public void bindExternal(String rawId, String editor) throws Exception {
        String id = normalize(rawId);
        if (definitions.containsKey(id)) return;
        ExternalKeyDescriptor external = discovered.get(id);
        if (external == null) throw new IllegalArgumentException("PlexonKeys category is no longer available: " + id);
        Instant now = Instant.now();
        mutate(yaml -> {
            String path = "keys." + id;
            yaml.set(path + ".enabled", true);
            yaml.set(path + ".display-name", "<white><bold>" + id + " Key</bold></white>");
            yaml.set(path + ".source", "PLEXONKEYS");
            yaml.set(path + ".external-id", id);
            yaml.set(path + ".match-mode", "EXACT");
            yaml.set(path + ".cache-last-known-good", true);
            yaml.set(path + ".fallback.base64", ItemCodec.capture(external.template(), true));
            yaml.set(path + ".legacy-templates", List.of());
            yaml.set(path + ".created-at", now.toString());
            yaml.set(path + ".updated-at", now.toString());
        });
        database.audit(new DatabaseService.AuditRecord(null, editor, "BIND", "KEY", id,
                "Bound live PlexonKeys category", now));
    }

    public List<String> importDefinitions(Path sourceFile, String editor) throws Exception {
        Path source = sourceFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".yml")) {
            throw new IllegalArgumentException("Key import source must be an existing .yml file");
        }
        Snapshot imported = load(source);
        List<KeyDefinition> additions = imported.definitions().values().stream()
                .sorted(Comparator.comparing(KeyDefinition::id)).toList();
        for (KeyDefinition definition : additions) {
            if (definitions.containsKey(definition.id())) {
                throw new IllegalArgumentException("Key import conflicts with existing ID: " + definition.id());
            }
        }
        Instant now = Instant.now();
        mutate(yaml -> additions.forEach(definition -> writeDefinition(yaml, definition, now)));
        for (KeyDefinition definition : additions) {
            database.audit(new DatabaseService.AuditRecord(null, editor, "IMPORT", "KEY", definition.id(),
                    "Imported a validated physical key definition", now));
        }
        return additions.stream().map(KeyDefinition::id).toList();
    }

    public void delete(String rawId) throws Exception {
        delete(rawId, "CONSOLE");
    }

    public void delete(String rawId, String editor) throws Exception {
        String id = normalize(rawId);
        if (!definitions.containsKey(id)) throw new IllegalArgumentException("Unknown key ID");
        mutate(yaml -> yaml.set("keys." + id, null));
        var nextCache = new LinkedHashMap<>(lastKnownGood);
        nextCache.remove(id);
        lastKnownGood = normalizedCopies(nextCache);
        database.removeKeyTemplateCache(id);
        database.audit(new DatabaseService.AuditRecord(null, editor, "DELETE", "KEY", id,
                "Deleted an unused physical key definition after confirmation", Instant.now()));
    }

    public void syncDiscovery() {
        if (!plugin.settings().plexonKeysEnabled()) discovered = Map.of();
        else discovered = provider.discover();
        collisions = detectCollisions();
    }

    public Collection<KeyDefinition> definitions() {
        return definitions.values().stream().sorted(Comparator.comparing(KeyDefinition::id)).toList();
    }

    public Optional<KeyDefinition> definition(String id) {
        return Optional.ofNullable(definitions.get(normalize(id)));
    }

    public Map<String, ExternalKeyDescriptor> discovered() { return discovered; }
    public Set<String> collisions() { return collisions; }
    public ProviderStatus providerStatus() { return provider.status(); }
    public String providerDiagnostic() { return provider.diagnostic(); }

    public List<String> unresolved() {
        return definitions.values().stream().filter(KeyDefinition::enabled)
                .map(KeyDefinition::id).filter(id -> resolve(id).isEmpty()).sorted().toList();
    }

    public String sourceLabel() {
        return provider.status() == ProviderStatus.READY && plugin.settings().plexonKeysEnabled()
                ? "PlexonKeys live templates" : "exact cached/configured templates";
    }

    private Set<String> detectCollisions() {
        var accepted = new ArrayList<Map.Entry<String, ItemStack>>();
        for (KeyDefinition definition : definitions.values()) {
            if (!definition.enabled() || definition.matchMode() == KeyMatchMode.IDENTITY_TAG) continue;
            begin(definition.id()).ifPresent(transaction -> transaction.acceptedTemplates()
                    .forEach(template -> accepted.add(Map.entry(definition.id(), template))));
        }
        var found = new LinkedHashSet<String>();
        for (int left = 0; left < accepted.size(); left++) {
            for (int right = left + 1; right < accepted.size(); right++) {
                if (accepted.get(left).getKey().equals(accepted.get(right).getKey())) continue;
                if (accepted.get(left).getValue().isSimilar(accepted.get(right).getValue())) {
                    found.add(accepted.get(left).getKey());
                    found.add(accepted.get(right).getKey());
                }
            }
        }
        return Set.copyOf(found);
    }

    private void cache(String keyId, ItemStack template) {
        ItemStack previous = lastKnownGood.get(keyId);
        if (previous != null && previous.isSimilar(template)) return;
        var next = new LinkedHashMap<>(lastKnownGood);
        next.put(keyId, ItemCodec.one(template));
        lastKnownGood = normalizedCopies(next);
        database.cacheKeyTemplate(keyId, template);
    }

    private void mutate(ThrowingConsumer<YamlConfiguration> change) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        change.accept(yaml);
        Snapshot parsed;
        Path temporary = Files.createTempFile(file.getParent(), "keys-validate-", ".yml");
        try {
            AtomicFiles.write(temporary, yaml.saveToString());
            parsed = load(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
        AtomicFiles.write(file, yaml.saveToString());
        definitions = parsed.definitions();
        syncDiscovery();
    }

    private static void writeDefinition(YamlConfiguration yaml, KeyDefinition definition, Instant importedAt) {
        String path = "keys." + definition.id();
        yaml.set(path, null);
        yaml.set(path + ".enabled", definition.enabled());
        yaml.set(path + ".display-name", Text.serialize(definition.displayName()));
        yaml.set(path + ".source", definition.source().name());
        yaml.set(path + ".external-id", definition.externalId());
        yaml.set(path + ".match-mode", definition.matchMode().name());
        yaml.set(path + ".cache-last-known-good", definition.cacheLastKnownGood());
        yaml.set(path + ".icon.base64", ItemCodec.capture(definition.icon(), true));
        ItemStack owned = definition.ownedTemplate();
        if (owned != null) yaml.set(path + ".item.base64", ItemCodec.capture(owned, true));
        ItemStack fallback = definition.fallbackTemplate();
        if (fallback != null) yaml.set(path + ".fallback.base64", ItemCodec.capture(fallback, true));
        yaml.set(path + ".legacy-templates", definition.legacyTemplates().stream()
                .map(item -> ItemCodec.capture(item, true)).toList());
        yaml.set(path + ".created-at", definition.createdAt().equals(Instant.EPOCH)
                ? importedAt.toString() : definition.createdAt().toString());
        yaml.set(path + ".updated-at", importedAt.toString());
    }

    private static Map<String, ItemStack> normalizedCopies(Map<String, ItemStack> values) {
        var result = new LinkedHashMap<String, ItemStack>();
        values.forEach((id, item) -> result.put(normalize(id), ItemCodec.one(item)));
        return Collections.unmodifiableMap(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        try { return Instant.parse(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid key timestamp: " + value, error); }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String path) {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid " + path + ": " + value, error); }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> { void accept(T value) throws Exception; }
}

from pathlib import Path

path = Path('src/main/java/com/antondev/crates/service/KeyService.java')
text = path.read_text()

# Extract the YAML parser from load(Path) so prepared mutations can be validated entirely in memory.
start = text.index('    public static Snapshot load(Path file) {')
end = text.index('    /**\n     * Reconstructs the key registry', start)
old = text[start:end]
if 'YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());' not in old:
    raise SystemExit('KeyService load(Path) block changed unexpectedly')
body = old[old.index('        if (yaml.getInt'):]
# remove the method's final closing brace from the captured parser body
last = body.rfind('    }\n')
parser_body = body[:last]
replacement = '''    public static Snapshot load(Path file) {
        return parseSnapshot(YamlConfiguration.loadConfiguration(file.toFile()));
    }

    private static Snapshot parseSnapshot(YamlConfiguration yaml) {
''' + parser_body + '''    }

'''
text = text[:start] + replacement + text[end:]

# Add immutable prepared-mutation carrier after KeyTransaction.
marker = '    private final PlexonCrates plugin;\n'
if marker not in text:
    raise SystemExit('KeyService field marker missing')
record = '''    public record PreparedMutation(Snapshot snapshot, String mirrorPayload,
                                   List<DatabaseService.AuditRecord> audits) {
        public PreparedMutation {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            mirrorPayload = java.util.Objects.requireNonNull(mirrorPayload, "mirrorPayload");
            audits = List.copyOf(audits);
        }
    }

'''
text = text.replace(marker, record + marker, 1)

# Add staged mutation API before ordinary live createCaptured methods.
marker = '    public void createCaptured(String rawId, Component displayName, ItemStack item, String editor) throws Exception {\n'
if marker not in text:
    raise SystemExit('createCaptured marker missing')
staged = '''    /** Starts an in-memory mutation from the current complete key registry without reading keys.yml. */
    public PreparedMutation beginPreparedMutation() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 2);
        definitions.values().stream().sorted(Comparator.comparing(KeyDefinition::id)).forEach(definition -> {
            Instant preserved = definition.updatedAt().equals(Instant.EPOCH) ? Instant.now() : definition.updatedAt();
            writeDefinition(yaml, definition, preserved);
        });
        return prepared(yaml, List.of());
    }

    public PreparedMutation prepareCreateCaptured(PreparedMutation base, String rawId, Component displayName,
                                                   ItemStack item, String editor) throws Exception {
        java.util.Objects.requireNonNull(base, "base");
        String id = normalize(rawId);
        if (!CrateRegistry.validId(id)) throw new IllegalArgumentException("Invalid key ID");
        if (base.snapshot().definitions().containsKey(id)) throw new IllegalArgumentException("Key already exists");
        if (item == null || item.getType().isAir()) throw new IllegalArgumentException("A physical key template is required");
        Instant now = Instant.now();
        YamlConfiguration yaml = preparedYaml(base);
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
        var audits = new ArrayList<>(base.audits());
        audits.add(new DatabaseService.AuditRecord(null, editor, "CREATE", "KEY", id,
                "Captured an exact physical key template", now));
        return prepared(yaml, audits);
    }

    public PreparedMutation prepareBindExternal(PreparedMutation base, String rawId, String editor) throws Exception {
        java.util.Objects.requireNonNull(base, "base");
        String id = normalize(rawId);
        if (base.snapshot().definitions().containsKey(id)) return base;
        ExternalKeyDescriptor external = discovered.get(id);
        if (external == null) throw new IllegalArgumentException("PlexonKeys category is no longer available: " + id);
        Instant now = Instant.now();
        YamlConfiguration yaml = preparedYaml(base);
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
        var audits = new ArrayList<>(base.audits());
        audits.add(new DatabaseService.AuditRecord(null, editor, "BIND", "KEY", id,
                "Bound live PlexonKeys category", now));
        return prepared(yaml, audits);
    }

    public Optional<KeyDefinition> definition(PreparedMutation prepared, String id) {
        java.util.Objects.requireNonNull(prepared, "prepared");
        return Optional.ofNullable(prepared.snapshot().definitions().get(normalize(id)));
    }

    /** Resolves a staged definition without mutating the live provider cache. */
    public Optional<ItemStack> template(PreparedMutation prepared, String id) {
        KeyDefinition definition = definition(prepared, id).orElse(null);
        if (definition == null || !definition.enabled()) return Optional.empty();
        if (definition.source() == KeySource.PLEXONKEYS) {
            if (plugin.settings().plexonKeysEnabled() && plugin.settings().plexonKeysMode().equals("LIVE_FIRST")) {
                Optional<ItemStack> live = provider.resolve(definition.externalId());
                if (live.isPresent()) return Optional.of(ItemCodec.one(live.get()));
            }
            ItemStack cached = lastKnownGood.get(definition.id());
            if (cached != null) return Optional.of(ItemCodec.one(cached));
            ItemStack fallback = definition.fallbackTemplate();
            return fallback == null ? Optional.empty() : Optional.of(ItemCodec.one(fallback));
        }
        ItemStack owned = definition.ownedTemplate();
        return owned == null ? Optional.empty() : Optional.of(ItemCodec.one(owned));
    }

    /** The only filesystem stage for a prepared key mutation. Safe to execute on the plugin I/O pool. */
    public void writePreparedMutation(PreparedMutation prepared) throws Exception {
        java.util.Objects.requireNonNull(prepared, "prepared");
        AtomicFiles.write(file, prepared.mirrorPayload());
    }

    /** Applies an already-durable candidate to live memory and emits its audit records. */
    public void installPreparedMutation(PreparedMutation prepared) {
        java.util.Objects.requireNonNull(prepared, "prepared");
        definitions = prepared.snapshot().definitions();
        syncDiscovery();
        for (DatabaseService.AuditRecord audit : prepared.audits()) database.audit(audit);
    }

    private PreparedMutation prepared(YamlConfiguration yaml, List<DatabaseService.AuditRecord> audits) {
        yaml.set("config-version", 2);
        Snapshot parsed = parseSnapshot(yaml);
        return new PreparedMutation(parsed, yaml.saveToString(), audits);
    }

    private static YamlConfiguration preparedYaml(PreparedMutation prepared) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(prepared.mirrorPayload());
        return yaml;
    }

'''
text = text.replace(marker, staged + marker, 1)
path.write_text(text)

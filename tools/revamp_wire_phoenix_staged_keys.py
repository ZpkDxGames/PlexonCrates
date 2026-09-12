from pathlib import Path

path = Path('src/main/java/com/antondev/crates/migration/phoenix/PhoenixMigrationService.java')
text = path.read_text()

if 'import com.antondev.crates.service.KeyService;\n' not in text:
    text = text.replace('import com.antondev.crates.service.LocationStore;\n',
                        'import com.antondev.crates.service.KeyService;\nimport com.antondev.crates.service.LocationStore;\n', 1)

old = '''        return plugin.io().submit(() -> prepareAsyncImport(planningState))
                .thenCompose(prepared -> primary(() -> new KeyedImport(prepared,
                        ensureKeys(prepared.fixture(), actor))))
                .thenCompose(keyed -> plugin.io().submit(() -> buildDraftPayloads(keyed)))
'''
new = '''        return plugin.io().submit(() -> prepareAsyncImport(planningState))
                .thenCompose(prepared -> primary(() -> prepareKeys(prepared, actor)))
                .thenCompose(preparedKeys -> plugin.io().submit(() -> writePreparedKeys(preparedKeys)))
                .thenCompose(preparedKeys -> primary(() -> installPreparedKeys(preparedKeys)))
                .thenCompose(keyed -> plugin.io().submit(() -> buildDraftPayloads(keyed)))
'''
if old not in text:
    raise SystemExit('async Phoenix key stage changed unexpectedly')
text = text.replace(old, new, 1)

marker = '    private DraftPayloadBatch buildDraftPayloads(KeyedImport keyed) throws Exception {\n'
if marker not in text:
    raise SystemExit('buildDraftPayloads marker missing')
methods = '''    private PreparedKeyImport prepareKeys(AsyncImportPreparation prepared, String actor) throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Phoenix key preparation requires primary thread");
        KeyService.PreparedMutation mutation = plugin.keys().beginPreparedMutation();
        var mappings = new LinkedHashMap<String, KeyMapping>();
        for (PhoenixKey source : prepared.fixture().keys().values().stream()
                .sorted(Comparator.comparing(PhoenixKey::sourceId)).toList()) {
            String preferred = source.targetId();
            if (plugin.keys().definition(mutation, preferred).isEmpty()
                    && plugin.keys().discovered().containsKey(preferred)) {
                mutation = plugin.keys().prepareBindExternal(mutation, preferred, actor);
            }
            if (plugin.keys().definition(mutation, preferred).isEmpty()) {
                mutation = plugin.keys().prepareCreateCaptured(
                        mutation, preferred, keyDisplayName(source), source.template(), actor);
            }
            ItemStack current = plugin.keys().template(mutation, preferred).orElseThrow(() ->
                    new IllegalStateException("Mapped Plexon key is unresolved: " + preferred));
            if (ItemCodec.one(current).isSimilar(ItemCodec.one(source.template()))) {
                mappings.put(source.sourceId().toLowerCase(Locale.ROOT),
                        new KeyMapping(source.sourceId(), List.of(preferred), false));
                continue;
            }
            String legacy = legacyKeyId(preferred);
            if (plugin.keys().definition(mutation, legacy).isEmpty()) {
                mutation = plugin.keys().prepareCreateCaptured(
                        mutation, legacy, keyDisplayName(source), source.template(), actor);
            } else {
                ItemStack existingLegacy = plugin.keys().template(mutation, legacy).orElseThrow(() ->
                        new IllegalStateException("Existing Phoenix legacy key is unresolved: " + legacy));
                if (!ItemCodec.one(existingLegacy).isSimilar(ItemCodec.one(source.template()))) {
                    throw new IllegalStateException("Legacy key ID conflicts with a different exact item: " + legacy);
                }
            }
            mappings.put(source.sourceId().toLowerCase(Locale.ROOT),
                    new KeyMapping(source.sourceId(), List.of(preferred, legacy), true));
        }
        return new PreparedKeyImport(prepared, mutation, Map.copyOf(mappings));
    }

    private PreparedKeyImport writePreparedKeys(PreparedKeyImport prepared) throws Exception {
        plugin.keys().writePreparedMutation(prepared.mutation());
        return prepared;
    }

    private KeyedImport installPreparedKeys(PreparedKeyImport prepared) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Phoenix key activation requires primary thread");
        plugin.keys().installPreparedMutation(prepared.mutation());
        return new KeyedImport(prepared.prepared(), prepared.keyMappings());
    }

'''
text = text.replace(marker, methods + marker, 1)

record_marker = '    private record KeyedImport(AsyncImportPreparation prepared, Map<String, KeyMapping> keyMappings) {\n'
if record_marker not in text:
    raise SystemExit('KeyedImport record marker missing')
record = '''    private record PreparedKeyImport(AsyncImportPreparation prepared, KeyService.PreparedMutation mutation,
                                     Map<String, KeyMapping> keyMappings) {
        private PreparedKeyImport { keyMappings = Map.copyOf(keyMappings); }
    }
'''
text = text.replace(record_marker, record + record_marker, 1)
path.write_text(text)

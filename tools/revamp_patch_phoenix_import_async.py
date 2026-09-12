from pathlib import Path

SERVICE = Path('src/main/java/com/antondev/crates/migration/phoenix/PhoenixMigrationService.java')
COMMAND = Path('src/main/java/com/antondev/crates/command/PhoenixMigrationCommand.java')

text = SERVICE.read_text()
if 'import java.util.concurrent.CompletableFuture;' not in text:
    text = text.replace('import java.util.UUID;\n', 'import java.util.UUID;\nimport java.util.concurrent.CompletableFuture;\n', 1)

marker = '    /** Compatibility overload; live imports require the plugin-aware constructor and actor metadata. */\n'
if marker not in text:
    raise SystemExit('Phoenix compatibility import marker missing')

async_block = '''    /**
     * Live destructive import coordinator. Heavy source/database/filesystem work stays on the bounded
     * plugin I/O pool; Bukkit registry/world mutations are marshalled back to the primary thread.
     */
    public CompletableFuture<ImportResult> importToDraftsAsync(
            PlanningState planningState, UUID actorId, String actorName) {
        requirePlugin();
        Objects.requireNonNull(planningState, "planningState");
        String actor = actorName == null || actorName.isBlank() ? "CONSOLE" : actorName;
        return plugin.io().submit(() -> prepareAsyncImport(planningState))
                .thenCompose(prepared -> primary(() -> new KeyedImport(prepared,
                        ensureKeys(prepared.fixture(), actor))))
                .thenCompose(keyed -> plugin.io().submit(() -> buildDraftPayloads(keyed)))
                .thenCompose(payloads -> primary(() -> prepareDraftBatch(payloads, actor)))
                .thenCompose(batch -> plugin.io().submit(() -> writeDraftBatch(batch)))
                .thenCompose(batch -> primary(() -> installDraftBatch(batch)))
                .thenCompose(installed -> plugin.io().submit(() -> persistAsyncImport(installed, actorId, actor)))
                .thenCompose(persisted -> primary(() -> activatePersistedImport(persisted)));
    }

    private AsyncImportPreparation prepareAsyncImport(PlanningState planningState) throws Exception {
        ScanResult currentScan = scan();
        PlanResult currentPlan = plan(currentScan, planningState);
        if (!currentPlan.importEnabled()) {
            throw new IllegalStateException("Phoenix import plan is blocked: "
                    + String.join("; ", currentPlan.warnings()));
        }
        Fixture fixture = adapter.load(sourceRoot);
        Instant importedAt = Instant.now();
        String shortFingerprint = currentScan.fingerprint().substring(0, 12);
        Path backup = dataRoot.resolve("backups").resolve("phoenix-import-"
                + REPORT_TIME.format(importedAt) + "-" + shortFingerprint).normalize();
        if (!backup.startsWith(dataRoot.resolve("backups").normalize())) {
            throw new IllegalStateException("Unsafe Phoenix migration backup path");
        }
        Files.createDirectories(backup);
        copyTree(fixture.root(), backup.resolve("PhoenixCratesLite"));
        plugin.database().createBackup(dataRoot, backup.resolve("PlexonCrates")).join();
        return new AsyncImportPreparation(currentScan, currentPlan, fixture, backup, importedAt);
    }

    private DraftPayloadBatch buildDraftPayloads(KeyedImport keyed) throws Exception {
        AsyncImportPreparation prepared = keyed.prepared();
        Path generated = generatedRoot.resolve(prepared.scan().fingerprint()).normalize();
        if (!generated.startsWith(generatedRoot)) throw new IllegalStateException("Unsafe generated migration path");
        Files.createDirectories(generated);
        var payloads = new ArrayList<DraftPayload>();
        for (PhoenixCrate source : prepared.fixture().crates()) {
            List<String> acceptedKeys = new ArrayList<>();
            for (String sourceKeyId : source.sourceKeyIds()) {
                PhoenixKey sourceKey = prepared.fixture().keys().get(sourceKeyId.toLowerCase(Locale.ROOT));
                if (sourceKey == null) throw new IllegalStateException("Unknown Phoenix key " + sourceKeyId);
                KeyMapping mapping = keyed.keyMappings().get(sourceKey.sourceId().toLowerCase(Locale.ROOT));
                if (mapping == null) throw new IllegalStateException("Missing key mapping for " + sourceKey.sourceId());
                acceptedKeys.addAll(mapping.acceptedIds());
            }
            String yaml = crateYaml(source, acceptedKeys, prepared.scan().fingerprint(), prepared.importedAt());
            Path definition = generated.resolve(source.targetId() + ".yml").normalize();
            if (!definition.getParent().equals(generated)) throw new IllegalStateException("Unsafe generated crate path");
            Files.writeString(definition, yaml, StandardCharsets.UTF_8);
            payloads.add(new DraftPayload(source.targetId(), yaml));
        }
        return new DraftPayloadBatch(keyed, List.copyOf(payloads));
    }

    private PreparedDraftBatch prepareDraftBatch(DraftPayloadBatch payloads, String actor) throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Phoenix draft preparation requires primary thread");
        var current = plugin.crates().snapshot();
        Map<String, byte[]> currentPayloads = current.payloads();
        var prepared = new ArrayList<com.antondev.crates.service.CrateRegistry.PreparedDraftImport>();
        int skipped = 0;
        for (DraftPayload payload : payloads.payloads()) {
            if (current.crates().containsKey(payload.crateId())) {
                byte[] existing = currentPayloads.get(payload.crateId());
                String fingerprint = existing == null ? "" : migrationFingerprint(existing);
                if (payloads.keyed().prepared().scan().fingerprint().equals(fingerprint)) {
                    skipped++;
                    continue;
                }
                throw new IllegalStateException("Target crate ID already exists: " + payload.crateId());
            }
            prepared.add(plugin.crates().prepareImportedDraft(payload.yaml(), payload.crateId(), actor));
        }
        return new PreparedDraftBatch(payloads, List.copyOf(prepared), skipped);
    }

    private PreparedDraftBatch writeDraftBatch(PreparedDraftBatch batch) throws Exception {
        for (var prepared : batch.prepared()) plugin.crates().writeImportedDraft(prepared);
        return batch;
    }

    private InstalledBatch installDraftBatch(PreparedDraftBatch batch) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Phoenix draft activation requires primary thread");
        var imported = new ArrayList<Crate>();
        for (var prepared : batch.prepared()) imported.add(plugin.crates().installImportedDraft(prepared));
        var crateKeyIds = new LinkedHashMap<String, String>();
        for (PhoenixCrate source : batch.payloads().keyed().prepared().fixture().crates()) {
            plugin.crates().find(source.targetId()).ifPresent(crate -> crateKeyIds.put(crate.id(), crate.keyId()));
        }
        var worldIds = new LinkedHashMap<String, UUID>();
        for (PhoenixLocation location : batch.payloads().keyed().prepared().fixture().locations()) {
            World world = Bukkit.getWorld(location.worldName());
            worldIds.put(location.worldName().toLowerCase(Locale.ROOT), world == null ? null : world.getUID());
        }
        return new InstalledBatch(batch, List.copyOf(imported), Map.copyOf(crateKeyIds), worldIds);
    }

    private PersistedImport persistAsyncImport(InstalledBatch installed, UUID actorId, String actor) throws Exception {
        AsyncImportPreparation prepared = installed.batch().payloads().keyed().prepared();
        for (Crate imported : installed.imported()) {
            plugin.database().audit(new DatabaseService.AuditRecord(actorId, actor, "MIGRATE", "CRATE",
                    imported.id(), "Imported PhoenixCratesLite definition as DRAFT from "
                            + prepared.scan().fingerprint(), prepared.importedAt())).join();
        }
        HistoryImport history = importAggregateHistoryAsync(prepared.fixture(), prepared.scan().fingerprint(),
                prepared.importedAt(), installed.crateKeyIds());
        int locations = importLocationsAsync(prepared.fixture(), prepared.importedAt(), installed.worldIds());
        List<DatabaseService.StoredLocation> storedLocations = plugin.database().loadLocations();
        long orphanWins = prepared.fixture().orphanRewardWins().values().stream().mapToLong(Long::longValue).sum();
        ImportResult partial = new ImportResult(prepared.scan().fingerprint(), prepared.backup(), null,
                installed.imported().size(), installed.batch().skipped(), history.rows(), locations,
                orphanWins, prepared.importedAt());
        Path report = writeReport(prepared.scan(), prepared.plan(), partial);
        Path marker = reportRoot.resolve("phoenix-" + prepared.scan().fingerprint() + ".imported").normalize();
        if (!marker.getParent().equals(reportRoot)) throw new IllegalStateException("Unsafe migration marker path");
        Files.writeString(marker, "source=" + SOURCE + "\nfingerprint=" + prepared.scan().fingerprint()
                + "\nimported-at=" + prepared.importedAt() + "\nbackup=" + dataRoot.relativize(prepared.backup())
                + "\nreport=" + dataRoot.relativize(report) + "\n", StandardCharsets.UTF_8);
        plugin.database().audit(new DatabaseService.AuditRecord(actorId, actor, "MIGRATE", "PHOENIX",
                prepared.scan().fingerprint(), "Imported PhoenixCratesLite source into " + installed.imported().size()
                        + " crate drafts; report=" + report.getFileName(), prepared.importedAt())).join();
        ImportResult result = new ImportResult(prepared.scan().fingerprint(), prepared.backup(), report,
                installed.imported().size(), installed.batch().skipped(), history.rows(), locations,
                orphanWins, prepared.importedAt());
        return new PersistedImport(result, storedLocations, history.stats());
    }

    private ImportResult activatePersistedImport(PersistedImport persisted) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Phoenix import activation requires primary thread");
        for (StatDelta delta : persisted.stats()) {
            plugin.statistics().record(delta.playerId(), delta.crateId(), delta.amount());
        }
        plugin.locations().apply(LocationStore.fromDatabase(persisted.locations(), plugin.crates()));
        return persisted.result();
    }

    private HistoryImport importAggregateHistoryAsync(Fixture fixture, String fingerprint, Instant importedAt,
                                                       Map<String, String> crateKeyIds) throws Exception {
        Map<String, String> crateTargets = fixture.crates().stream().collect(java.util.stream.Collectors.toMap(
                PhoenixCrate::sourceId, PhoenixCrate::targetId, (left, right) -> left, LinkedHashMap::new));
        int imported = 0;
        var stats = new ArrayList<StatDelta>();
        for (PhoenixPlayer player : fixture.players()) {
            Set<UUID> existingTransactions = plugin.database().history(player.playerId(), 100, 0).stream()
                    .map(DatabaseService.OpeningRecord::transactionId)
                    .collect(java.util.stream.Collectors.toSet());
            for (Map.Entry<String, Long> opening : player.openedCrates().entrySet()) {
                if (opening.getValue() <= 0) continue;
                String target = crateTargets.get(opening.getKey());
                if (target == null) continue;
                if (opening.getValue() > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Phoenix opening count exceeds PlexonCrates aggregate history limit");
                }
                UUID transaction = UUID.nameUUIDFromBytes(("PlexonCrates:Phoenix:" + fingerprint + ":"
                        + player.playerId() + ":" + opening.getKey()).getBytes(StandardCharsets.UTF_8));
                if (existingTransactions.contains(transaction)) continue;
                List<String> wins = player.rewardWins().entrySet().stream()
                        .filter(entry -> opening.getKey().equals(fixture.rewardToCrate().get(entry.getKey())))
                        .filter(entry -> entry.getValue() > 0)
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
                String rewardSummary = wins.isEmpty() ? "aggregate:no-current-reward-wins" : String.join(",", wins);
                String keyId = crateKeyIds.getOrDefault(target, "");
                int amount = opening.getValue().intValue();
                DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transaction,
                        player.playerId(), player.playerName(), target, keyId, 0, amount,
                        "PHOENIX_MIGRATION", rewardSummary, "PHOENIX_AGGREGATE", 0,
                        "Imported aggregate Phoenix history; source database has no per-opening timestamp. sourceCrate="
                                + opening.getKey(), importedAt);
                plugin.database().completeOpening(record).join();
                stats.add(new StatDelta(player.playerId(), target, amount));
                imported++;
            }
        }
        return new HistoryImport(imported, List.copyOf(stats));
    }

    private int importLocationsAsync(Fixture fixture, Instant importedAt, Map<String, UUID> worldIds) {
        Map<String, String> crateTargets = fixture.crates().stream().collect(java.util.stream.Collectors.toMap(
                PhoenixCrate::sourceId, PhoenixCrate::targetId, (left, right) -> left, LinkedHashMap::new));
        int count = 0;
        for (PhoenixLocation location : fixture.locations()) {
            String target = crateTargets.get(location.sourceCrateId());
            if (target == null) continue;
            UUID worldUuid = worldIds.get(location.worldName().toLowerCase(Locale.ROOT));
            plugin.database().saveLocation(new DatabaseService.StoredLocation(worldUuid, location.worldName(),
                    location.x(), location.y(), location.z(), target, importedAt)).join();
            count++;
        }
        return count;
    }

'''
text = text.replace(marker, async_block + marker, 1)

helper_marker = '    private void requirePlugin() {\n'
if helper_marker not in text:
    raise SystemExit('requirePlugin marker missing')
helper = '''    private <T> CompletableFuture<T> primary(CheckedSupplier<T> supplier) {
        var future = new CompletableFuture<T>();
        Runnable task = () -> {
            try {
                if (!plugin.isEnabled()) throw new IllegalStateException("Plugin disabled during Phoenix migration");
                future.complete(supplier.get());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
        return future;
    }

'''
text = text.replace(helper_marker, helper + helper_marker, 1)

record_marker = '    private record KeyMapping(String sourceId, List<String> acceptedIds, boolean legacyAdded) {\n'
if record_marker not in text:
    raise SystemExit('KeyMapping record marker missing')
records = '''    private record AsyncImportPreparation(ScanResult scan, PlanResult plan, Fixture fixture,
                                          Path backup, Instant importedAt) {}
    private record KeyedImport(AsyncImportPreparation prepared, Map<String, KeyMapping> keyMappings) {
        private KeyedImport { keyMappings = Map.copyOf(keyMappings); }
    }
    private record DraftPayload(String crateId, String yaml) {}
    private record DraftPayloadBatch(KeyedImport keyed, List<DraftPayload> payloads) {
        private DraftPayloadBatch { payloads = List.copyOf(payloads); }
    }
    private record PreparedDraftBatch(DraftPayloadBatch payloads,
                                      List<com.antondev.crates.service.CrateRegistry.PreparedDraftImport> prepared,
                                      int skipped) {
        private PreparedDraftBatch { prepared = List.copyOf(prepared); }
    }
    private record InstalledBatch(PreparedDraftBatch batch, List<Crate> imported,
                                  Map<String, String> crateKeyIds, Map<String, UUID> worldIds) {
        private InstalledBatch {
            imported = List.copyOf(imported);
            crateKeyIds = Map.copyOf(crateKeyIds);
            worldIds = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(worldIds));
        }
    }
    private record StatDelta(UUID playerId, String crateId, int amount) {}
    private record HistoryImport(int rows, List<StatDelta> stats) {
        private HistoryImport { stats = List.copyOf(stats); }
    }
    private record PersistedImport(ImportResult result, List<DatabaseService.StoredLocation> locations,
                                   List<StatDelta> stats) {
        private PersistedImport {
            locations = List.copyOf(locations);
            stats = List.copyOf(stats);
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

'''
text = text.replace(record_marker, records + record_marker, 1)
SERVICE.write_text(text)

command = COMMAND.read_text()
command = command.replace('case "import" -> importData(service, sender, args);',
                          'case "import" -> importData(plugin, service, sender, args);', 1)
start = command.index('    private static void importData(PhoenixMigrationService service, CommandSender sender, String[] args) throws Exception {')
end = command.index('    private static <T> void async(', start)
replacement = '''    private static void importData(PlexonCrates plugin, PhoenixMigrationService service,
                                   CommandSender sender, String[] args) {
        if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
            sender.sendMessage(Text.parse("<yellow>This step creates PlexonCrates data and aggregate history.</yellow>"));
            sender.sendMessage(Text.parse("<gray>It never modifies Phoenix source files, but it must be explicitly confirmed:</gray>"));
            sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix import confirm</white>"));
            return;
        }
        UUID actorId = sender instanceof Player player ? player.getUniqueId() : null;
        String actorName = sender.getName();
        PhoenixMigrationService.PlanningState state = service.capturePlanningState();
        sender.sendMessage(Text.parse("<aqua>Creating Phoenix backup and importing drafts off-thread…</aqua>"));
        service.importToDraftsAsync(state, actorId, actorName).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (error != null) fail(plugin, sender, error);
                else renderImport(sender, result);
            });
        });
    }

    private static void renderImport(CommandSender sender, PhoenixMigrationService.ImportResult result) {
        sender.sendMessage(Text.parse("<gradient:#FF9F2E:#FFF0B2><bold>Phoenix Import Complete</bold></gradient>"));
        sender.sendMessage(Text.parse("<gray>Crate drafts:</gray> <white>" + result.importedCrates()
                + " imported</white> <dark_gray>•</dark_gray> <white>" + result.skippedCrates() + " already present</white>"));
        sender.sendMessage(Text.parse("<gray>Aggregate history rows:</gray> <white>" + result.historyRows()
                + "</white> <dark_gray>•</dark_gray> <gray>Locations:</gray> <white>" + result.locations() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Orphan historical reward wins:</gray> <yellow>" + result.orphanRewardWins() + "</yellow>"));
        sender.sendMessage(Text.parse("<gray>Backup:</gray> <white>" + escape(result.backupDirectory().getFileName().toString()) + "</white>"));
        sender.sendMessage(Text.parse("<gray>Report:</gray> <white>" + escape(result.report().getFileName().toString()) + "</white>"));
        sender.sendMessage(Text.parse("<yellow>Imported crates remain DRAFT. Review and validate them before publishing.</yellow>"));
    }

'''
command = command[:start] + replacement + command[end:]
COMMAND.write_text(command)

package com.antondev.crates.migration.phoenix;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.Fixture;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.PhoenixCrate;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.PhoenixKey;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.PhoenixLocation;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.PhoenixPlayer;
import com.antondev.crates.migration.phoenix.PhoenixFixtureAdapter.PhoenixReward;
import com.antondev.crates.model.Crate;
import com.antondev.crates.service.LocationStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Read-only PhoenixCratesLite migration boundary and importer.
 *
 * <p>The source tree is never changed. The only supported schema is the exact
 * operator-owned PlexonCraft fixture that was supplied for the 3.0 migration.
 * Imported crates remain drafts and must pass the ordinary PlexonCrates publish
 * flow after operator review.</p>
 */
public final class PhoenixMigrationService {
    public static final long MAX_SOURCE_FILE_BYTES = 64L * 1024L * 1024L;
    private static final DateTimeFormatter REPORT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String SOURCE = "PHOENIXCRATESLITE";

    private final PlexonCrates plugin;
    private final Path dataRoot;
    private final Path sourceRoot;
    private final Path reportRoot;
    private final Path generatedRoot;
    private final PhoenixFixtureAdapter adapter = new PhoenixFixtureAdapter();

    /** Scan-only constructor retained for filesystem/security tests. */
    public PhoenixMigrationService(Path pluginDataFolder) {
        this(null, pluginDataFolder);
    }

    public PhoenixMigrationService(PlexonCrates plugin) {
        this(Objects.requireNonNull(plugin, "plugin"), plugin.getDataFolder().toPath());
    }

    private PhoenixMigrationService(PlexonCrates plugin, Path pluginDataFolder) {
        this.plugin = plugin;
        this.dataRoot = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder").toAbsolutePath().normalize();
        this.sourceRoot = dataRoot.resolve("imports").resolve("phoenix").normalize();
        this.reportRoot = dataRoot.resolve("migration-reports").normalize();
        this.generatedRoot = dataRoot.resolve("imports").resolve("phoenix-generated").normalize();
        if (!sourceRoot.startsWith(dataRoot) || !reportRoot.startsWith(dataRoot)
                || !generatedRoot.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Phoenix migration paths must remain inside PlexonCrates data");
        }
    }

    public Path sourceRoot() { return sourceRoot; }
    public Path reportRoot() { return reportRoot; }

    /** Fingerprints every source file without following links or opening anything for write. */
    public ScanResult scan() throws IOException {
        Files.createDirectories(sourceRoot);
        Files.createDirectories(reportRoot);
        List<SourceFile> files = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot)) {
            for (Path candidate : stream.sorted().toList()) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (!normalized.startsWith(sourceRoot)) {
                    throw new IOException("Phoenix source path escapes configured import root: " + candidate);
                }
                if (candidate.equals(sourceRoot)) continue;
                if (Files.isSymbolicLink(candidate)) {
                    throw new IOException("Symbolic links are not permitted in Phoenix migration sources: "
                            + sourceRoot.relativize(candidate));
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                long size = Files.size(candidate);
                if (size > MAX_SOURCE_FILE_BYTES) {
                    throw new IOException("Phoenix source file exceeds the 64 MiB scan limit: "
                            + sourceRoot.relativize(candidate));
                }
                String relative = portableRelative(sourceRoot.relativize(normalized));
                files.add(new SourceFile(relative, size, sha256(candidate)));
            }
        }
        files.sort(Comparator.comparing(SourceFile::relativePath));
        String fingerprint = fingerprint(files);
        List<String> warnings = files.isEmpty()
                ? List.of("No Phoenix migration fixture files were found under imports/phoenix.")
                : List.of();
        return new ScanResult(Instant.now(), fingerprint, List.copyOf(files), warnings);
    }

    /** Parses the supplied live fixture and produces a non-mutating mapping plan. */
    public PlanResult plan(ScanResult scan) {
        Objects.requireNonNull(scan, "scan");
        if (scan.files().isEmpty()) {
            return blockedPlan(scan, "No Phoenix source files were found.", List.of());
        }
        try {
            Fixture fixture = adapter.load(sourceRoot);
            var entries = new ArrayList<PlanEntry>();
            var warnings = new ArrayList<>(fixture.warnings());
            boolean enabled = true;

            Plugin phoenix = plugin == null ? null : plugin.getServer().getPluginManager().getPlugin("PhoenixCratesLite");
            if (phoenix != null && phoenix.isEnabled()) {
                enabled = false;
                warnings.add("PhoenixCratesLite is enabled. Stop the server and remove its JAR from active plugins before importing.");
            }

            for (PhoenixKey key : fixture.keys().values().stream()
                    .sorted(Comparator.comparing(PhoenixKey::sourceId)).toList()) {
                MigrationStatus status = MigrationStatus.EXACT;
                String detail = key.sourceId() + " -> " + key.targetId();
                if (plugin != null && plugin.keys().definition(key.targetId()).isPresent()) {
                    ItemStack existing = plugin.keys().template(key.targetId()).orElse(null);
                    if (existing == null) {
                        status = MigrationStatus.MANUAL_REVIEW;
                        enabled = false;
                        detail += " (existing Plexon key is unresolved)";
                    } else if (!ItemCodec.one(existing).isSimilar(ItemCodec.one(key.template()))) {
                        status = MigrationStatus.CONVERTED;
                        detail += " (existing Plexon key differs; Phoenix exact key will be retained as a legacy accepted key)";
                    } else detail += " (exactly matches existing Plexon key)";
                }
                entries.add(new PlanEntry("key:" + key.sourceId(), key.targetId(), status, detail));
            }

            for (PhoenixCrate crate : fixture.crates()) {
                MigrationStatus status = MigrationStatus.EXACT;
                String detail = crate.sourceId() + " -> " + crate.targetId() + " as DRAFT; "
                        + crate.rewards().size() + " rewards";
                if (plugin != null && plugin.crates().find(crate.targetId()).isPresent()) {
                    String existingFingerprint = migrationFingerprint(crate.targetId());
                    if (scan.fingerprint().equals(existingFingerprint)) {
                        status = MigrationStatus.SKIPPED;
                        detail += " (already imported from this source fingerprint)";
                    } else {
                        status = MigrationStatus.CONFLICT;
                        enabled = false;
                        detail += " (target ID already exists and was not imported from this source fingerprint)";
                    }
                }
                entries.add(new PlanEntry("crate:" + crate.sourceId(), crate.targetId(), status, detail));
                for (PhoenixReward reward : crate.rewards()) {
                    entries.add(new PlanEntry("reward:" + crate.sourceId() + "/" + reward.sourceId(),
                            crate.targetId() + "/" + reward.sourceId(), MigrationStatus.EXACT,
                            String.format(Locale.ROOT, "%.2f%% -> %d basis points; exact item snapshot",
                                    reward.basisPoints() / 100.0, reward.basisPoints())));
                    if (!reward.commands().isEmpty()) {
                        enabled = false;
                        warnings.add(crate.sourceId() + "/" + reward.sourceId()
                                + " has Phoenix commands and requires manual command-semantic review.");
                    }
                }
                if (!"ROULETTE".equals(crate.animation())) {
                    entries.add(new PlanEntry("animation:" + crate.sourceId(), crate.targetId(),
                            MigrationStatus.CONVERTED, "Phoenix animation maps to PlexonCrates " + crate.animation()));
                }
            }

            Set<String> targetCrates = fixture.crates().stream().map(PhoenixCrate::targetId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (PhoenixLocation location : fixture.locations()) {
                String target = PhoenixFixtureAdapter.normalizeId(location.sourceCrateId());
                if (!targetCrates.contains(target)) {
                    enabled = false;
                    entries.add(new PlanEntry("location:" + location.worldName(), target,
                            MigrationStatus.CONFLICT, "Location references an unavailable Phoenix crate"));
                    continue;
                }
                if (plugin != null) {
                    var existing = plugin.locations().all().stream().filter(link ->
                            link.position().worldName().equalsIgnoreCase(location.worldName())
                                    && link.position().x() == location.x() && link.position().y() == location.y()
                                    && link.position().z() == location.z()).findFirst().orElse(null);
                    if (existing != null && !existing.crateId().equalsIgnoreCase(target)) {
                        enabled = false;
                        entries.add(new PlanEntry("location:" + location.worldName() + ":" + location.x()
                                + ":" + location.y() + ":" + location.z(), target, MigrationStatus.CONFLICT,
                                "Block is already linked to " + existing.crateId()));
                        continue;
                    }
                }
                entries.add(new PlanEntry("location:" + location.worldName() + ":" + location.x()
                        + ":" + location.y() + ":" + location.z(), target, MigrationStatus.EXACT,
                        "World link will be imported without forcing the chunk to load"));
            }

            long openings = fixture.players().stream().flatMap(player -> player.openedCrates().values().stream())
                    .mapToLong(Long::longValue).sum();
            long mappedWins = fixture.players().stream().flatMap(player -> player.rewardWins().entrySet().stream())
                    .filter(entry -> fixture.rewardToCrate().containsKey(entry.getKey()))
                    .mapToLong(Map.Entry::getValue).sum();
            long orphanWins = fixture.orphanRewardWins().values().stream().mapToLong(Long::longValue).sum();
            entries.add(new PlanEntry("player-history", "aggregate-history", MigrationStatus.CONVERTED,
                    fixture.players().size() + " players; " + openings + " aggregate openings; " + mappedWins
                            + " mapped reward wins. Phoenix does not store per-opening timestamps in this snapshot."));
            if (orphanWins > 0) {
                entries.add(new PlanEntry("historical-orphan-rewards", "migration-report",
                        MigrationStatus.MANUAL_REVIEW, orphanWins + " historical wins reference "
                                + fixture.orphanRewardWins().size() + " reward IDs absent from current Phoenix crate YAML; "
                                + "they will be preserved in the migration report, not assigned to another reward."));
            }

            for (String warning : fixture.warnings()) {
                if (warning.contains("requires manual review") || warning.contains("non-empty")) enabled = false;
            }
            String detail = "Phoenix fixture mapped: " + fixture.crates().size() + " crates, "
                    + fixture.crates().stream().mapToInt(value -> value.rewards().size()).sum() + " rewards, "
                    + fixture.keys().size() + " keys, " + fixture.locations().size() + " locations, "
                    + fixture.players().size() + " players, " + openings + " aggregate openings, "
                    + orphanWins + " orphan historical reward wins.";
            return new PlanResult(Instant.now(), scan.fingerprint(), List.copyOf(entries), enabled,
                    detail, List.copyOf(warnings));
        } catch (Exception error) {
            return blockedPlan(scan, "Phoenix fixture could not be parsed: " + concise(error),
                    List.of(concise(error)));
        }
    }

    /** Compatibility overload; live imports require the plugin-aware constructor and actor metadata. */
    public ImportResult importToDrafts(PlanResult plan) {
        if (plugin == null) throw new IllegalStateException("Phoenix import requires a running PlexonCrates instance");
        return importToDrafts(plan, null, "CONSOLE");
    }

    /** Performs the confirmed, idempotent import into ordinary PlexonCrates DRAFT definitions. */
    public ImportResult importToDrafts(PlanResult suppliedPlan, UUID actorId, String actorName) {
        requirePlugin();
        Objects.requireNonNull(suppliedPlan, "suppliedPlan");
        String actor = actorName == null || actorName.isBlank() ? "CONSOLE" : actorName;
        try {
            ScanResult currentScan = scan();
            if (!currentScan.fingerprint().equals(suppliedPlan.sourceFingerprint())) {
                throw new IllegalStateException("Phoenix source files changed after the migration plan was created");
            }
            PlanResult currentPlan = plan(currentScan);
            if (!currentPlan.importEnabled()) {
                throw new IllegalStateException("Phoenix import plan is blocked: " + String.join("; ", currentPlan.warnings()));
            }
            Plugin phoenix = plugin.getServer().getPluginManager().getPlugin("PhoenixCratesLite");
            if (phoenix != null && phoenix.isEnabled()) {
                throw new IllegalStateException("PhoenixCratesLite must be disabled during import");
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

            Map<String, KeyMapping> keyMappings = ensureKeys(fixture, actor);
            Path generated = generatedRoot.resolve(currentScan.fingerprint()).normalize();
            if (!generated.startsWith(generatedRoot)) throw new IllegalStateException("Unsafe generated migration path");
            Files.createDirectories(generated);

            int importedCrates = 0;
            int skippedCrates = 0;
            for (PhoenixCrate source : fixture.crates()) {
                if (plugin.crates().find(source.targetId()).isPresent()) {
                    if (currentScan.fingerprint().equals(migrationFingerprint(source.targetId()))) {
                        skippedCrates++;
                        continue;
                    }
                    throw new IllegalStateException("Target crate ID already exists: " + source.targetId());
                }
                List<String> acceptedKeys = new ArrayList<>();
                for (String sourceKeyId : source.sourceKeyIds()) {
                    PhoenixKey sourceKey = fixture.keys().get(sourceKeyId.toLowerCase(Locale.ROOT));
                    if (sourceKey == null) throw new IllegalStateException("Unknown Phoenix key " + sourceKeyId);
                    KeyMapping mapping = keyMappings.get(sourceKey.sourceId().toLowerCase(Locale.ROOT));
                    if (mapping == null) throw new IllegalStateException("Missing key mapping for " + sourceKey.sourceId());
                    acceptedKeys.addAll(mapping.acceptedIds());
                }
                Path definition = generated.resolve(source.targetId() + ".yml").normalize();
                if (!definition.getParent().equals(generated)) throw new IllegalStateException("Unsafe generated crate path");
                Files.writeString(definition, crateYaml(source, acceptedKeys, currentScan.fingerprint(), importedAt),
                        StandardCharsets.UTF_8);
                Crate imported = plugin.crates().importAsDraft(definition, source.targetId(), actor);
                if (imported.state() != com.antondev.crates.domain.crate.CrateState.DRAFT) {
                    throw new IllegalStateException("Phoenix crate did not import as DRAFT: " + source.targetId());
                }
                importedCrates++;
                plugin.database().audit(new DatabaseService.AuditRecord(actorId, actor, "MIGRATE", "CRATE",
                        imported.id(), "Imported PhoenixCratesLite definition as DRAFT from "
                                + currentScan.fingerprint(), importedAt)).join();
            }

            int historyRows = importAggregateHistory(fixture, currentScan.fingerprint(), importedAt);
            int locations = importLocations(fixture, importedAt);
            plugin.locations().apply(LocationStore.fromDatabase(plugin.database().loadLocations(), plugin.crates()));

            Path report = writeReport(currentScan, currentPlan, new ImportResult(currentScan.fingerprint(), backup,
                    null, importedCrates, skippedCrates, historyRows, locations,
                    fixture.orphanRewardWins().values().stream().mapToLong(Long::longValue).sum(), importedAt));
            Path marker = reportRoot.resolve("phoenix-" + currentScan.fingerprint() + ".imported").normalize();
            if (!marker.getParent().equals(reportRoot)) throw new IllegalStateException("Unsafe migration marker path");
            Files.writeString(marker, "source=" + SOURCE + "\nfingerprint=" + currentScan.fingerprint()
                    + "\nimported-at=" + importedAt + "\nbackup=" + dataRoot.relativize(backup) + "\nreport="
                    + dataRoot.relativize(report) + "\n", StandardCharsets.UTF_8);
            plugin.database().audit(new DatabaseService.AuditRecord(actorId, actor, "MIGRATE", "PHOENIX",
                    currentScan.fingerprint(), "Imported PhoenixCratesLite source into " + importedCrates
                            + " crate drafts; report=" + report.getFileName(), importedAt)).join();
            return new ImportResult(currentScan.fingerprint(), backup, report, importedCrates, skippedCrates,
                    historyRows, locations,
                    fixture.orphanRewardWins().values().stream().mapToLong(Long::longValue).sum(), importedAt);
        } catch (Exception error) {
            throw error instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Phoenix import failed: " + concise(error), error);
        }
    }

    /** Revalidates the current source and all mapping conflicts without changing data. */
    public ValidationResult validate(PlanResult plan) {
        Objects.requireNonNull(plan, "plan");
        var issues = new ArrayList<String>();
        try {
            ScanResult current = scan();
            if (!current.fingerprint().equals(plan.sourceFingerprint())) {
                issues.add("Phoenix source fingerprint changed after the plan was generated.");
            }
            PlanResult fresh = plan(current);
            if (!fresh.importEnabled()) issues.addAll(fresh.warnings());
            fresh.entries().stream().filter(entry -> entry.status() == MigrationStatus.CONFLICT)
                    .forEach(entry -> issues.add(entry.source() + ": " + entry.detail()));
        } catch (Exception error) {
            issues.add(concise(error));
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    public Path writeReport(ScanResult scan, PlanResult plan) throws IOException {
        return writeReport(scan, plan, null);
    }

    public Path writeReport(ScanResult scan, PlanResult plan, ImportResult imported) throws IOException {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(plan, "plan");
        Files.createDirectories(reportRoot);
        String fileName = "phoenix-" + REPORT_TIME.format(Instant.now()) + ".md";
        Path report = confinedReport(fileName);
        StringBuilder body = new StringBuilder();
        body.append("# PlexonCrates Phoenix Migration Report\n\n")
                .append("- Source: `PhoenixCratesLite` operator-owned data\n")
                .append("- Source fingerprint: `").append(scan.fingerprint()).append("`\n")
                .append("- Source files: ").append(scan.files().size()).append("\n")
                .append("- Import enabled by plan: **").append(plan.importEnabled()).append("**\n")
                .append("- State: **").append(imported == null ? "PLAN" : "IMPORTED_TO_DRAFTS").append("**\n");
        if (imported != null) {
            body.append("- Backup: `").append(markdown(dataRoot.relativize(imported.backupDirectory()).toString()))
                    .append("`\n- Imported crate drafts: ").append(imported.importedCrates())
                    .append("\n- Skipped existing same-source drafts: ").append(imported.skippedCrates())
                    .append("\n- Aggregate player-history rows: ").append(imported.historyRows())
                    .append("\n- World links imported: ").append(imported.locations())
                    .append("\n- Orphan historical reward wins: ").append(imported.orphanRewardWins()).append("\n");
        }
        body.append("\n## Mapping plan\n\n")
                .append("| Source | Target | Status | Detail |\n")
                .append("| --- | --- | --- | --- |\n");
        for (PlanEntry entry : plan.entries()) {
            body.append("| `").append(markdown(entry.source())).append("` | `")
                    .append(markdown(entry.target())).append("` | ").append(entry.status())
                    .append(" | ").append(markdown(entry.detail())).append(" |\n");
        }
        body.append("\n## Source files\n\n| File | Bytes | SHA-256 |\n| --- | ---: | --- |\n");
        for (SourceFile source : scan.files()) {
            body.append("| `").append(markdown(source.relativePath())).append("` | ")
                    .append(source.size()).append(" | `").append(source.sha256()).append("` |\n");
        }
        if (!plan.warnings().isEmpty()) {
            body.append("\n## Warnings\n\n");
            for (String warning : plan.warnings()) body.append("- ").append(warning).append("\n");
        }
        try {
            Fixture fixture = adapter.load(sourceRoot);
            if (!fixture.orphanRewardWins().isEmpty()) {
                body.append("\n## Historical reward IDs absent from current Phoenix definitions\n\n")
                        .append("These values remain preserved in the Phoenix backup/report and are not assigned to another reward.\n\n")
                        .append("| Reward ID | Wins |\n| --- | ---: |\n");
                fixture.orphanRewardWins().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> body.append("| `").append(markdown(entry.getKey())).append("` | ")
                                .append(entry.getValue()).append(" |\n"));
            }
        } catch (Exception error) {
            body.append("\n_Source details unavailable while writing report: ").append(concise(error)).append("_\n");
        }
        body.append("\n## Safety\n\n")
                .append("Phoenix source files were not modified. Imported crate definitions remain DRAFT until ordinary PlexonCrates validation and publication. ")
                .append("Historical player data is aggregate because the supplied Phoenix database does not contain individual opening timestamps.\n");
        Files.writeString(report, body.toString(), StandardCharsets.UTF_8);
        return report;
    }

    private Map<String, KeyMapping> ensureKeys(Fixture fixture, String actor) throws Exception {
        var mappings = new LinkedHashMap<String, KeyMapping>();
        for (PhoenixKey source : fixture.keys().values().stream()
                .sorted(Comparator.comparing(PhoenixKey::sourceId)).toList()) {
            String preferred = source.targetId();
            if (plugin.keys().definition(preferred).isEmpty() && plugin.keys().discovered().containsKey(preferred)) {
                plugin.keys().bindExternal(preferred, actor);
            }
            if (plugin.keys().definition(preferred).isEmpty()) {
                plugin.keys().createCaptured(preferred, keyDisplayName(source), source.template(), actor);
            }
            ItemStack current = plugin.keys().template(preferred).orElseThrow(() ->
                    new IllegalStateException("Mapped Plexon key is unresolved: " + preferred));
            if (ItemCodec.one(current).isSimilar(ItemCodec.one(source.template()))) {
                mappings.put(source.sourceId().toLowerCase(Locale.ROOT),
                        new KeyMapping(source.sourceId(), List.of(preferred), false));
                continue;
            }
            String legacy = legacyKeyId(preferred);
            if (plugin.keys().definition(legacy).isEmpty()) {
                plugin.keys().createCaptured(legacy, keyDisplayName(source), source.template(), actor);
            } else {
                ItemStack existingLegacy = plugin.keys().template(legacy).orElseThrow(() ->
                        new IllegalStateException("Existing Phoenix legacy key is unresolved: " + legacy));
                if (!ItemCodec.one(existingLegacy).isSimilar(ItemCodec.one(source.template()))) {
                    throw new IllegalStateException("Legacy key ID conflicts with a different exact item: " + legacy);
                }
            }
            mappings.put(source.sourceId().toLowerCase(Locale.ROOT),
                    new KeyMapping(source.sourceId(), List.of(preferred, legacy), true));
        }
        return Map.copyOf(mappings);
    }

    private int importAggregateHistory(Fixture fixture, String fingerprint, Instant importedAt) throws Exception {
        Map<String, String> crateTargets = fixture.crates().stream().collect(java.util.stream.Collectors.toMap(
                PhoenixCrate::sourceId, PhoenixCrate::targetId, (left, right) -> left, LinkedHashMap::new));
        int imported = 0;
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
                String keyId = plugin.crates().find(target).map(Crate::keyId).orElse("");
                DatabaseService.OpeningRecord record = new DatabaseService.OpeningRecord(transaction,
                        player.playerId(), player.playerName(), target, keyId, 0, opening.getValue().intValue(),
                        "PHOENIX_MIGRATION", rewardSummary, "PHOENIX_AGGREGATE", 0,
                        "Imported aggregate Phoenix history; source database has no per-opening timestamp. sourceCrate="
                                + opening.getKey(), importedAt);
                plugin.database().completeOpening(record).join();
                plugin.statistics().record(player.playerId(), target, opening.getValue().intValue());
                imported++;
            }
        }
        return imported;
    }

    private int importLocations(Fixture fixture, Instant importedAt) {
        Map<String, String> crateTargets = fixture.crates().stream().collect(java.util.stream.Collectors.toMap(
                PhoenixCrate::sourceId, PhoenixCrate::targetId, (left, right) -> left, LinkedHashMap::new));
        int count = 0;
        for (PhoenixLocation location : fixture.locations()) {
            String target = crateTargets.get(location.sourceCrateId());
            if (target == null) continue;
            World world = Bukkit.getWorld(location.worldName());
            UUID worldUuid = world == null ? null : world.getUID();
            plugin.database().saveLocation(new DatabaseService.StoredLocation(worldUuid, location.worldName(),
                    location.x(), location.y(), location.z(), target, importedAt)).join();
            count++;
        }
        return count;
    }

    private String crateYaml(PhoenixCrate source, List<String> acceptedKeys, String fingerprint,
                             Instant importedAt) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 3);
        yaml.set("id", source.targetId());
        yaml.set("state", "DRAFT");
        yaml.set("display-order", 1000 + Math.abs(source.targetId().hashCode() % 1000));
        yaml.set("display-name", legacy(source.displayName()));
        yaml.set("description", source.description());
        yaml.set("icon.base64", ItemCodec.capture(source.icon(), true));
        yaml.set("access.permission", source.permission());
        yaml.set("access.worlds", List.of());
        yaml.set("access.excluded-worlds", List.of());
        yaml.set("keys.cost", source.keyCost());
        yaml.set("keys.accepted", acceptedKeys.stream().distinct().toList());
        yaml.set("keys.payment-policy", "PHYSICAL_ONLY");
        yaml.set("keys.allow-mixed-payment", false);
        yaml.set("opening.mode", "RANDOM");
        yaml.set("opening.cooldown-seconds", source.cooldownSeconds());
        yaml.set("opening.bulk-enabled", true);
        yaml.set("opening.bulk-maximum", 64);
        yaml.set("opening.animation", source.animation());
        yaml.set("opening.broadcast", "");
        yaml.set("hologram.enabled", true);
        yaml.set("hologram.lines", source.hologramLines());
        yaml.set("pity.enabled", false);
        yaml.set("pity.threshold", 0);
        yaml.set("pity.reward-ids", List.of());
        yaml.set("rerolls.enabled", false);
        yaml.set("rerolls.maximum", 0);
        yaml.set("rerolls.cost-type", "TOKEN");
        yaml.set("rerolls.cost", 0);
        yaml.set("rerolls.permission", "");
        yaml.set("rerolls.exclude-previous", true);
        yaml.set("rerolls.timeout-seconds", 15);
        yaml.set("rerolls.timeout-policy", "ACCEPT_CURRENT");
        yaml.set("rerolls.mass-allowed", false);
        yaml.createSection("milestones");
        yaml.createSection("rewards");
        for (PhoenixReward reward : source.rewards()) {
            String path = "rewards." + reward.sourceId();
            yaml.set(path + ".enabled", true);
            yaml.set(path + ".display-name", legacy(reward.displayName()));
            yaml.set(path + ".chance-basis-points", reward.basisPoints());
            yaml.set(path + ".chance-locked", true);
            yaml.set(path + ".display.base64", ItemCodec.capture(reward.displayItem(), true));
            int itemIndex = 0;
            for (ItemStack item : reward.items()) {
                yaml.set(path + ".items.item_" + itemIndex++ + ".base64", ItemCodec.capture(item, false));
            }
            yaml.set(path + ".commands", reward.commands().stream().map(PhoenixMigrationService::command).toList());
        }
        yaml.set("migration.source", SOURCE);
        yaml.set("migration.source-id", source.sourceId());
        yaml.set("migration.source-fingerprint", fingerprint);
        yaml.set("migration.imported-at", importedAt.toString());
        yaml.set("audit.created-at", importedAt.toString());
        yaml.set("audit.updated-at", importedAt.toString());
        yaml.set("audit.last-editor", "PHOENIX_MIGRATION");
        return yaml.saveToString();
    }

    private String migrationFingerprint(String crateId) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(plugin.crates().serialized(crateId));
            return yaml.getString("migration.source-fingerprint", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private PlanResult blockedPlan(ScanResult scan, String detail, List<String> warnings) {
        return new PlanResult(Instant.now(), scan.fingerprint(), List.of(
                new PlanEntry("phoenix-source", "manual-review", MigrationStatus.MANUAL_REVIEW, detail)),
                false, detail, List.copyOf(warnings));
    }

    private void copyTree(Path source, Path destination) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = destination.toAbsolutePath().normalize();
        Files.createDirectories(dst);
        try (var stream = Files.walk(src)) {
            for (Path candidate : stream.sorted().toList()) {
                if (Files.isSymbolicLink(candidate)) throw new IOException("Phoenix backup refuses symbolic links");
                Path relative = src.relativize(candidate.toAbsolutePath().normalize());
                Path target = dst.resolve(relative).normalize();
                if (!target.startsWith(dst)) throw new IOException("Phoenix backup path escapes destination");
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(candidate, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private Path confinedReport(String fileName) throws IOException {
        if (!fileName.matches("phoenix-[0-9]{8}-[0-9]{6}\\.md")) throw new IOException("Unsafe report name");
        Path candidate = reportRoot.resolve(fileName).normalize();
        if (!candidate.getParent().equals(reportRoot)) throw new IOException("Migration report path escapes report directory");
        return candidate;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fingerprint(List<SourceFile> files) {
        MessageDigest digest = sha256Digest();
        for (SourceFile file : files) {
            digest.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(file.size()).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(file.sha256().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private void requirePlugin() {
        if (plugin == null) throw new IllegalStateException("Phoenix import requires a running PlexonCrates instance");
    }

    private static String portableRelative(Path path) { return path.toString().replace('\\', '/'); }
    private static String markdown(String value) { return value.replace("|", "\\|").replace("`", "'"); }
    private static String concise(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private static String legacy(String value) { return value == null ? "" : value.replace('&', '§'); }
    private static String command(String value) { return value != null && value.startsWith("/") ? value.substring(1) : value; }
    private static String legacyKeyId(String preferred) {
        String value = "phoenix_" + preferred + "_legacy";
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
    private static Component keyDisplayName(PhoenixKey key) {
        Component name = key.template().getItemMeta().displayName();
        return name == null ? Text.parse("<white>" + key.targetId() + " key</white>") : name;
    }

    public enum Phase { SCAN, PLAN, IMPORT_TO_DRAFTS, VALIDATE, CUTOVER_REPORT }
    public enum MigrationStatus { EXACT, CONVERTED, MANUAL_REVIEW, UNSUPPORTED, SKIPPED, CONFLICT }

    public record SourceFile(String relativePath, long size, String sha256) {
        public SourceFile {
            Objects.requireNonNull(relativePath); Objects.requireNonNull(sha256);
            if (relativePath.isBlank() || size < 0 || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid source metadata");
        }
    }
    public record ScanResult(Instant scannedAt, String fingerprint, List<SourceFile> files, List<String> warnings) {
        public ScanResult { scannedAt = Objects.requireNonNull(scannedAt); fingerprint = Objects.requireNonNull(fingerprint); files = List.copyOf(files); warnings = List.copyOf(warnings); }
    }
    public record PlanEntry(String source, String target, MigrationStatus status, String detail) {
        public PlanEntry { Objects.requireNonNull(source); Objects.requireNonNull(target); Objects.requireNonNull(status); detail = detail == null ? "" : detail; }
    }
    public record PlanResult(Instant plannedAt, String sourceFingerprint, List<PlanEntry> entries,
                             boolean importEnabled, String detail, List<String> warnings) {
        public PlanResult { plannedAt = Objects.requireNonNull(plannedAt); sourceFingerprint = Objects.requireNonNull(sourceFingerprint); entries = List.copyOf(entries); detail = detail == null ? "" : detail; warnings = List.copyOf(warnings); }
    }
    public record ValidationResult(boolean validForImport, List<String> issues) {
        public ValidationResult { issues = List.copyOf(issues); }
    }
    public record ImportResult(String sourceFingerprint, Path backupDirectory, Path report,
                               int importedCrates, int skippedCrates, int historyRows, int locations,
                               long orphanRewardWins, Instant importedAt) {}
    private record KeyMapping(String sourceId, List<String> acceptedIds, boolean legacyAdded) {
        private KeyMapping { acceptedIds = List.copyOf(acceptedIds); }
    }
}

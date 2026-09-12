from pathlib import Path

SERVICE = Path('src/main/java/com/antondev/crates/migration/phoenix/PhoenixMigrationService.java')
COMMAND = Path('src/main/java/com/antondev/crates/command/PhoenixMigrationCommand.java')

text = SERVICE.read_text()

marker = '    /** Parses the supplied live fixture and produces a non-mutating mapping plan. */\n'
if marker not in text:
    raise SystemExit('planning marker not found')

capture = '''    /** Captures all live plugin state needed by read-only migration planning on the primary thread. */
    public PlanningState capturePlanningState() {
        requirePlugin();
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Phoenix planning state must be captured on the primary thread");
        }
        var keys = new LinkedHashMap<String, PlanningKey>();
        for (var definition : plugin.keys().definitions()) {
            ItemStack template = plugin.keys().template(definition.id()).orElse(null);
            keys.put(definition.id().toLowerCase(Locale.ROOT), new PlanningKey(template));
        }
        var crateFingerprints = new LinkedHashMap<String, String>();
        var crates = plugin.crates().snapshot();
        Map<String, byte[]> payloads = crates.payloads();
        for (String crateId : crates.crates().keySet()) {
            byte[] payload = payloads.get(crateId);
            crateFingerprints.put(crateId.toLowerCase(Locale.ROOT),
                    payload == null ? "" : migrationFingerprint(payload));
        }
        Plugin phoenix = plugin.getServer().getPluginManager().getPlugin("PhoenixCratesLite");
        return new PlanningState(phoenix != null && phoenix.isEnabled(), keys, crateFingerprints,
                List.copyOf(plugin.locations().all()));
    }

'''
text = text.replace(marker, capture + marker, 1)

old = '    public PlanResult plan(ScanResult scan) {\n        Objects.requireNonNull(scan, "scan");\n'
new = '''    public PlanResult plan(ScanResult scan) {
        return plan(scan, plugin == null ? PlanningState.empty() : capturePlanningState());
    }

    public PlanResult plan(ScanResult scan, PlanningState state) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(state, "state");
'''
if old not in text:
    raise SystemExit('plan method signature changed unexpectedly')
text = text.replace(old, new, 1)

old = '''            Plugin phoenix = plugin == null ? null : plugin.getServer().getPluginManager().getPlugin("PhoenixCratesLite");
            if (phoenix != null && phoenix.isEnabled()) {
'''
new = '''            if (state.phoenixEnabled()) {
'''
if old not in text:
    raise SystemExit('Phoenix enabled planning block changed unexpectedly')
text = text.replace(old, new, 1)

old = '''                if (plugin != null && plugin.keys().definition(key.targetId()).isPresent()) {
                    ItemStack existing = plugin.keys().template(key.targetId()).orElse(null);
'''
new = '''                PlanningKey existingKey = state.keys().get(key.targetId().toLowerCase(Locale.ROOT));
                if (existingKey != null) {
                    ItemStack existing = existingKey.template();
'''
if old not in text:
    raise SystemExit('key planning block changed unexpectedly')
text = text.replace(old, new, 1)

old = '''                if (plugin != null && plugin.crates().find(crate.targetId()).isPresent()) {
                    String existingFingerprint = migrationFingerprint(crate.targetId());
'''
new = '''                if (state.crateFingerprints().containsKey(crate.targetId())) {
                    String existingFingerprint = state.crateFingerprints().get(crate.targetId());
'''
if old not in text:
    raise SystemExit('crate planning block changed unexpectedly')
text = text.replace(old, new, 1)

old = '''                if (plugin != null) {
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
'''
new = '''                var existing = state.locations().stream().filter(link ->
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
'''
if old not in text:
    raise SystemExit('location planning block changed unexpectedly')
text = text.replace(old, new, 1)

old = '''    public ValidationResult validate(PlanResult plan) {
        Objects.requireNonNull(plan, "plan");
'''
new = '''    public ValidationResult validate(PlanResult plan) {
        return validate(plan, plugin == null ? PlanningState.empty() : capturePlanningState());
    }

    public ValidationResult validate(PlanResult plan, PlanningState state) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
'''
if old not in text:
    raise SystemExit('validation signature changed unexpectedly')
text = text.replace(old, new, 1)

old = '            PlanResult fresh = plan(current);\n'
new = '            PlanResult fresh = plan(current, state);\n'
validation_start = text.index('    public ValidationResult validate(PlanResult plan, PlanningState state) {')
validation_end = text.index('    public Path writeReport(', validation_start)
segment = text[validation_start:validation_end]
if old not in segment:
    raise SystemExit('validation planning call changed unexpectedly')
segment = segment.replace(old, new, 1)
text = text[:validation_start] + segment + text[validation_end:]

old = '''    private String migrationFingerprint(String crateId) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(plugin.crates().serialized(crateId));
            return yaml.getString("migration.source-fingerprint", "");
        } catch (Exception ignored) {
            return "";
        }
    }
'''
new = '''    private String migrationFingerprint(String crateId) {
        try {
            return migrationFingerprint(plugin.crates().serialized(crateId).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String migrationFingerprint(byte[] payload) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(payload, StandardCharsets.UTF_8));
            return yaml.getString("migration.source-fingerprint", "");
        } catch (Exception ignored) {
            return "";
        }
    }
'''
if old not in text:
    raise SystemExit('migration fingerprint block changed unexpectedly')
text = text.replace(old, new, 1)

record_marker = '    public enum Phase { SCAN, PLAN, IMPORT_TO_DRAFTS, VALIDATE, CUTOVER_REPORT }\n'
if record_marker not in text:
    raise SystemExit('record marker not found')
records = '''    public record PlanningKey(ItemStack template) {
        public PlanningKey {
            template = template == null ? null : template.clone();
        }
        @Override public ItemStack template() { return template == null ? null : template.clone(); }
    }

    public record PlanningState(boolean phoenixEnabled, Map<String, PlanningKey> keys,
                                Map<String, String> crateFingerprints, List<LocationStore.Link> locations) {
        public PlanningState {
            keys = Map.copyOf(keys);
            crateFingerprints = Map.copyOf(crateFingerprints);
            locations = List.copyOf(locations);
        }
        public static PlanningState empty() {
            return new PlanningState(false, Map.of(), Map.of(), List.of());
        }
    }

'''
text = text.replace(record_marker, records + record_marker, 1)
SERVICE.write_text(text)

command = '''package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.migration.phoenix.PhoenixMigrationService;
import com.antondev.crates.service.AsyncIoService;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Focused administrator command for the PhoenixCratesLite replacement workflow. */
final class PhoenixMigrationCommand {
    private PhoenixMigrationCommand() {}

    static void execute(PlexonCrates plugin, CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("phoenix")) {
            help(sender);
            return;
        }
        PhoenixMigrationService service = new PhoenixMigrationService(plugin);
        String phase = args[2].toLowerCase(Locale.ROOT);
        try {
            switch (phase) {
                case "scan" -> scan(plugin, service, sender);
                case "plan" -> plan(plugin, service, sender);
                case "validate" -> validate(plugin, service, sender);
                case "report" -> report(plugin, service, sender);
                case "import" -> importData(service, sender, args);
                default -> help(sender);
            }
        } catch (Exception error) {
            fail(plugin, sender, error);
        }
    }

    static List<String> tab(String[] args) {
        if (args.length == 2) return filter(List.of("phoenix"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("phoenix")) {
            return filter(List.of("scan", "plan", "import", "validate", "report"), args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("phoenix")
                && args[2].equalsIgnoreCase("import")) {
            return filter(List.of("confirm"), args[3]);
        }
        return List.of();
    }

    private static void scan(PlexonCrates plugin, PhoenixMigrationService service, CommandSender sender) {
        async(plugin, sender, "<aqua>Scanning Phoenix migration source off-thread…</aqua>", service::scan,
                PhoenixMigrationCommand::renderScan);
    }

    private static void plan(PlexonCrates plugin, PhoenixMigrationService service, CommandSender sender) {
        PhoenixMigrationService.PlanningState state = service.capturePlanningState();
        async(plugin, sender, "<aqua>Building Phoenix migration plan off-thread…</aqua>", () -> {
            PhoenixMigrationService.ScanResult scan = service.scan();
            return new PlanBundle(scan, service.plan(scan, state));
        }, (target, bundle) -> renderPlan(target, bundle.plan()));
    }

    private static void validate(PlexonCrates plugin, PhoenixMigrationService service, CommandSender sender) {
        PhoenixMigrationService.PlanningState state = service.capturePlanningState();
        async(plugin, sender, "<aqua>Validating Phoenix migration source off-thread…</aqua>", () -> {
            PhoenixMigrationService.ScanResult scan = service.scan();
            PhoenixMigrationService.PlanResult plan = service.plan(scan, state);
            return new ValidationBundle(scan, service.validate(plan, state));
        }, PhoenixMigrationCommand::renderValidation);
    }

    private static void report(PlexonCrates plugin, PhoenixMigrationService service, CommandSender sender) {
        PhoenixMigrationService.PlanningState state = service.capturePlanningState();
        async(plugin, sender, "<aqua>Writing Phoenix migration report off-thread…</aqua>", () -> {
            PhoenixMigrationService.ScanResult scan = service.scan();
            PhoenixMigrationService.PlanResult plan = service.plan(scan, state);
            return new ReportBundle(service.writeReport(scan, plan));
        }, (target, bundle) -> target.sendMessage(Text.parse("<green>Phoenix migration report written:</green> <white>"
                + escape(bundle.report().getFileName().toString()) + "</white>")));
    }

    private static void renderScan(CommandSender sender, PhoenixMigrationService.ScanResult scan) {
        sender.sendMessage(Text.parse("<gradient:#FF9F2E:#FFF0B2><bold>Phoenix Migration Scan</bold></gradient>"));
        sender.sendMessage(Text.parse("<gray>Source:</gray> <white>plugins/PlexonCrates/imports/phoenix/</white>"));
        sender.sendMessage(Text.parse("<gray>Files:</gray> <white>" + scan.files().size() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Fingerprint:</gray> <white>" + scan.fingerprint() + "</white>"));
        if (scan.files().isEmpty()) {
            sender.sendMessage(Text.parse("<yellow>Copy the PhoenixCratesLite data folder into imports/phoenix/ before planning.</yellow>"));
        } else sender.sendMessage(Text.parse("<green>Source fingerprint completed without modifying Phoenix files.</green>"));
    }

    private static void renderPlan(CommandSender sender, PhoenixMigrationService.PlanResult plan) {
        sender.sendMessage(Text.parse("<gradient:#FF9F2E:#FFF0B2><bold>Phoenix Migration Plan</bold></gradient>"));
        sender.sendMessage(Text.parse("<gray>Fingerprint:</gray> <white>" + plan.sourceFingerprint() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Mappings:</gray> <white>" + plan.entries().size() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Import gate:</gray> "
                + (plan.importEnabled() ? "<green>READY</green>" : "<red>BLOCKED</red>")));
        sender.sendMessage(Text.parse("<gray>Summary:</gray> <white>" + escape(plan.detail()) + "</white>"));
        long exact = plan.entries().stream().filter(value -> value.status() == PhoenixMigrationService.MigrationStatus.EXACT).count();
        long converted = plan.entries().stream().filter(value -> value.status() == PhoenixMigrationService.MigrationStatus.CONVERTED).count();
        long review = plan.entries().stream().filter(value -> value.status() == PhoenixMigrationService.MigrationStatus.MANUAL_REVIEW).count();
        long conflicts = plan.entries().stream().filter(value -> value.status() == PhoenixMigrationService.MigrationStatus.CONFLICT).count();
        sender.sendMessage(Text.parse("<gray>Status:</gray> <white>" + exact + " exact</white> <dark_gray>•</dark_gray> <yellow>"
                + converted + " converted</yellow> <dark_gray>•</dark_gray> <gold>" + review
                + " review</gold> <dark_gray>•</dark_gray> <red>" + conflicts + " conflicts</red>"));
        for (String warning : plan.warnings()) sender.sendMessage(Text.parse("<yellow>⚠ " + escape(warning) + "</yellow>"));
        if (plan.importEnabled()) {
            sender.sendMessage(Text.parse("<gray>Run</gray> <white>/pcrates migrate phoenix import confirm</white> <gray>to create backups and import drafts.</gray>"));
        }
    }

    private static void renderValidation(CommandSender sender, ValidationBundle bundle) {
        if (bundle.validation().validForImport()) {
            sender.sendMessage(Text.parse("<green>Phoenix migration validation passed for fingerprint</green> <white>"
                    + bundle.scan().fingerprint() + "</white><green>.</green>"));
        } else {
            sender.sendMessage(Text.parse("<red>Phoenix migration validation is blocked.</red>"));
            for (String issue : bundle.validation().issues()) {
                sender.sendMessage(Text.parse("<red>•</red> <white>" + escape(issue) + "</white>"));
            }
        }
    }

    private static void importData(PhoenixMigrationService service, CommandSender sender, String[] args) throws Exception {
        if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
            sender.sendMessage(Text.parse("<yellow>This step creates PlexonCrates data and aggregate history.</yellow>"));
            sender.sendMessage(Text.parse("<gray>It never modifies Phoenix source files, but it must be explicitly confirmed:</gray>"));
            sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix import confirm</white>"));
            return;
        }
        PhoenixMigrationService.ScanResult scan = service.scan();
        PhoenixMigrationService.PlanResult plan = service.plan(scan);
        if (!plan.importEnabled()) {
            sender.sendMessage(Text.parse("<red>Import is blocked. Run /pcrates migrate phoenix plan first.</red>"));
            for (String warning : plan.warnings()) sender.sendMessage(Text.parse("<red>•</red> <white>" + escape(warning) + "</white>"));
            return;
        }
        UUID actorId = sender instanceof Player player ? player.getUniqueId() : null;
        PhoenixMigrationService.ImportResult result = service.importToDrafts(plan, actorId, sender.getName());
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

    private static <T> void async(PlexonCrates plugin, CommandSender sender, String progress,
                                  AsyncIoService.CheckedSupplier<T> task, BiConsumer<CommandSender, T> success) {
        sender.sendMessage(Text.parse(progress));
        plugin.io().submit(task).whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player player && !player.isOnline()) return;
                if (error != null) fail(plugin, sender, error);
                else success.accept(sender, result);
            });
        });
    }

    private static void fail(PlexonCrates plugin, CommandSender sender, Throwable error) {
        sender.sendMessage(Text.parse("<red>Phoenix migration failed:</red> <white>" + escape(concise(error)) + "</white>"));
        plugin.getLogger().log(java.util.logging.Level.WARNING, "Phoenix migration command failed", error);
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix scan</white> <dark_gray>—</dark_gray> <gray>Hash the read-only source.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix plan</white> <dark_gray>—</dark_gray> <gray>Preview mappings and conflicts.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix validate</white> <dark_gray>—</dark_gray> <gray>Re-check the current plan.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix report</white> <dark_gray>—</dark_gray> <gray>Write a Markdown migration report.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates migrate phoenix import confirm</white> <dark_gray>—</dark_gray> <gray>Back up and import as drafts.</gray>"));
    }

    private static String concise(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private record PlanBundle(PhoenixMigrationService.ScanResult scan, PhoenixMigrationService.PlanResult plan) {}
    private record ValidationBundle(PhoenixMigrationService.ScanResult scan,
                                    PhoenixMigrationService.ValidationResult validation) {}
    private record ReportBundle(Path report) {}
}
'''
COMMAND.write_text(command)

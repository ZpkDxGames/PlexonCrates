package com.antondev.crates.command;

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
                case "import" -> importData(plugin, service, sender, args);
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

    private static void importData(PlexonCrates plugin, PhoenixMigrationService service,
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

package com.plexoncrates.command;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.crate.RewardActionType;
import com.plexoncrates.item.ItemDiagnostics;
import com.plexoncrates.migration.PhoenixMigrationService;
import com.plexoncrates.migration.PhoenixPartialImportRecovery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CrateCommand implements CommandExecutor, TabCompleter {
    private final PlexonCrates plugin;
    private final ConfigManager config;

    public CrateCommand(PlexonCrates plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        try {
            if (args.length == 0) {
                Player player = player(sender);
                require(player, "plexoncrates.use");
                plugin.gui().openPlayerMenu(player);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "admin" -> admin(sender, args);
                case "preview" -> preview(sender, args);
                case "open" -> open(sender, args);
                case "claims" -> claims(sender, args);
                case "link" -> link(sender, args);
                case "unlink" -> unlink(sender);
                case "key" -> key(sender, args);
                case "reward" -> reward(sender, args);
                case "item" -> item(sender, args);
                case "migrate" -> migrate(sender, args);
                case "reload" -> reload(sender);
                case "help" -> help(sender);
                default -> help(sender);
            }
        } catch (CommandFailure failure) {
            sender.sendMessage(config.prefix() + "§c" + failure.getMessage());
        } catch (Exception error) {
            sender.sendMessage(config.prefix() + "§c" + message(error));
            plugin.getLogger().log(Level.WARNING,
                    "Command /" + label + " " + String.join(" ", args) + " failed", error);
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.admin.gui");
        if (args.length >= 3 && args[1].equalsIgnoreCase("create")) {
            Crate created = plugin.crates().create(args[2]);
            plugin.gui().openCrateEditor(player, created);
            return;
        }
        plugin.gui().openAdmin(player);
    }

    private void preview(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.preview");
        plugin.gui().openPreview(player, crate(args, 1));
    }

    private void open(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.open");
        Crate crate = crate(args, 1);
        String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "virtual";
        if (mode.equals("physical")) plugin.openings().openPhysical(player, crate, player.getLocation());
        else plugin.openings().openVirtual(player, crate);
    }

    private void claims(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.use");
        int page = args.length >= 2 ? Math.max(0, integer(args[1], "page") - 1) : 0;
        plugin.gui().openClaims(player, page);
    }

    private void link(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.admin.link");
        Crate crate = crate(args, 1);
        Block block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) {
            throw new CommandFailure("Look at a chest, barrel or other tile-state block.");
        }
        try {
            plugin.crates().link(block, crate.id());
            player.sendMessage(config.message("linked", "crate", crate.id()));
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().contains("TileState")) {
                player.sendMessage(config.message("tile-required"));
            } else throw error;
        }
    }

    private void unlink(CommandSender sender) {
        Player player = player(sender);
        require(player, "plexoncrates.admin.link");
        Block block = player.getTargetBlockExact(6);
        if (block == null || block.getType().isAir()) throw new CommandFailure("Look at a linked crate block.");
        Optional<String> removed = plugin.crates().unlink(block);
        if (removed.isEmpty()) throw new CommandFailure("That block is not linked to a crate.");
        player.sendMessage(config.message("unlinked"));
    }

    private void key(CommandSender sender, String[] args) {
        require(sender, "plexoncrates.admin.keys");
        if (args.length < 5) {
            throw new CommandFailure("Usage: /crates key <give|physical> <player> <crate> <amount>");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) throw new CommandFailure("That player must be online.");
        Crate crate = plugin.crates().find(args[3])
                .orElseThrow(() -> new CommandFailure("Unknown crate: " + args[3]));
        int amount = positive(args[4], 100000);
        if (action.equals("physical")) {
            givePhysical(target, crate, amount);
            sender.sendMessage(config.prefix() + "§aGranted §f" + amount + "x " + crate.id()
                    + " §aphysical key(s) to §f" + target.getName() + "§a.");
            return;
        }
        if (!action.equals("give")) throw new CommandFailure("Key action must be give or physical.");
        plugin.keys().grantVirtual(target.getUniqueId(), crate, amount).whenComplete((balance, error) ->
                sync(() -> {
                    if (error != null) sender.sendMessage(config.prefix() + "§cVirtual key grant failed.");
                    else sender.sendMessage(config.message("key-granted", "amount", Integer.toString(amount),
                            "crate", crate.id(), "player", target.getName()));
                }));
    }

    private void reward(CommandSender sender, String[] args) {
        require(sender, "plexoncrates.admin.rewards");
        if (args.length < 2) throw new CommandFailure("Usage: /crates reward <add|remove|weight|action> ...");
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> {
                Player player = player(sender);
                if (args.length < 4) throw new CommandFailure("Usage: /crates reward add <crate> <weight>");
                Crate crate = crate(args, 2);
                int weight = positive(args[3], Integer.MAX_VALUE);
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) throw new CommandFailure("Hold the exact reward item in your main hand.");
                var reward = plugin.crates().addCapturedReward(crate.id(), held, weight);
                sender.sendMessage(config.prefix() + "§aAdded reward §f" + reward.id()
                        + " §awith weight §f" + reward.weight() + "§a.");
            }
            case "remove" -> {
                if (args.length < 4) throw new CommandFailure("Usage: /crates reward remove <crate> <reward>");
                Crate crate = crate(args, 2);
                plugin.crates().removeReward(crate.id(), args[3]);
                sender.sendMessage(config.prefix() + "§aRemoved reward §f" + args[3] + "§a.");
            }
            case "weight" -> {
                if (args.length < 5) throw new CommandFailure("Usage: /crates reward weight <crate> <reward> <weight>");
                Crate crate = crate(args, 2);
                int weight = nonNegative(args[4], Integer.MAX_VALUE);
                plugin.crates().setRewardWeight(crate.id(), args[3], weight);
                sender.sendMessage(config.prefix() + "§aUpdated reward weight to §f" + weight + "§a.");
            }
            case "action" -> rewardAction(sender, args);
            default -> throw new CommandFailure("Reward action must be add, remove, weight or action.");
        }
    }

    private void rewardAction(CommandSender sender, String[] args) {
        if (args.length < 5) {
            throw new CommandFailure("Usage: /crates reward action <crate> <reward> <ITEM|COMMAND|MESSAGE|SOUND> [value]");
        }
        Crate crate = crate(args, 2);
        String rewardId = args[3];
        RewardActionType type;
        try {
            type = RewardActionType.valueOf(args[4].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new CommandFailure("Action type must be ITEM, COMMAND, MESSAGE or SOUND.");
        }
        RewardAction rewardAction;
        if (type == RewardActionType.ITEM) {
            Player player = player(sender);
            ItemStack held = player.getInventory().getItemInMainHand();
            rewardAction = held.getType().isAir()
                    ? new RewardAction(type, "", null)
                    : new RewardAction(type, "", held.clone());
        } else {
            if (args.length < 6) throw new CommandFailure("This action type requires a value.");
            rewardAction = new RewardAction(type, String.join(" ", Arrays.copyOfRange(args, 5, args.length)), null);
        }
        plugin.crates().addRewardAction(crate.id(), rewardId, rewardAction);
        sender.sendMessage(config.prefix() + "§aAdded §f" + type + " §aaction to §f" + rewardId + "§a.");
    }

    private void item(CommandSender sender, String[] args) {
        Player player = player(sender);
        require(player, "plexoncrates.admin.items");
        if (args.length < 2) throw new CommandFailure("Usage: /crates item <inspect|compare> ...");

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) throw new CommandFailure("Hold the item to inspect in your main hand.");

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("inspect")) {
            sendItemInspection(sender, held);
            return;
        }
        if (!action.equals("compare")) {
            throw new CommandFailure("Item action must be inspect or compare.");
        }
        if (args.length < 4) {
            throw new CommandFailure("Usage: /crates item compare <crate> <key|icon|reward-id>");
        }

        Crate crate = crate(args, 2);
        String targetId = args[3];
        ItemStack target;
        String targetLabel;
        if (targetId.equalsIgnoreCase("key")) {
            target = crate.keyItem();
            targetLabel = crate.id() + " key";
        } else if (targetId.equalsIgnoreCase("icon")) {
            target = crate.icon();
            targetLabel = crate.id() + " icon";
        } else {
            Reward reward = crate.rewards().stream()
                    .filter(candidate -> candidate.id().equalsIgnoreCase(targetId))
                    .findFirst()
                    .orElseThrow(() -> new CommandFailure("Unknown reward in " + crate.id() + ": " + targetId));
            target = reward.displayItem();
            targetLabel = crate.id() + "/" + reward.id();
        }

        ItemDiagnostics.Comparison comparison = ItemDiagnostics.compare(held, target);
        sender.sendMessage(config.prefix() + "§6§lExact Item Comparison");
        sender.sendMessage("§7Target: §f" + targetLabel);
        sender.sendMessage("§7Held SHA-256: §f" + comparison.held().sha256());
        sender.sendMessage("§7Target SHA-256: §f" + comparison.target().sha256());
        sender.sendMessage("§7Exact bytes: " + yesNo(comparison.exact()));
        sender.sendMessage("§7Exact ignoring amount: " + yesNo(comparison.exactIgnoringAmount()));
        sender.sendMessage("§7Held amount / target amount: §f" + comparison.held().amount()
                + " §8/ §f" + comparison.target().amount());
    }

    private void sendItemInspection(CommandSender sender, ItemStack item) {
        ItemDiagnostics.SnapshotInfo info = ItemDiagnostics.inspect(item);
        sender.sendMessage(config.prefix() + "§6§lExact Item Inspection");
        sender.sendMessage("§7Material: §f" + info.material() + " §8x§f" + info.amount());
        sender.sendMessage("§7Format: §f" + info.format());
        sender.sendMessage("§7Native bytes: §f" + info.byteLength());
        sender.sendMessage("§7Minecraft data version: §f" + info.minecraftDataVersion());
        sender.sendMessage("§7SHA-256: §f" + info.sha256());
        sender.sendMessage("§7PDC keys: §f" + (info.pdcKeys().isEmpty() ? "none" : String.join(", ", info.pdcKeys())));
        sender.sendMessage("§7Native round-trip: §aPASS");
        sender.sendMessage("§8PDC values are intentionally not shown.");
    }

    private void migrate(CommandSender sender, String[] args) {
        require(sender, "plexoncrates.admin.migrate");
        if (args.length < 3 || !args[1].equalsIgnoreCase("phoenix")) {
            throw new CommandFailure("Usage: /crates migrate phoenix <scan|plan|report|import confirm>");
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "scan", "plan" -> planMigration(sender, action.equals("plan"));
            case "report" -> reportMigration(sender);
            case "import" -> {
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    throw new CommandFailure("Import is destructive to PlexonCrates state. Use: /crates migrate phoenix import confirm");
                }
                importMigration(sender);
            }
            default -> throw new CommandFailure("Phoenix action must be scan, plan, report or import confirm.");
        }
    }

    private void planMigration(CommandSender sender, boolean detailed) {
        sender.sendMessage(config.prefix() + "§eScanning PhoenixCratesLite data...");
        plugin.phoenixMigration().planAsync().whenComplete((plan, error) -> sync(() -> {
            if (error != null) {
                migrationFailure(sender, "Phoenix scan failed", error);
                return;
            }
            sendPlanSummary(sender, plan, detailed);
        }));
    }

    private void reportMigration(CommandSender sender) {
        sender.sendMessage(config.prefix() + "§eBuilding Phoenix migration report...");
        plugin.phoenixMigration().planAsync().thenCompose(plan ->
                plugin.phoenixMigration().writePlanReport(plan).thenApply(report -> new Object[]{plan, report}))
                .whenComplete((result, error) -> sync(() -> {
                    if (error != null) {
                        migrationFailure(sender, "Phoenix report failed", error);
                        return;
                    }
                    PhoenixMigrationService.Plan plan = (PhoenixMigrationService.Plan) result[0];
                    java.nio.file.Path report = (java.nio.file.Path) result[1];
                    sendPlanSummary(sender, plan, false);
                    sender.sendMessage(config.prefix() + "§aReport written to §f" + report + "§a.");
                }));
    }

    private void importMigration(CommandSender sender) {
        sender.sendMessage(config.prefix() + "§eRe-scanning Phoenix source before confirmed import...");
        plugin.phoenixMigration().planAsync().whenComplete((plan, scanError) -> {
            if (scanError != null) {
                sync(() -> migrationFailure(sender, "Phoenix import failed", scanError));
                return;
            }

            if (plan.ready()) {
                plugin.phoenixMigration().importConfirmed(plan).whenComplete((result, error) -> sync(() -> {
                    if (error != null) {
                        migrationFailure(sender, "Phoenix import failed", error);
                        return;
                    }
                    sendImportSuccess(sender, result);
                }));
                return;
            }

            if (!PhoenixPartialImportRecovery.canResume(plugin.crates(), plan)) {
                sync(() -> {
                    sendPlanSummary(sender, plan, true);
                    sender.sendMessage(config.prefix()
                            + "§cImport remains blocked. Existing data does not exactly match a recoverable partial import.");
                });
                return;
            }

            sync(() -> sender.sendMessage(config.prefix()
                    + "§eExact partial Phoenix import detected. Resuming historical database stage only..."));
            plugin.database().importPhoenixHistory(plan.sourceHash(), plan.openingRows(), plan.rewardRows())
                    .whenComplete((history, error) -> sync(() -> {
                        if (error != null) {
                            migrationFailure(sender, "Phoenix recovery failed", error);
                            return;
                        }
                        sender.sendMessage(config.prefix() + "§aPartial Phoenix import recovered successfully.");
                        sender.sendMessage("§7Existing disabled definitions verified unchanged: §f" + plan.crates().size());
                        sender.sendMessage("§7Historical openings: §f" + plan.openings()
                                + " §8| §7Reward wins: §f" + plan.rewardWins());
                        sender.sendMessage("§7Orphan reward wins preserved: §f" + plan.orphanRewardWins());
                        sender.sendMessage("§7History applied this run: §f" + history.applied());
                        sender.sendMessage(config.prefix()
                                + "§eReview imported crates in /crates admin before enabling them.");
                    }));
        });
    }

    private void sendImportSuccess(CommandSender sender, PhoenixMigrationService.ImportResult result) {
        sender.sendMessage(config.prefix() + "§aPhoenix import completed into disabled review definitions.");
        sender.sendMessage("§7Crates: §f" + result.crates() + " §8| §7Rewards: §f" + result.rewards()
                + " §8| §7Locations: §f" + result.locations());
        sender.sendMessage("§7Players: §f" + result.players() + " §8| §7Historical openings: §f" + result.openings()
                + " §8| §7Reward wins: §f" + result.rewardWins());
        sender.sendMessage("§7Orphan reward wins preserved: §f" + result.orphanRewardWins());
        sender.sendMessage("§7History applied this run: §f" + result.historyApplied());
        sender.sendMessage("§7Backup: §f" + result.backup());
        sender.sendMessage("§7Report: §f" + result.report());
        sender.sendMessage(config.prefix() + "§eReview imported crates in /crates admin before enabling them.");
    }

    private void sendPlanSummary(CommandSender sender, PhoenixMigrationService.Plan plan, boolean detailed) {
        String state = plan.ready() ? "§aREADY" : "§cBLOCKED";
        sender.sendMessage(config.prefix() + "§6Phoenix migration plan: " + state);
        sender.sendMessage("§7Source: §f" + plan.source());
        sender.sendMessage("§7SHA-256: §f" + plan.sourceHash());
        sender.sendMessage("§7Crates: §f" + plan.crates().size() + " §8| §7Rewards: §f" + plan.rewardCount()
                + " §8| §7Keys: §f" + plan.keyCount() + " §8| §7Locations: §f" + plan.locationCount());
        sender.sendMessage("§7Players: §f" + plan.playerCount() + " §8| §7Openings: §f" + plan.openings()
                + " §8| §7Reward wins: §f" + plan.rewardWins() + " §8| §7Orphans: §f" + plan.orphanRewardWins());
        sender.sendMessage("§7Warnings: §f" + plan.warnings().size() + " §8| §7Blocking errors: §f" + plan.errors().size());
        if (detailed) {
            for (String warning : plan.warnings()) sender.sendMessage("§e⚠ §7" + warning);
            for (String error : plan.errors()) sender.sendMessage("§c✖ §7" + error);
            for (Crate crate : plan.crates()) {
                int weight = crate.rewards().stream().mapToInt(reward -> reward.weight()).sum();
                sender.sendMessage("§8- §f" + crate.id() + " §7(" + crate.rewards().size()
                        + " rewards, " + crate.locations().size() + " locations, weight " + weight + ")");
            }
        }
    }

    private void migrationFailure(CommandSender sender, String prefix, Throwable error) {
        Throwable cause = unwrap(error);
        sender.sendMessage(config.prefix() + "§c" + prefix + ": " + message(cause));
        plugin.getLogger().log(Level.WARNING, prefix, cause);
    }

    private void reload(CommandSender sender) {
        require(sender, "plexoncrates.admin.reload");
        plugin.reloadPlugin();
        sender.sendMessage(config.message("reload-complete"));
    }

    private void givePhysical(Player target, Crate crate, int amount) {
        int remaining = amount;
        ItemStack template = crate.keyItem();
        while (remaining > 0) {
            ItemStack stack = template.clone();
            int give = Math.min(stack.getMaxStackSize(), remaining);
            stack.setAmount(give);
            remaining -= give;
            target.getInventory().addItem(stack).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        }
    }

    private Crate crate(String[] args, int index) {
        if (args.length <= index) throw new CommandFailure("A crate ID is required.");
        return plugin.crates().find(args[index])
                .orElseThrow(() -> new CommandFailure("Unknown crate: " + args[index]));
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        throw new CommandFailure("This action can only be used by a player.");
    }

    private static void require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission) && !sender.hasPermission("plexoncrates.admin")) {
            throw new CommandFailure("You do not have permission to do that.");
        }
    }

    private static int positive(String raw, int maximum) {
        int value = integer(raw, "number");
        if (value < 1 || value > maximum) {
            throw new CommandFailure("Number must be between 1 and " + maximum + ".");
        }
        return value;
    }

    private static int nonNegative(String raw, int maximum) {
        int value = integer(raw, "number");
        if (value < 0 || value > maximum) {
            throw new CommandFailure("Number must be between 0 and " + maximum + ".");
        }
        return value;
    }

    private static int integer(String raw, String label) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new CommandFailure("Invalid " + label + ": " + raw);
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(config.prefix() + "§6§lCommands");
        sender.sendMessage("§e/crates §7- Open the player crate menu");
        sender.sendMessage("§e/crates preview <crate> §7- Preview weighted rewards");
        sender.sendMessage("§e/crates open <crate> [virtual|physical] §7- Open a crate");
        sender.sendMessage("§e/crates claims [page] §7- Open durable claims");
        if (sender.hasPermission("plexoncrates.admin")) {
            sender.sendMessage("§c/crates admin [create <id>] §7- GUI administration");
            sender.sendMessage("§c/crates link <crate> §7- Link the block you are looking at");
            sender.sendMessage("§c/crates unlink §7- Remove a physical block link");
            sender.sendMessage("§c/crates key <give|physical> <player> <crate> <amount>");
            sender.sendMessage("§c/crates reward add <crate> <weight> §7- Capture held exact item");
            sender.sendMessage("§c/crates reward remove <crate> <reward>");
            sender.sendMessage("§c/crates reward weight <crate> <reward> <weight>");
            sender.sendMessage("§c/crates reward action <crate> <reward> <ITEM|COMMAND|MESSAGE|SOUND> [value]");
            sender.sendMessage("§c/crates item inspect §7- Fingerprint the held exact item");
            sender.sendMessage("§c/crates item compare <crate> <key|icon|reward-id>");
            sender.sendMessage("§c/crates migrate phoenix <scan|plan|report|import confirm>");
            sender.sendMessage("§c/crates reload");
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> root = new ArrayList<>(List.of("preview", "open", "claims", "help"));
            if (sender.hasPermission("plexoncrates.admin")) {
                root.addAll(List.of("admin", "link", "unlink", "key", "reward", "item", "migrate", "reload"));
            }
            return filter(root, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> crateIds = plugin.crates().all().stream().map(Crate::id).toList();
        if (args.length == 2 && List.of("preview", "open", "link").contains(sub)) return filter(crateIds, args[1]);
        if (sub.equals("open") && args.length == 3) return filter(List.of("virtual", "physical"), args[2]);
        if (sub.equals("admin") && args.length == 2) return filter(List.of("create"), args[1]);
        if (sub.equals("item")) {
            if (args.length == 2) return filter(List.of("inspect", "compare"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("compare")) return filter(crateIds, args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("compare")) {
                return plugin.crates().find(args[2])
                        .map(crate -> {
                            List<String> targets = new ArrayList<>(List.of("key", "icon"));
                            targets.addAll(crate.rewards().stream().map(Reward::id).toList());
                            return filter(targets, args[3]);
                        }).orElse(List.of());
            }
        }
        if (sub.equals("migrate")) {
            if (args.length == 2) return filter(List.of("phoenix"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("phoenix")) {
                return filter(List.of("scan", "plan", "report", "import"), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("phoenix") && args[2].equalsIgnoreCase("import")) {
                return filter(List.of("confirm"), args[3]);
            }
        }
        if (sub.equals("key")) {
            if (args.length == 2) return filter(List.of("give", "physical"), args[1]);
            if (args.length == 3) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
            if (args.length == 4) return filter(crateIds, args[3]);
            if (args.length == 5) return filter(List.of("1", "5", "10", "64"), args[4]);
        }
        if (sub.equals("reward")) {
            if (args.length == 2) return filter(List.of("add", "remove", "weight", "action"), args[1]);
            if (args.length == 3) return filter(crateIds, args[2]);
            if (args.length == 4 && List.of("remove", "weight", "action")
                    .contains(args[1].toLowerCase(Locale.ROOT))) {
                return plugin.crates().find(args[2])
                        .map(crate -> filter(crate.rewards().stream().map(Reward::id).toList(), args[3]))
                        .orElse(List.of());
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("add")) {
                return filter(List.of("1", "5", "10", "25", "100"), args[3]);
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("weight")) {
                return filter(List.of("1", "5", "10", "25", "100"), args[4]);
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("action")) {
                return filter(List.of("ITEM", "COMMAND", "MESSAGE", "SOUND"), args[4]);
            }
        }
        return List.of();
    }

    private void sync(Runnable task) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException && current.getCause() != null
                && current.getMessage() != null && current.getMessage().equals(current.getCause().toString())) {
            return current.getCause();
        }
        return current;
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private static String yesNo(boolean value) {
        return value ? "§aYES" : "§cNO";
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static final class CommandFailure extends RuntimeException {
        private CommandFailure(String message) {
            super(message);
        }
    }
}

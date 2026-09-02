package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.nio.file.Path;
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

public final class CratesAdminCommand implements CommandExecutor, TabCompleter {
    private final PlexonCrates plugin;

    public CratesAdminCommand(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String action = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed(sender, permission(action))) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (action.equals("gui")) {
            if (sender instanceof Player player) plugin.menus().openAdmin(player);
            else help(sender);
            return true;
        }
        try {
            switch (action) {
                case "help" -> help(sender);
                case "create" -> create(sender, args);
                case "edit" -> edit(sender, args);
                case "clone" -> cloneCrate(sender, args);
                case "import" -> importCrate(sender, args);
                case "export" -> exportCrate(sender, args);
                case "delete" -> deleteCrate(sender, args);
                case "keys" -> keys(sender, args);
                case "wand" -> wand(sender, args);
                case "link" -> link(sender, args);
                case "unlink" -> unlink(sender);
                case "reload" -> plugin.reloadFor(sender);
                case "validate" -> plugin.validateFor(sender);
                case "backup" -> plugin.backupFor(sender);
                case "diagnose" -> plugin.diagnoseFor(sender);
                case "status" -> status(sender);
                case "save" -> {
                    plugin.statistics().save();
                    plugin.messages().send(sender, "statistics-saved");
                }
                case "set" -> set(sender, args);
                case "unset" -> unset(sender);
                case "additem" -> addItem(sender, args);
                case "addcommand" -> addCommand(sender, args);
                case "remove" -> remove(sender, args);
                case "weight" -> weight(sender, args);
                case "givekey" -> giveKey(sender, args);
                case "open" -> forceOpen(sender, args);
                default -> help(sender);
            }
        } catch (PlayersOnly ignored) {
            // The helper already supplied the concise players-only message.
        } catch (Exception error) {
            plugin.configError(sender, error);
        }
        return true;
    }

    private void create(CommandSender sender, String[] args) throws Exception {
        if (args.length < 2) { help(sender); return; }
        Crate created = plugin.crates().createDraft(args[1], sender.getName());
        if (sender instanceof Player player) {
            plugin.database().saveDraft(created.id(), plugin.crates().serialized(created.id()), player.getUniqueId());
            plugin.menus().openEditor(player, created);
        } else sender.sendMessage(Text.parse("<green>Created persistent crate draft</green> <white>" + created.id() + "</white><green>.</green>"));
    }

    private void edit(CommandSender sender, String[] args) {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate != null) plugin.menus().openEditor(player, crate);
    }

    private void cloneCrate(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) { help(sender); return; }
        Crate clone = plugin.crates().cloneAsDraft(args[1], args[2], sender.getName());
        UUID editor = sender instanceof Player player ? player.getUniqueId() : null;
        plugin.database().saveDraft(clone.id(), plugin.crates().serialized(clone.id()), editor);
        if (sender instanceof Player player) plugin.menus().openEditor(player, clone);
        else sender.sendMessage(Text.parse("<green>Cloned crate as draft:</green> <white>" + clone.id() + "</white>"));
    }

    private void importCrate(CommandSender sender, String[] args) throws Exception {
        if (args.length < 3) { help(sender); return; }
        String fileName = args[1];
        if (!safeYamlName(fileName)) throw new IllegalArgumentException("Import filename must be a simple .yml name");
        Path root = plugin.getDataFolder().toPath().resolve("imports").toAbsolutePath().normalize();
        Path source = root.resolve(fileName).normalize();
        if (!source.getParent().equals(root)) throw new IllegalArgumentException("Import path leaves the imports directory");
        Crate imported = plugin.crates().importAsDraft(source, args[2], sender.getName());
        UUID editor = sender instanceof Player player ? player.getUniqueId() : null;
        plugin.database().saveDraft(imported.id(), plugin.crates().serialized(imported.id()), editor);
        if (sender instanceof Player player) plugin.menus().openEditor(player, imported);
        else sender.sendMessage(Text.parse("<green>Imported crate as draft:</green> <white>" + imported.id() + "</white>"));
    }

    private void exportCrate(CommandSender sender, String[] args) throws Exception {
        Crate crate = crate(sender, args, 1);
        if (crate == null) return;
        Path destination = plugin.crates().exportDefinition(crate.id(),
                plugin.getDataFolder().toPath().resolve("exports"));
        sender.sendMessage(Text.parse("<green>Exported</green> <white>" + crate.id()
                + "</white> <green>to</green> <white>exports/" + destination.getFileName() + "</white><green>.</green>"));
    }

    private void deleteCrate(CommandSender sender, String[] args) {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate != null) plugin.adminMenus().openCrateDeleteConfirmation(player, crate.id());
    }

    private void keys(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("sync")) {
            plugin.keys().syncDiscovery();
            sender.sendMessage(Text.parse("<green>Physical-key discovery refreshed.</green> <gray>Provider:</gray> <white>"
                    + plugin.keys().providerStatus() + "</white>"));
            return;
        }
        plugin.adminMenus().openKeys(player(sender), 0);
    }

    private void wand(CommandSender sender, String[] args) {
        Player player = player(sender);
        String crateId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!crateId.isBlank() && plugin.crates().find(crateId).isEmpty()) { invalidCrate(sender); return; }
        plugin.wand().give(player, crateId);
    }

    private void link(CommandSender sender, String[] args) {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate == null) return;
        Block block = target(player, sender);
        if (block != null) plugin.wand().link(player, block, crate);
    }

    private void unlink(CommandSender sender) {
        Player player = player(sender);
        Block block = target(player, sender);
        if (block == null) return;
        var link = plugin.locations().at(block).orElse(null);
        if (link == null) { plugin.messages().send(sender, "location-not-found"); return; }
        plugin.menus().openUnlinkConfirmation(player, link);
    }

    private void set(CommandSender sender, String[] args) throws Exception {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate == null) return;
        Block block = target(player, sender);
        if (block != null) plugin.wand().link(player, block, crate);
    }

    private void unset(CommandSender sender) throws Exception {
        Player player = player(sender);
        Block block = target(player, sender);
        if (block == null) return;
        var link = plugin.locations().at(block).orElse(null);
        if (link == null) { plugin.messages().send(sender, "location-not-found"); return; }
        plugin.menus().openUnlinkConfirmation(player, link);
    }

    private void addItem(CommandSender sender, String[] args) throws Exception {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate == null || args.length < 4) {
            help(sender);
            return;
        }
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        double weight = weight(args[3]);
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            plugin.messages().send(sender, "hold-item");
            return;
        }
        plugin.crates().addCapturedReward(crate.id(), rewardId, weight, held);
        plugin.messages().send(sender, "reward-added", Text.value("reward", rewardId),
                Text.component("crate", crate.displayName()), Text.value("weight", weight));
    }

    private void addCommand(CommandSender sender, String[] args) throws Exception {
        Crate crate = crate(sender, args, 1);
        if (crate == null || args.length < 5) {
            help(sender);
            return;
        }
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        double weight = weight(args[3]);
        String rewardCommand = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
        plugin.crates().addCommandReward(crate.id(), rewardId, weight, rewardCommand);
        plugin.messages().send(sender, "command-reward-added", Text.value("reward", rewardId),
                Text.component("crate", crate.displayName()));
    }

    private void remove(CommandSender sender, String[] args) throws Exception {
        Crate crate = crate(sender, args, 1);
        if (crate == null || args.length < 3) {
            help(sender);
            return;
        }
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        plugin.crates().removeReward(crate.id(), rewardId);
        plugin.messages().send(sender, "reward-removed", Text.value("reward", rewardId),
                Text.component("crate", crate.displayName()));
    }

    private void weight(CommandSender sender, String[] args) throws Exception {
        Crate crate = crate(sender, args, 1);
        if (crate == null || args.length < 4) {
            help(sender);
            return;
        }
        String rewardId = args[2].toLowerCase(Locale.ROOT);
        double value = weight(args[3]);
        plugin.crates().setWeight(crate.id(), rewardId, value);
        plugin.messages().send(sender, "weight-updated", Text.value("reward", rewardId),
                Text.component("crate", crate.displayName()), Text.value("weight", value));
    }

    private void giveKey(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found");
            return;
        }
        String keyId = args[2].toLowerCase(Locale.ROOT);
        int amount = args.length >= 4 ? positive(args[3], 10_000) : 1;
        ItemStack key = plugin.keys().template(keyId).orElseThrow(() -> new IllegalArgumentException("Unknown key ID"));
        plugin.keys().give(target, keyId, amount);
        var name = key.getItemMeta().displayName();
        plugin.messages().send(sender, "key-given", Text.value("amount", amount),
                Text.component("key", name == null ? Text.parse("<white>" + keyId + " key</white>") : name),
                Text.value("player", target.getName()));
    }

    private void forceOpen(CommandSender sender, String[] args) {
        if (args.length < 3) {
            help(sender);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messages().send(sender, "player-not-found");
            return;
        }
        Crate crate = plugin.crates().find(args[2]).orElse(null);
        if (crate == null) {
            invalidCrate(sender);
            return;
        }
        int amount = args.length >= 4 ? positive(args[3], plugin.settings().maximumBulk()) : 1;
        if (plugin.openings().open(target, crate, amount, true)) {
            plugin.messages().send(sender, "forced-open", Text.value("amount", amount),
                    Text.component("crate", crate.displayName()), Text.value("player", target.getName()));
        }
    }

    private void status(CommandSender sender) {
        plugin.messages().send(sender, "status", Text.value("crates", plugin.crates().all().size()),
                Text.value("rewards", plugin.crates().rewardCount()), Text.value("locations", plugin.locations().all().size()),
                Text.value("key_source", plugin.keys().sourceLabel()));
    }

    private Block target(Player player, CommandSender sender) {
        Block block = player.getTargetBlockExact(plugin.settings().targetDistance());
        if (block == null || block.getType().isAir()) {
            plugin.messages().send(sender, "target-required", Text.value("distance", plugin.settings().targetDistance()));
            return null;
        }
        return block;
    }

    private static String permission(String action) {
        return switch (action) {
            case "gui", "help", "status" -> "plexoncrates.admin.gui";
            case "create", "edit", "clone", "import", "export", "delete" -> "plexoncrates.admin.crates";
            case "keys" -> "plexoncrates.admin.keys";
            case "additem", "addcommand", "remove", "weight" -> "plexoncrates.admin.rewards";
            case "wand", "link", "unlink", "set", "unset" -> "plexoncrates.admin.locations";
            case "givekey", "open" -> "plexoncrates.admin.give";
            case "reload", "validate", "save" -> "plexoncrates.admin.reload";
            case "backup" -> "plexoncrates.admin.backup";
            case "diagnose" -> "plexoncrates.admin.diagnose";
            default -> "plexoncrates.admin";
        };
    }

    private static boolean allowed(CommandSender sender, String permission) {
        return sender.hasPermission("plexoncrates.admin") || sender.hasPermission(permission);
    }

    private Crate crate(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            invalidCrate(sender);
            return null;
        }
        Crate crate = plugin.crates().find(args[index]).orElse(null);
        if (crate == null) invalidCrate(sender);
        return crate;
    }

    private void invalidCrate(CommandSender sender) {
        plugin.messages().send(sender, "invalid-crate", Text.value("crates", plugin.crates().ids()));
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        plugin.messages().send(sender, "players-only");
        throw new PlayersOnly();
    }

    private static double weight(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value <= 0 || value > 1_000_000_000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Weight must be a finite number greater than zero");
        }
    }

    private static int positive(String raw, int maximum) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Amount must be between 1 and " + maximum);
        }
    }

    private static boolean safeYamlName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.yml");
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>PlexonCrates</bold></gradient> <dark_gray>•</dark_gray> <gold>Administration</gold>"));
        sender.sendMessage(Text.parse("<white>/pcrates</white> <dark_gray>—</dark_gray> <gray>Open the visual editor.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates create <id></white> <dark_gray>•</dark_gray> <white>edit <crate></white> <dark_gray>•</dark_gray> <white>clone <crate> <new-id></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates import <file.yml> <new-id></white> <dark_gray>•</dark_gray> <white>export <crate></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates delete <crate></white> <dark_gray>•</dark_gray> <white>keys [sync]</white> <dark_gray>•</dark_gray> <white>wand [crate]</white>"));
        sender.sendMessage(Text.parse("<white>/pcrates link <crate></white> <dark_gray>—</dark_gray> <gray>Link the block you are looking at.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates unlink</white> <dark_gray>—</dark_gray> <gray>Confirm unlinking the target block.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates additem <crate> <id> <weight></white> <dark_gray>—</dark_gray> <gray>Capture the held item.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates addcommand <crate> <id> <weight> <command></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates remove <crate> <reward></white> <dark_gray>•</dark_gray> <white>weight <crate> <reward> <weight></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates givekey <player> <key> [amount]</white>"));
        sender.sendMessage(Text.parse("<white>/pcrates open <player> <crate> [amount]</white> <dark_gray>—</dark_gray> <gray>Administrative keyless opening.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates validate</white> <dark_gray>•</dark_gray> <white>reload</white> <dark_gray>•</dark_gray> <white>backup</white> <dark_gray>•</dark_gray> <white>status</white> <dark_gray>•</dark_gray> <white>diagnose</white>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("plexoncrates.admin") && !sender.hasPermission("plexoncrates.admin.gui")) return List.of();
        if (args.length == 1) return filter(List.of("gui", "create", "edit", "clone", "import", "export", "delete", "keys", "wand",
                "link", "unlink", "set", "unset", "additem", "addcommand", "remove", "weight", "givekey",
                "open", "validate", "reload", "backup", "diagnose", "save", "status", "help"), args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("edit", "export", "delete", "link", "set", "additem", "addcommand", "remove", "weight").contains(action)) {
            return filter(plugin.crates().ordered().stream().map(Crate::id).toList(), args[1]);
        }
        if (args.length == 2 && action.equals("clone")) return filter(plugin.crates().orderedAdmin().stream().map(Crate::id).toList(), args[1]);
        if (args.length == 2 && action.equals("keys")) return filter(List.of("sync"), args[1]);
        if (args.length == 2 && action.equals("wand")) return filter(plugin.crates().orderedAdmin().stream().map(Crate::id).toList(), args[1]);
        if (args.length == 2 && List.of("givekey", "open").contains(action)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && action.equals("givekey")) {
            return filter(plugin.crates().ordered().stream().map(Crate::keyId).distinct().toList(), args[2]);
        }
        if (args.length == 3 && action.equals("open")) {
            return filter(plugin.crates().ordered().stream().map(Crate::id).toList(), args[2]);
        }
        if (args.length == 3 && List.of("remove", "weight").contains(action)) {
            return plugin.crates().find(args[1]).map(crate -> filter(new ArrayList<>(crate.rewards().keySet()), args[2])).orElse(List.of());
        }
        if ((args.length == 4 && List.of("additem", "addcommand", "weight").contains(action))
                || (args.length == 4 && List.of("givekey", "open").contains(action))) {
            return filter(List.of("1", "5", "10", "64"), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static final class PlayersOnly extends RuntimeException {}
}

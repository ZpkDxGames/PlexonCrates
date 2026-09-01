package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
        if (!sender.hasPermission("plexoncrates.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            if (sender instanceof Player player) plugin.menus().openAdmin(player);
            else help(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "help" -> help(sender);
                case "reload" -> plugin.reloadFor(sender);
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

    private void set(CommandSender sender, String[] args) throws Exception {
        Player player = player(sender);
        Crate crate = crate(sender, args, 1);
        if (crate == null) return;
        Block block = player.getTargetBlockExact(plugin.settings().targetDistance());
        if (block == null || block.getType().isAir()) {
            plugin.messages().send(sender, "target-required", Text.value("distance", plugin.settings().targetDistance()));
            return;
        }
        plugin.locations().set(block, crate.id());
        plugin.displays().refresh();
        plugin.messages().send(sender, "location-set", Text.component("crate", crate.displayName()));
    }

    private void unset(CommandSender sender) throws Exception {
        Player player = player(sender);
        Block block = player.getTargetBlockExact(plugin.settings().targetDistance());
        if (block == null || block.getType().isAir()) {
            plugin.messages().send(sender, "target-required", Text.value("distance", plugin.settings().targetDistance()));
            return;
        }
        if (!plugin.locations().remove(block)) {
            plugin.messages().send(sender, "location-not-found");
            return;
        }
        plugin.displays().refresh();
        plugin.messages().send(sender, "location-removed");
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

    private void help(CommandSender sender) {
        sender.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>PlexonCrates</bold></gradient> <dark_gray>•</dark_gray> <gold>Administration</gold>"));
        sender.sendMessage(Text.parse("<white>/pcrates</white> <dark_gray>—</dark_gray> <gray>Open the visual editor.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates set <crate></white> <dark_gray>—</dark_gray> <gray>Link the block you are looking at.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates unset</white> <dark_gray>—</dark_gray> <gray>Unlink the target block.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates additem <crate> <id> <weight></white> <dark_gray>—</dark_gray> <gray>Capture the held item.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates addcommand <crate> <id> <weight> <command></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates remove <crate> <reward></white> <dark_gray>•</dark_gray> <white>weight <crate> <reward> <weight></white>"));
        sender.sendMessage(Text.parse("<white>/pcrates givekey <player> <key> [amount]</white>"));
        sender.sendMessage(Text.parse("<white>/pcrates open <player> <crate> [amount]</white> <dark_gray>—</dark_gray> <gray>Administrative keyless opening.</gray>"));
        sender.sendMessage(Text.parse("<white>/pcrates reload</white> <dark_gray>•</dark_gray> <white>status</white> <dark_gray>•</dark_gray> <white>save</white>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("plexoncrates.admin")) return List.of();
        if (args.length == 1) return filter(List.of("gui", "set", "unset", "additem", "addcommand", "remove", "weight", "givekey", "open", "reload", "save", "status", "help"), args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("set", "additem", "addcommand", "remove", "weight").contains(action)) {
            return filter(plugin.crates().ordered().stream().map(Crate::id).toList(), args[1]);
        }
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

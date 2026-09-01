package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CratesCommand implements CommandExecutor, TabCompleter {
    private final PlexonCrates plugin;

    public CratesCommand(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("plexoncrates.use")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            plugin.menus().openBrowser(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>PlexonCrates</bold></gradient> <dark_gray>•</dark_gray> <gray>Player commands</gray>"));
            player.sendMessage(Text.parse("<white>/crates</white> <dark_gray>—</dark_gray> <gray>Browse every crate.</gray>"));
            player.sendMessage(Text.parse("<white>/crates preview <crate></white> <dark_gray>—</dark_gray> <gray>Preview rewards.</gray>"));
            player.sendMessage(Text.parse("<white>/crates open <crate> [amount]</white> <dark_gray>—</dark_gray> <gray>Open using physical PlexonKeys keys.</gray>"));
            return true;
        }
        if (action.equals("preview") || action.equals("open")) {
            if (args.length < 2) {
                invalidCrate(player);
                return true;
            }
            Crate crate = plugin.crates().find(args[1]).orElse(null);
            if (crate == null) {
                invalidCrate(player);
                return true;
            }
            if (action.equals("preview")) {
                plugin.menus().openPreview(player, crate, 0, false);
                return true;
            }
            int amount = args.length >= 3 ? amount(player, args[2]) : 1;
            if (amount > 0) plugin.openings().open(player, crate, amount, false);
            return true;
        }
        Crate direct = plugin.crates().find(action).orElse(null);
        if (direct != null) plugin.menus().openPreview(player, direct, 0, false);
        else invalidCrate(player);
        return true;
    }

    private int amount(Player player, String raw) {
        try {
            int amount = Integer.parseInt(raw);
            if (amount < 1 || amount > plugin.settings().maximumBulk()) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException error) {
            plugin.messages().send(player, "invalid-amount", Text.value("maximum", plugin.settings().maximumBulk()));
            return -1;
        }
    }

    private void invalidCrate(CommandSender sender) {
        plugin.messages().send(sender, "invalid-crate", Text.value("crates", plugin.crates().ids()));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            var values = new ArrayList<>(List.of("preview", "open", "help"));
            values.addAll(plugin.crates().ordered().stream().map(Crate::id).toList());
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("open"))) {
            return filter(plugin.crates().ordered().stream().map(Crate::id).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("open")) return filter(List.of("1", "5", "10", "64"), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}

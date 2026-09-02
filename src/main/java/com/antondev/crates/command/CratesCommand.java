package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.domain.opening.OpenSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.bukkit.command.Command;
import org.bukkit.Bukkit;
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
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (args.length == 0) {
            if (!allowed(player, "plexoncrates.use")) return denied(player);
            plugin.menus().openBrowser(player);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help")) {
            player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>PlexonCrates</bold></gradient> <dark_gray>•</dark_gray> <gray>Player commands</gray>"));
            player.sendMessage(Text.parse("<white>/crates</white> <dark_gray>—</dark_gray> <gray>Browse every crate.</gray>"));
            player.sendMessage(Text.parse("<white>/crates preview <crate></white> <dark_gray>—</dark_gray> <gray>Preview rewards.</gray>"));
            player.sendMessage(Text.parse("<white>/crates open <crate> [amount]</white> <dark_gray>—</dark_gray> <gray>Open using physical PlexonKeys keys.</gray>"));
            player.sendMessage(Text.parse("<white>/crates history [page]</white> <dark_gray>—</dark_gray> <gray>Review recent wins.</gray>"));
            return true;
        }
        if (action.equals("history")) {
            if (!allowed(player, "plexoncrates.history")) return denied(player);
            int page = args.length >= 2 ? page(player, args[1]) : 1;
            if (page > 0) history(player, page);
            return true;
        }
        if (action.equals("preview") || action.equals("open")) {
            String permission = action.equals("preview") ? "plexoncrates.preview" : "plexoncrates.open";
            if (!allowed(player, permission)) return denied(player);
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
            if (amount > 0) plugin.openings().open(player, crate, amount, OpenSource.COMMAND, null);
            return true;
        }
        if (!allowed(player, "plexoncrates.preview")) return denied(player);
        Crate direct = plugin.crates().find(action).orElse(null);
        if (direct != null) plugin.menus().openPreview(player, direct, 0, false);
        else invalidCrate(player);
        return true;
    }

    private void history(Player player, int page) {
        int pageSize = 8;
        plugin.database().historyAsync(player.getUniqueId(), pageSize, (page - 1) * pageSize)
                .whenComplete((records, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) {
                            plugin.messages().send(player, "database-error");
                            return;
                        }
                        player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Opening History</bold></gradient> <dark_gray>•</dark_gray> <gray>Page " + page + "</gray>"));
                        if (records.isEmpty()) {
                            player.sendMessage(Text.parse("<gray>No openings were found on this page.</gray>"));
                            return;
                        }
                        DateTimeFormatter time = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
                        records.forEach(record -> player.sendMessage(Text.parse("<dark_gray>•</dark_gray> <white>"
                                + time.format(record.completedAt()) + "</white> <gray>" + record.crateId() + " → "
                                + record.rewardIds().replace(',', ' ') + "</gray>")));
                    });
                });
    }

    private int page(Player player, String raw) {
        try {
            int page = Integer.parseInt(raw);
            if (page < 1 || page > 100_000) throw new NumberFormatException();
            return page;
        } catch (NumberFormatException error) {
            player.sendMessage(Text.parse("<red>History page must be a positive whole number.</red>"));
            return -1;
        }
    }

    private boolean allowed(Player player, String permission) {
        return player.hasPermission(permission);
    }

    private boolean denied(Player player) {
        plugin.messages().send(player, "no-permission");
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
            var values = new ArrayList<>(List.of("preview", "open", "history", "help"));
            values.addAll(plugin.crates().ordered().stream().map(Crate::id).toList());
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("open"))) {
            return filter(plugin.crates().ordered().stream().map(Crate::id).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("open")) return filter(List.of("1", "5", "10", "64"), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) return filter(List.of("1", "2", "3"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}

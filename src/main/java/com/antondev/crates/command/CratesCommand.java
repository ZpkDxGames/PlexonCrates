package com.antondev.crates.command;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.model.Crate;
import com.antondev.crates.domain.opening.OpenSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
            player.sendMessage(Text.parse("<white>/crates claim [page|id]</white> <dark_gray>—</dark_gray> <gray>Deliver exact pending claims.</gray>"));
            player.sendMessage(Text.parse("<white>/crates keys</white> <dark_gray>—</dark_gray> <gray>View physical and optional virtual-key balances.</gray>"));
            player.sendMessage(Text.parse("<white>/crates milestones <crate></white> <dark_gray>—</dark_gray> <gray>View durable opening progress.</gray>"));
            player.sendMessage(Text.parse("<white>/crates rerolls</white> <dark_gray>—</dark_gray> <gray>View reroll-token balance.</gray>"));
            return true;
        }
        if (action.equals("history")) {
            if (!allowed(player, "plexoncrates.history")) return denied(player);
            int page = args.length >= 2 ? page(player, args[1]) : 1;
            if (page > 0) history(player, page);
            return true;
        }
        if (action.equals("claim")) {
            if (!allowed(player, "plexoncrates.claim")) return denied(player);
            if (args.length < 2) {
                claims(player, 1);
                return true;
            }
            try {
                plugin.claims().claim(player, UUID.fromString(args[1]));
            } catch (IllegalArgumentException error) {
                int requested = page(player, args[1]);
                if (requested > 0) claims(player, requested);
            }
            return true;
        }
        if (action.equals("keys")) {
            if (!allowed(player, "plexoncrates.use")) return denied(player);
            keys(player);
            return true;
        }
        if (action.equals("milestones")) {
            if (!allowed(player, "plexoncrates.milestones")) return denied(player);
            if (!plugin.settings().milestonesEnabled()) return disabled(player);
            String crateId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            milestones(player, crateId);
            return true;
        }
        if (action.equals("rerolls")) {
            if (!allowed(player, "plexoncrates.rerolls")) return denied(player);
            if (!plugin.settings().rerollsEnabled()) return disabled(player);
            rerolls(player);
            return true;
        }
        if (action.equals("preview") || action.equals("open")) {
            String permission = action.equals("preview") ? "plexoncrates.preview" : "plexoncrates.open";
            if (!allowed(player, permission)) return denied(player);
            if (args.length < 2) {
                invalidCrate(player);
                return true;
            }
            Crate crate = plugin.runtime().find(args[1]).orElse(null);
            if (crate == null) {
                invalidCrate(player);
                return true;
            }
            if (action.equals("preview")) {
                plugin.menus().openPreview(player, crate, 0, false);
                return true;
            }
            int amount = args.length >= 3 ? amount(player, args[2]) : 1;
            if (amount > 0) {
                if (crate.paymentPolicy() == com.antondev.crates.domain.key.KeyPaymentPolicy.PLAYER_CHOICE
                        || crate.openingMode() == com.antondev.crates.domain.opening.OpeningMode.SELECTIVE) {
                    plugin.menus().openPreview(player, crate, 0, false);
                } else {
                    plugin.openings().open(player, crate, amount, OpenSource.COMMAND, null);
                }
            }
            return true;
        }
        if (!allowed(player, "plexoncrates.preview")) return denied(player);
        Crate direct = plugin.runtime().find(action).orElse(null);
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

    private void claims(Player player, int page) {
        plugin.claims().list(player.getUniqueId(), page).whenComplete((entries, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Claim Inbox</bold></gradient> <dark_gray>•</dark_gray> <gray>Page " + page + "</gray>"));
                if (entries == null || entries.isEmpty()) {
                    player.sendMessage(Text.parse("<gray>No pending exact claims were found on this page.</gray>"));
                    return;
                }
                for (DatabaseService.ClaimEntry entry : entries) {
                    Component line = Text.parse("<dark_gray>•</dark_gray> <white><id></white> <gray><source> · <state></gray> ",
                            Text.value("id", entry.claimId()), Text.value("source", entry.sourceType()),
                            Text.value("state", entry.state()))
                            .append(Text.parse("<green>[Claim]</green>")
                                    .clickEvent(ClickEvent.runCommand("/crates claim " + entry.claimId())));
                    player.sendMessage(line);
                }
            });
        });
    }

    private void milestones(Player player, String crateId) {
        if (crateId.isBlank()) {
            player.sendMessage(Text.parse("<gray>Use <white>/crates milestones <crate></white> to view one crate's progress.</gray>"));
            return;
        }
        if (plugin.runtime().find(crateId).isEmpty()) {
            invalidCrate(player);
            return;
        }
        plugin.database().loadMilestoneState(player.getUniqueId(), crateId).whenComplete((state, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null || state == null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                int earned = milestoneCount(state.earnedPayload());
                player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Milestones</bold></gradient> <dark_gray>•</dark_gray> <gray>"
                        + crateId + "</gray>"));
                player.sendMessage(Text.parse("<gray>Openings:</gray> <white>" + state.openings()
                        + "</white> <dark_gray>•</dark_gray> <gray>Earned:</gray> <white>" + earned
                        + "</white>"));
                player.sendMessage(Text.parse("<gray>Progress is updated only after a successful opening finalizes.</gray>"));
            });
        });
    }

    private void rerolls(Player player) {
        plugin.database().loadRerollBalance(player.getUniqueId()).whenComplete((balance, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null || balance == null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Reroll Tokens</bold></gradient>"));
                player.sendMessage(Text.parse("<gray>Available:</gray> <white>" + balance.balance()
                        + "</white> <dark_gray>•</dark_gray> <gray>Reroll offers always retain an Accept action.</gray>"));
            });
        });
    }

    private static int milestoneCount(byte[] payload) {
        if (payload == null || payload.length == 0) return 0;
        int count = 0;
        for (byte value : payload) if (value == '\n') count++;
        return count + 1;
    }

    private void keys(Player player) {
        if (!plugin.settings().virtualKeyWalletEnabled()) {
            player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Physical Key Balances</bold></gradient>"));
            plugin.keys().definitions().stream()
                    .sorted(java.util.Comparator.comparing(definition -> definition.id()))
                    .forEach(definition -> player.sendMessage(Text.parse("<gray>Physical " + definition.id()
                            + ":</gray> <white>" + plugin.keys().count(player, definition.id()) + "</white>")));
            return;
        }
        plugin.database().loadVirtualKeyBalances(player.getUniqueId(), 50, 0).whenComplete((virtual, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                player.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>Key Balances</bold></gradient>"));
                plugin.keys().definitions().stream()
                        .sorted(java.util.Comparator.comparing(definition -> definition.id()))
                        .forEach(definition -> player.sendMessage(Text.parse("<gray>Physical " + definition.id()
                                + ":</gray> <white>" + plugin.keys().count(player, definition.id()) + "</white>")));
                if (virtual == null || virtual.isEmpty()) {
                    player.sendMessage(Text.parse("<gray>Virtual:</gray> <white>none</white>"));
                } else {
                    virtual.forEach(balance -> player.sendMessage(Text.parse("<gray>Virtual " + balance.keyId()
                            + ":</gray> <white>" + balance.balance() + "</white>")));
                }
                plugin.claims().pendingCount(player.getUniqueId()).whenComplete((pending, pendingError) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && pendingError == null) {
                            player.sendMessage(Text.parse("<gray>Claim Inbox:</gray> <white>" + pending + " pending</white>"));
                        }
                    });
                });
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

    private boolean disabled(Player player) {
        plugin.messages().send(player, "disabled");
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
        plugin.messages().send(sender, "invalid-crate", Text.value("crates", plugin.runtime().ids()));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            var values = new ArrayList<>(List.of("preview", "open", "history", "claim", "keys", "milestones", "rerolls", "help"));
            values.addAll(plugin.runtime().ordered().stream().map(Crate::id).toList());
            return filter(values, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("open"))) {
            return filter(plugin.runtime().ordered().stream().map(Crate::id).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("open")) return filter(List.of("1", "5", "10", "64"), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) return filter(List.of("1", "2", "3"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("milestones")) return filter(plugin.runtime().ordered().stream().map(Crate::id).toList(), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}

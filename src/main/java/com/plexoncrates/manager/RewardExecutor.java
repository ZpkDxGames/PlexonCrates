package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.util.ColorUtil;
import com.plexoncrates.util.ItemCodec;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Executes a selected reward on the server thread and persists overflow/stats asynchronously. */
public final class RewardExecutor {
    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final DatabaseManager database;

    public RewardExecutor(PlexonCrates plugin, ConfigManager config, DatabaseManager database) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
    }

    public void execute(Player player, Crate crate, Reward reward) {
        if (!Bukkit.isPrimaryThread()) {
            if (!plugin.isEnabled()) {
                plugin.getLogger().severe("Refused to execute reward off-thread after PlexonCrates was disabled: "
                        + crate.id() + "/" + reward.id());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> execute(player, crate, reward));
            return;
        }

        for (RewardAction action : reward.actions()) {
            try {
                switch (action.type()) {
                    case ITEM -> {
                        ItemStack delivered = action.item();
                        if (delivered == null) {
                            // Compatibility for older definitions where the canonical reward item was
                            // stored as the display item. New captures persist an explicit exact item.
                            delivered = reward.displayItem();
                            plugin.getLogger().warning("Legacy ITEM action without an explicit exact snapshot: "
                                    + crate.id() + "/" + reward.id());
                        }
                        giveItem(player, crate, reward, delivered);
                    }
                    case COMMAND -> {
                        String command = placeholders(action.value(), player, crate, reward);
                        if (!command.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    }
                    case MESSAGE -> player.sendMessage(ColorUtil.color(placeholders(action.value(), player, crate, reward)));
                    case SOUND -> playSound(player, action.value());
                }
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE,
                        "Reward action " + action.type() + " failed for " + crate.id() + "/" + reward.id(), error);
            }
        }

        database.recordOpening(player.getUniqueId(), player.getName(), crate.id(), reward.id()).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Could not record crate opening statistics", error);
            return null;
        });
    }

    private void giveItem(Player player, Crate crate, Reward reward, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new IllegalArgumentException("Reward ITEM action resolved to an invalid item");
        }
        // Clone immediately: amount/inventory mutations must never touch the canonical definition.
        ItemStack delivery = item.clone();
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(delivery);
        if (leftovers.isEmpty()) return;

        for (ItemStack leftover : leftovers.values()) {
            if (!config.claimsEnabled()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                continue;
            }
            byte[] exactBytes = ItemCodec.encodeBytes(leftover);
            database.queueClaim(player.getUniqueId(), crate.id(), reward.id(), exactBytes).exceptionally(error -> {
                plugin.getLogger().log(Level.SEVERE, "Could not persist overflow reward claim", error);
                if (plugin.isEnabled() && player.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                }
                return null;
            });
        }
    }

    @SuppressWarnings("removal")
    private void playSound(Player player, String raw) {
        if (!config.soundsEnabled() || raw == null || raw.isBlank()) return;
        try {
            player.playSound(player.getLocation(), Sound.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)), 1.0F, 1.0F);
        } catch (IllegalArgumentException error) {
            plugin.getLogger().warning("Unknown reward sound: " + raw);
        }
    }

    private static String placeholders(String input, Player player, Crate crate, Reward reward) {
        if (input == null) return "";
        return input.replace("%player%", player.getName())
                .replace("%crate%", crate.id())
                .replace("%reward%", reward.id());
    }
}

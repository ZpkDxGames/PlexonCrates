package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.util.WeightedSelector;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Owns key reservation/consumption and the hand-off into an opening animation. */
public final class OpeningManager {
    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final KeyManager keys;
    private final AnimationManager animations;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public OpeningManager(PlexonCrates plugin, ConfigManager config, KeyManager keys, AnimationManager animations) {
        this.plugin = plugin;
        this.config = config;
        this.keys = keys;
        this.animations = animations;
    }

    public void openVirtual(Player player, Crate crate) {
        if (!crate.enabled()) return;
        UUID playerId = player.getUniqueId();
        if (!reserve(playerId)) {
            player.sendMessage(config.message("already-opening"));
            return;
        }

        Optional<Reward> reward = WeightedSelector.select(crate.rewards());
        if (reward.isEmpty()) {
            release(playerId);
            player.sendMessage(config.prefix() + "§cThis crate has no enabled weighted rewards.");
            return;
        }

        keys.consumeVirtual(playerId, crate).whenComplete((consumed, error) -> {
            if (!plugin.isEnabled()) {
                release(playerId);
                if (error == null && Boolean.TRUE.equals(consumed)) {
                    refundVirtual(playerId, crate, "plugin disabled before opening animation");
                }
                return;
            }

            try {
                Bukkit.getScheduler().runTask(plugin, () -> finishVirtualOpen(player, crate, reward.get(), consumed, error));
            } catch (Throwable schedulerFailure) {
                release(playerId);
                if (error == null && Boolean.TRUE.equals(consumed)) {
                    refundVirtual(playerId, crate, "scheduler rejected opening hand-off");
                }
                plugin.getLogger().log(Level.SEVERE, "Could not schedule virtual crate opening", schedulerFailure);
            }
        });
    }

    private void finishVirtualOpen(Player player, Crate crate, Reward reward, Boolean consumed, Throwable error) {
        UUID playerId = player.getUniqueId();
        if (error != null) {
            release(playerId);
            plugin.getLogger().log(Level.SEVERE, "Virtual key transaction failed", error);
            if (player.isOnline()) player.sendMessage(config.prefix() + "§cThe virtual key transaction failed.");
            return;
        }
        if (!Boolean.TRUE.equals(consumed)) {
            release(playerId);
            if (player.isOnline()) player.sendMessage(config.message("no-key", "crate", crate.id()));
            return;
        }
        if (!player.isOnline()) {
            release(playerId);
            refundVirtual(playerId, crate, "player disconnected before opening animation");
            return;
        }

        try {
            animations.start(player, crate, reward, null);
        } catch (Throwable openingFailure) {
            refundVirtual(playerId, crate, "opening manager failed before animation ownership");
            plugin.getLogger().log(Level.SEVERE, "Virtual crate opening failed before settlement", openingFailure);
            player.sendMessage(config.prefix() + "§cThe opening failed safely and your virtual key is being refunded.");
        } finally {
            release(playerId);
        }
    }

    public void openPhysical(Player player, Crate crate, Location source) {
        if (!crate.enabled()) return;
        UUID playerId = player.getUniqueId();
        if (!reserve(playerId)) {
            player.sendMessage(config.message("already-opening"));
            return;
        }

        Optional<Reward> reward = WeightedSelector.select(crate.rewards());
        if (reward.isEmpty()) {
            release(playerId);
            player.sendMessage(config.prefix() + "§cThis crate has no enabled weighted rewards.");
            return;
        }
        if (!keys.consumePhysical(player, crate)) {
            release(playerId);
            player.sendMessage(config.message("no-key", "crate", crate.id()));
            return;
        }

        try {
            animations.start(player, crate, reward.get(), source);
        } catch (Throwable openingFailure) {
            plugin.getLogger().log(Level.SEVERE, "Physical crate opening failed before settlement", openingFailure);
            keys.refundPhysical(player, crate).exceptionally(refundFailure -> {
                plugin.getLogger().log(Level.SEVERE, "Physical key refund also failed", refundFailure);
                return null;
            });
            player.sendMessage(config.prefix() + "§cThe opening failed safely and your physical key is being refunded.");
        } finally {
            release(playerId);
        }
    }

    public boolean isOpeningOrPending(UUID playerId) {
        return pending.contains(playerId) || animations.isOpening(playerId);
    }

    public void release(UUID playerId) {
        pending.remove(playerId);
    }

    private boolean reserve(UUID playerId) {
        if (animations.isOpening(playerId)) return false;
        return pending.add(playerId);
    }

    private void refundVirtual(UUID playerId, Crate crate, String reason) {
        keys.grantVirtual(playerId, crate, 1L).whenComplete((balance, refundError) -> {
            if (refundError != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not refund virtual key for " + playerId + " / " + crate.id() + " after " + reason,
                        refundError);
            } else {
                plugin.getLogger().fine("Refunded virtual key for " + playerId + " / " + crate.id() + " after " + reason);
            }
        });
    }
}

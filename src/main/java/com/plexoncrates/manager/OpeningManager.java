package com.plexoncrates.manager;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.util.WeightedSelector;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class OpeningManager {
    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final KeyManager keys;
    private final AnimationManager animations;

    public OpeningManager(PlexonCrates plugin, ConfigManager config, KeyManager keys, AnimationManager animations) {
        this.plugin = plugin; this.config = config; this.keys = keys; this.animations = animations;
    }

    public void openVirtual(Player player, Crate crate) {
        if (!crate.enabled()) return;
        if (animations.isOpening(player.getUniqueId())) { player.sendMessage(config.message("already-opening")); return; }
        Optional<Reward> reward = WeightedSelector.select(crate.rewards());
        if (reward.isEmpty()) { player.sendMessage(config.prefix() + "§cThis crate has no enabled weighted rewards."); return; }
        keys.consumeVirtual(player.getUniqueId(), crate).whenComplete((consumed, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) { plugin.getLogger().log(Level.SEVERE, "Virtual key transaction failed", error); player.sendMessage(config.prefix() + "§cThe virtual key transaction failed."); return; }
                if (!Boolean.TRUE.equals(consumed)) { player.sendMessage(config.message("no-key", "crate", crate.id())); return; }
                animations.start(player, crate, reward.get(), null);
            });
        });
    }

    public void openPhysical(Player player, Crate crate, Location source) {
        if (!crate.enabled()) return;
        if (animations.isOpening(player.getUniqueId())) { player.sendMessage(config.message("already-opening")); return; }
        Optional<Reward> reward = WeightedSelector.select(crate.rewards());
        if (reward.isEmpty()) { player.sendMessage(config.prefix() + "§cThis crate has no enabled weighted rewards."); return; }
        if (!keys.consumePhysical(player, crate)) { player.sendMessage(config.message("no-key", "crate", crate.id())); return; }
        animations.start(player, crate, reward.get(), source);
    }
}

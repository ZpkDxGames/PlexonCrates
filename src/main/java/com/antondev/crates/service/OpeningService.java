package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class OpeningService {
    private final PlexonCrates plugin;
    private final OpeningLog log;
    private final Set<UUID> animating = new HashSet<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public OpeningService(PlexonCrates plugin, OpeningLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public boolean open(Player player, Crate crate, int amount, boolean forced) {
        if (!plugin.settings().enabled() || !crate.enabled()) {
            plugin.messages().send(player, "disabled");
            return false;
        }
        if (!plugin.settings().allows(player.getWorld())) {
            plugin.messages().send(player, "invalid-world");
            return false;
        }
        if (!crate.permission().isBlank() && !player.hasPermission(crate.permission())) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        if (amount < 1 || amount > plugin.settings().maximumBulk()) {
            plugin.messages().send(player, "invalid-amount", Text.value("maximum", plugin.settings().maximumBulk()));
            return false;
        }
        boolean animate = amount == 1 && plugin.settings().animationEnabled();
        if (animate && animating.contains(player.getUniqueId())) {
            plugin.messages().send(player, "already-opening");
            return false;
        }
        if (!forced && !player.hasPermission("plexoncrates.bypass.cooldown")) {
            long remaining = cooldownRemaining(player, crate);
            if (remaining > 0) {
                plugin.messages().send(player, "cooldown", Text.value("seconds", Math.max(1, (remaining + 999) / 1000)));
                return false;
            }
        }

        boolean consumeKey = !forced && !player.hasPermission("plexoncrates.bypass.key");
        if (consumeKey && plugin.keys().count(player, crate.keyId()) < amount) {
            plugin.messages().send(player, "no-key", Text.component("key", keyName(crate)));
            return false;
        }

        var selected = new ArrayList<CrateReward>(amount);
        for (int index = 0; index < amount; index++) {
            var reward = RewardSelector.select(crate.orderedRewards(), player);
            if (reward.isEmpty()) {
                plugin.messages().send(player, "no-eligible-rewards");
                return false;
            }
            selected.add(reward.get());
        }

        List<ItemStack> items = selected.stream().flatMap(reward -> reward.itemCopies().stream()).toList();
        if (!plugin.settings().dropOverflow()
                && !InventoryPlanner.fits(player.getInventory().getStorageContents(), items)) {
            plugin.messages().send(player, "inventory-full");
            return false;
        }
        if (consumeKey && !plugin.keys().consume(player, crate.keyId(), amount)) {
            plugin.messages().send(player, "no-key", Text.component("key", keyName(crate)));
            return false;
        }

        if (animate) animating.add(player.getUniqueId());
        setCooldown(player, crate);
        boolean overflow = deliver(player, crate, selected);
        if (overflow) plugin.messages().send(player, "reward-overflow");

        if (animate) {
            try {
                plugin.menus().animate(player, crate, selected.getFirst(), () -> {
                    animating.remove(player.getUniqueId());
                    announceSingle(player, crate, selected.getFirst());
                });
            } catch (RuntimeException error) {
                animating.remove(player.getUniqueId());
                plugin.getLogger().log(Level.WARNING, "Could not show the crate animation; the selected reward was still delivered safely.", error);
                announceSingle(player, crate, selected.getFirst());
            }
        } else if (amount == 1) {
            announceSingle(player, crate, selected.getFirst());
        } else {
            plugin.messages().send(player, "bulk-opened", Text.value("amount", amount),
                    Text.component("crate", crate.displayName()), Text.value("rewards", selected.size()));
            announceBroadcasts(player, crate, selected);
        }
        return true;
    }

    public int bulkAmount(Player player, Crate crate) {
        if (player.hasPermission("plexoncrates.bypass.key")) return plugin.settings().maximumBulk();
        return Math.min(plugin.settings().maximumBulk(), plugin.keys().count(player, crate.keyId()));
    }

    public void clear() {
        animating.clear();
        cooldowns.clear();
    }

    private boolean deliver(Player player, Crate crate, List<CrateReward> selected) {
        boolean overflow = false;
        for (CrateReward reward : selected) {
            for (ItemStack item : reward.itemCopies()) {
                var leftovers = player.getInventory().addItem(item).values();
                if (!leftovers.isEmpty()) overflow = true;
                for (ItemStack leftover : leftovers) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            for (String command : reward.commands()) {
                String rendered = command
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString())
                        .replace("%crate%", crate.id())
                        .replace("%reward%", reward.id());
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered)) {
                        plugin.getLogger().warning("Reward command returned false: " + rendered);
                    }
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.SEVERE, "Reward command failed after a valid key was consumed: " + rendered, error);
                }
            }
            plugin.statistics().record(player.getUniqueId(), crate.id());
            log.record(player, crate, reward);
        }
        return overflow;
    }

    private void announceSingle(Player player, Crate crate, CrateReward reward) {
        plugin.messages().send(player, "opened", Text.component("crate", crate.displayName()),
                Text.component("reward", reward.displayName()));
        announceBroadcasts(player, crate, List.of(reward));
        player.playSound(player.getLocation(), plugin.settings().finishSound(),
                plugin.settings().soundVolume(), plugin.settings().soundPitch());
    }

    private void announceBroadcasts(Player player, Crate crate, List<CrateReward> rewards) {
        TagResolver[] crateTags = tags(player, crate, null);
        plugin.messages().broadcastRaw(crate.broadcast(), crateTags);
        for (CrateReward reward : rewards) {
            plugin.messages().broadcastRaw(reward.broadcast(), tags(player, crate, reward));
        }
    }

    private TagResolver[] tags(Player player, Crate crate, CrateReward reward) {
        return new TagResolver[]{Text.value("player", player.getName()), Text.value("uuid", player.getUniqueId()),
                Text.component("crate", crate.displayName()), Text.value("crate_id", crate.id()),
                Text.component("reward", reward == null ? Component.empty() : reward.displayName()),
                Text.value("reward_id", reward == null ? "" : reward.id())};
    }

    private Component keyName(Crate crate) {
        return plugin.keys().template(crate.keyId()).map(item -> {
            Component name = item.getItemMeta().displayName();
            return name == null ? Text.parse("<white>" + crate.keyId() + " key</white>") : name;
        }).orElseGet(() -> Text.parse("<white>" + crate.keyId() + " key</white>"));
    }

    private long cooldownRemaining(Player player, Crate crate) {
        long available = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(crate.id(), 0L);
        return Math.max(0, available - System.currentTimeMillis());
    }

    private void setCooldown(Player player, Crate crate) {
        if (crate.cooldownSeconds() <= 0) return;
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(crate.id(), System.currentTimeMillis() + crate.cooldownSeconds() * 1000L);
    }
}

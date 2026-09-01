package com.antondev.crates.service;

import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.PluginSettings;
import com.antondev.crates.integration.PlexonKeysBridge;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class KeyService {
    public record Snapshot(Map<String, ItemStack> templates) {
        public Snapshot {
            var copies = new LinkedHashMap<String, ItemStack>();
            templates.forEach((id, item) -> copies.put(id, ItemCodec.one(item)));
            templates = Collections.unmodifiableMap(copies);
        }
    }

    private final com.antondev.crates.PlexonCrates plugin;
    private PlexonKeysBridge bridge;
    private Map<String, ItemStack> fallback;

    public KeyService(com.antondev.crates.PlexonCrates plugin, Snapshot snapshot) {
        this.plugin = plugin;
        this.fallback = snapshot.templates();
        this.bridge = new PlexonKeysBridge(plugin, plugin.settings().plexonKeysPlugin());
    }

    public static Snapshot load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("config-version") != 1) throw new IllegalArgumentException("Unsupported keys.yml config-version");
        ConfigurationSection keys = yaml.getConfigurationSection("keys");
        if (keys == null || keys.getKeys(false).isEmpty()) throw new IllegalArgumentException("keys.yml contains no keys");
        var templates = new LinkedHashMap<String, ItemStack>();
        for (String rawId : keys.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!CrateRegistry.validId(id)) throw new IllegalArgumentException("Invalid key ID: " + rawId);
            templates.put(id, ItemCodec.read(keys.getConfigurationSection(rawId)));
        }
        return new Snapshot(templates);
    }

    public void apply(Snapshot snapshot) {
        fallback = snapshot.templates();
        bridge = new PlexonKeysBridge(plugin, plugin.settings().plexonKeysPlugin());
    }

    public Optional<ItemStack> template(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        PluginSettings settings = plugin.settings();
        if (settings.plexonKeysEnabled() && settings.plexonKeysMode().equals("LIVE_FIRST")) {
            Optional<ItemStack> live = bridge.template(normalized);
            if (live.isPresent()) return live.map(ItemCodec::one);
        }
        ItemStack item = fallback.get(normalized);
        return item == null ? Optional.empty() : Optional.of(item.clone());
    }

    public boolean matches(ItemStack candidate, String keyId) {
        if (candidate == null || candidate.getType().isAir()) return false;
        return template(keyId).map(template -> matches(candidate, template)).orElse(false);
    }

    public int count(Player player, String keyId) {
        ItemStack template = template(keyId).orElse(null);
        if (template == null) return 0;
        return count(player, template);
    }

    private int count(Player player, ItemStack template) {
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, template)) amount += item.getAmount();
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (matches(offhand, template)) amount += offhand.getAmount();
        return amount;
    }

    public boolean consume(Player player, String keyId, int requested) {
        ItemStack template = template(keyId).orElse(null);
        if (template == null || requested < 1 || count(player, template) < requested) return false;
        int remaining = requested;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length && remaining > 0; slot++) {
            ItemStack item = storage[slot];
            if (!matches(item, template)) continue;
            int removed = Math.min(item.getAmount(), remaining);
            remaining -= removed;
            if (removed == item.getAmount()) storage[slot] = null;
            else item.setAmount(item.getAmount() - removed);
        }
        player.getInventory().setStorageContents(storage);
        if (remaining > 0) {
            ItemStack item = player.getInventory().getItemInOffHand();
            int removed = Math.min(item.getAmount(), remaining);
            remaining -= removed;
            if (removed == item.getAmount()) player.getInventory().setItemInOffHand(null);
            else item.setAmount(item.getAmount() - removed);
        }
        return remaining == 0;
    }

    private static boolean matches(ItemStack candidate, ItemStack template) {
        return candidate != null && !candidate.getType().isAir() && ItemCodec.one(candidate).isSimilar(template);
    }

    public void give(Player player, String keyId, int amount) {
        ItemStack template = template(keyId).orElseThrow(() -> new IllegalArgumentException("Unknown key ID"));
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(stack.getMaxStackSize(), remaining));
            remaining -= stack.getAmount();
            player.getInventory().addItem(stack).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public String sourceLabel() {
        if (plugin.settings().plexonKeysEnabled()
                && plugin.settings().plexonKeysMode().equals("LIVE_FIRST")
                && bridge.available()) return "PlexonKeys live templates";
        return "keys.yml fallback";
    }
}

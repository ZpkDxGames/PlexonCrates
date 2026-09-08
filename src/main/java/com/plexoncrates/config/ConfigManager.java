package com.plexoncrates.config;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.util.ColorUtil;
import java.io.File;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

public final class ConfigManager {
    private final PlexonCrates plugin;

    public ConfigManager(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public void initialize() {
        plugin.saveDefaultConfig();
        File crates = new File(plugin.getDataFolder(), "crates.yml");
        if (!crates.exists()) plugin.saveResource("crates.yml", false);
    }

    public void reload() {
        plugin.reloadConfig();
    }

    public FileConfiguration config() {
        return plugin.getConfig();
    }

    public String prefix() {
        return ColorUtil.color(config().getString("plugin.prefix", "&8[&6PlexonCrates&8] &r"));
    }

    public String message(String key) {
        return prefix() + ColorUtil.color(config().getString("messages." + key, "&cMissing message: " + key));
    }

    public String message(String key, String... replacements) {
        String value = message(key);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("%" + replacements[index] + "%", replacements[index + 1]);
        }
        return value;
    }

    public int databasePoolSize() {
        return Math.max(2, config().getInt("database.pool-size", 2));
    }

    public String databaseFile() {
        return config().getString("database.file", "data/plexoncrates.db");
    }

    public int busyTimeoutMillis() {
        return Math.max(1000, config().getInt("database.busy-timeout-ms", 5000));
    }

    public boolean virtualKeysEnabled() {
        return config().getBoolean("keys.virtual-enabled", true);
    }

    public boolean physicalKeysEnabled() {
        return config().getBoolean("keys.physical-enabled", true);
    }

    public boolean requireHeldPhysicalKey() {
        return config().getBoolean("keys.physical-open-requires-held-key", true);
    }

    public int openingDurationTicks() {
        return Math.max(20, config().getInt("opening.duration-ticks", 100));
    }

    public boolean lockOpeningInventory() {
        return config().getBoolean("opening.lock-inventory-during-animation", true);
    }

    public boolean claimsEnabled() {
        return config().getBoolean("claims.enabled", true);
    }

    public int claimPageSize() {
        return Math.max(9, Math.min(45, config().getInt("claims.page-size", 45)));
    }

    public boolean effectsEnabled() {
        return config().getBoolean("effects.enabled", true);
    }

    public int idleIntervalTicks() {
        return Math.max(2, config().getInt("effects.idle-interval-ticks", 5));
    }

    public int maxBlocksPerIdlePass() {
        return Math.max(1, config().getInt("effects.max-blocks-per-pass", 64));
    }

    public boolean particlesEnabled() {
        return config().getBoolean("effects.particles-enabled", true);
    }

    public boolean soundsEnabled() {
        return config().getBoolean("effects.sounds-enabled", true);
    }
}

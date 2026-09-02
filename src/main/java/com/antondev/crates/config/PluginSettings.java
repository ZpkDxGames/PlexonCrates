package com.antondev.crates.config;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.antondev.crates.domain.crate.AnimationType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

public record PluginSettings(
        String databaseFile,
        int maximumQueuedWrites,
        boolean enabled,
        Set<String> worlds,
        Set<String> excludedWorlds,
        boolean dropOverflow,
        int maximumBulk,
        int statisticsSaveSeconds,
        boolean plexonKeysEnabled,
        String plexonKeysPlugin,
        String plexonKeysMode,
        String fallbackFile,
        boolean consumeOffhandKeys,
        boolean leftPreview,
        boolean rightOpen,
        boolean sneakBulk,
        boolean cancelVanillaUse,
        int targetDistance,
        boolean animationEnabled,
        AnimationType defaultAnimation,
        int bulkSummaryThreshold,
        String recoveryPolicy,
        int animationDuration,
        int animationPeriod,
        String openingSound,
        String finishSound,
        float soundVolume,
        float soundPitch,
        boolean hologramsEnabled,
        double hologramOffset,
        double hologramViewRange,
        int hologramLineWidth,
        boolean hologramShadowed,
        boolean hologramSeeThrough,
        boolean particlesEnabled,
        Particle particle,
        int particleInterval,
        int particleCount,
        double particleHorizontalSpread,
        double particleVerticalSpread,
        double particleViewRange,
        int inputTimeoutSeconds,
        int sessionTimeoutMinutes,
        Set<Material> deniedLocationMaterials,
        Set<String> allowedLocationWorlds,
        boolean placeholderApiEnabled,
        boolean vaultEnabled,
        boolean consoleLogging,
        boolean fileLogging,
        String logDateFormat) {

    public static PluginSettings load(File file) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        if (c.getInt("config-version") != 2) throw new IllegalArgumentException("Unsupported config.yml config-version; expected 2");
        String databaseFile = required(c, "database.file");
        if (!databaseFile.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.db") || databaseFile.contains("..")) {
            throw new IllegalArgumentException("database.file must be a safe relative data/*.db path");
        }
        int maximumQueuedWrites = integer(c, "database.maximum-queued-writes", 64, 100_000);
        int bulk = integer(c, "settings.maximum-bulk-open", 1, 10_000);
        int save = integer(c, "settings.statistics-save-seconds", 30, 86_400);
        int target = integer(c, "interaction.maximum-target-distance", 1, 64);
        int duration = integer(c, "opening.animation-duration-ticks", 10, 1_200);
        int period = integer(c, "opening.animation-period-ticks", 1, 20);
        int particleInterval = integer(c, "particles.interval-ticks", 1, 1_200);
        int particleCount = integer(c, "particles.count", 0, 1_000);
        int lineWidth = integer(c, "holograms.line-width", 20, 2_000);
        float volume = (float) number(c, "opening.sound-volume", 0, 10);
        float pitch = (float) number(c, "opening.sound-pitch", 0, 2);
        String mode = c.getString("plexonkeys.mode", "LIVE_FIRST").toUpperCase(Locale.ROOT);
        if (!Set.of("LIVE_FIRST", "FALLBACK_ONLY").contains(mode)) {
            throw new IllegalArgumentException("plexonkeys.mode must be LIVE_FIRST or FALLBACK_ONLY");
        }
        Particle particle;
        try {
            particle = Particle.valueOf(c.getString("particles.type", "END_ROD").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown particles.type", error);
        }
        String fallback = required(c, "plexonkeys.fallback-file");
        if (!fallback.matches("[A-Za-z0-9._-]+\\.yml")) {
            throw new IllegalArgumentException("plexonkeys.fallback-file must be a simple .yml filename");
        }
        String openingSound = sound(c, "opening.opening-sound");
        String finishSound = sound(c, "opening.finish-sound");
        String dateFormat = required(c, "logging.date-format");
        try { DateTimeFormatter.ofPattern(dateFormat); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("logging.date-format is invalid", error); }
        AnimationType defaultAnimation;
        try { defaultAnimation = AnimationType.valueOf(c.getString("opening.default-animation", "ROULETTE").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("opening.default-animation is invalid", error); }
        String recoveryPolicy = c.getString("opening.recovery-policy", "MANUAL_REVIEW").toUpperCase(Locale.ROOT);
        if (!recoveryPolicy.equals("MANUAL_REVIEW")) {
            throw new IllegalArgumentException("opening.recovery-policy currently supports only MANUAL_REVIEW; arbitrary rewards are never replayed automatically");
        }
        Set<Material> deniedMaterials = c.getStringList("locations.denied-materials").stream().map(value -> {
            Material material = Material.matchMaterial(value);
            if (material == null) throw new IllegalArgumentException("Unknown locations.denied-materials entry: " + value);
            return material;
        }).collect(Collectors.toUnmodifiableSet());
        return new PluginSettings(
                databaseFile, maximumQueuedWrites,
                c.getBoolean("settings.enabled"), lower(c.getStringList("settings.worlds")),
                lower(c.getStringList("settings.excluded-worlds")), c.getBoolean("settings.drop-overflow-items"),
                bulk, save, c.getBoolean("plexonkeys.enabled"), required(c, "plexonkeys.plugin-name"), mode,
                fallback, c.getBoolean("interaction.consume-offhand-keys"), c.getBoolean("interaction.left-click-preview"),
                c.getBoolean("interaction.right-click-open"), c.getBoolean("interaction.sneak-right-click-bulk"),
                c.getBoolean("interaction.cancel-vanilla-block-use"), target, c.getBoolean("opening.animation-enabled"),
                defaultAnimation, integer(c, "opening.bulk-summary-threshold", 1, 64), recoveryPolicy,
                duration, period, openingSound, finishSound, volume, pitch,
                c.getBoolean("holograms.enabled"), number(c, "holograms.vertical-offset", -10, 10),
                number(c, "holograms.view-range", 1, 256), lineWidth, c.getBoolean("holograms.shadowed"),
                c.getBoolean("holograms.see-through"), c.getBoolean("particles.enabled"), particle, particleInterval,
                particleCount, number(c, "particles.horizontal-spread", 0, 10),
                number(c, "particles.vertical-spread", 0, 10), number(c, "particles.view-range", 1, 256),
                integer(c, "editing.input-timeout-seconds", 10, 300),
                integer(c, "editing.session-timeout-minutes", 1, 240), deniedMaterials,
                lower(c.getStringList("locations.allowed-worlds")), c.getBoolean("integrations.placeholderapi"),
                c.getBoolean("integrations.vault"),
                c.getBoolean("logging.console"), c.getBoolean("logging.file"), dateFormat);
    }

    public boolean allows(World world) {
        String name = world.getName().toLowerCase(Locale.ROOT);
        return (worlds.isEmpty() || worlds.contains(name)) && !excludedWorlds.contains(name);
    }

    private static String required(YamlConfiguration c, String path) {
        String value = c.getString(path, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(path + " cannot be empty");
        return value;
    }

    private static String sound(YamlConfiguration c, String path) {
        String value = required(c, path).toLowerCase(Locale.ROOT);
        if (NamespacedKey.fromString(value) == null) throw new IllegalArgumentException(path + " must be a valid namespaced sound");
        return value;
    }

    private static int integer(YamlConfiguration c, String path, int min, int max) {
        double value = number(c, path, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(path + " must be a whole number");
        return (int) value;
    }

    private static double number(YamlConfiguration c, String path, double min, double max) {
        Object raw = c.get(path);
        if (!(raw instanceof Number number)) throw new IllegalArgumentException(path + " must be numeric");
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static Set<String> lower(java.util.List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}

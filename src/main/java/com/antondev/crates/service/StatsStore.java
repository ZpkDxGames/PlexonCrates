package com.antondev.crates.service;

import com.antondev.crates.config.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class StatsStore {
    private final Path file;
    private final Map<String, Long> global = new HashMap<>();
    private final Map<UUID, Map<String, Long>> players = new HashMap<>();
    private boolean dirty;

    public StatsStore(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("statistics.yml contains invalid YAML", error);
        }
        if (yaml.getInt("config-version", 1) != 1) throw new IllegalArgumentException("Unsupported statistics.yml config-version");
        ConfigurationSection globalSection = yaml.getConfigurationSection("global");
        if (globalSection != null) {
            for (String id : globalSection.getKeys(false)) global.put(id, nonNegative(globalSection.getLong(id)));
        }
        ConfigurationSection playerSection = yaml.getConfigurationSection("players");
        if (playerSection != null) {
            for (String rawUuid : playerSection.getKeys(false)) {
                UUID uuid;
                try { uuid = UUID.fromString(rawUuid); }
                catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid statistics UUID: " + rawUuid); }
                ConfigurationSection values = playerSection.getConfigurationSection(rawUuid);
                var counts = new HashMap<String, Long>();
                if (values != null) for (String id : values.getKeys(false)) counts.put(id, nonNegative(values.getLong(id)));
                players.put(uuid, counts);
            }
        }
    }

    public void record(UUID player, String crateId) {
        global.merge(crateId, 1L, Long::sum);
        players.computeIfAbsent(player, ignored -> new HashMap<>()).merge(crateId, 1L, Long::sum);
        dirty = true;
    }

    public long global(String crateId) {
        return global.getOrDefault(crateId, 0L);
    }

    public long player(UUID player, String crateId) {
        return players.getOrDefault(player, Map.of()).getOrDefault(crateId, 0L);
    }

    public boolean save() throws IOException {
        if (!dirty && Files.exists(file)) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 1);
        global.forEach((id, value) -> yaml.set("global." + id, value));
        players.forEach((uuid, counts) -> counts.forEach((id, value) -> yaml.set("players." + uuid + "." + id, value)));
        AtomicFiles.write(file, yaml.saveToString());
        dirty = false;
        return true;
    }

    private static long nonNegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Statistics cannot be negative");
        return value;
    }
}

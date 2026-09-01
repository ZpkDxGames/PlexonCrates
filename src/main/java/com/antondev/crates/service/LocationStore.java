package com.antondev.crates.service;

import com.antondev.crates.config.AtomicFiles;
import com.antondev.crates.model.BlockPosition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LocationStore {
    public record Link(BlockPosition position, String crateId) {}
    public record Snapshot(Map<String, Link> links) {}

    private final Path file;
    private Map<String, Link> links;

    public LocationStore(Path file, Snapshot snapshot) {
        this.file = file;
        apply(snapshot);
    }

    public static void createIfMissing(Path file) throws IOException {
        if (!Files.exists(file)) AtomicFiles.write(file, "config-version: 1\nlocations: []\n");
    }

    public static Snapshot load(Path file, CrateRegistry crates) throws IOException {
        createIfMissing(file);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("locations.yml contains invalid YAML", error);
        }
        if (yaml.getInt("config-version") != 1) throw new IllegalArgumentException("Unsupported locations.yml config-version");
        var links = new LinkedHashMap<String, Link>();
        for (Map<?, ?> raw : yaml.getMapList("locations")) {
            Object worldRaw = raw.get("world");
            Object crateRaw = raw.get("crate");
            Object xRaw = raw.get("x");
            Object yRaw = raw.get("y");
            Object zRaw = raw.get("z");
            if (!(worldRaw instanceof String world) || world.isBlank()
                    || !(crateRaw instanceof String crateId)
                    || !(xRaw instanceof Number x) || !(yRaw instanceof Number y) || !(zRaw instanceof Number z)) {
                throw new IllegalArgumentException("Every locations.yml entry needs world, crate, x, y, and z");
            }
            if (crates.find(crateId).isEmpty()) throw new IllegalArgumentException("Location references unknown crate: " + crateId);
            BlockPosition position = new BlockPosition(world, exact(x, "x"), exact(y, "y"), exact(z, "z"));
            Link link = new Link(position, crateId.toLowerCase(java.util.Locale.ROOT));
            if (links.putIfAbsent(position.key(), link) != null) throw new IllegalArgumentException("Duplicate crate location: " + position.key());
        }
        return new Snapshot(Map.copyOf(links));
    }

    public void apply(Snapshot snapshot) {
        links = snapshot.links();
    }

    public Optional<Link> at(Block block) {
        return Optional.ofNullable(links.get(BlockPosition.of(block).key()));
    }

    public Collection<Link> all() {
        return links.values();
    }

    public long count(String crateId) {
        return links.values().stream().filter(link -> link.crateId().equalsIgnoreCase(crateId)).count();
    }

    public void set(Block block, String crateId) throws IOException {
        var next = new LinkedHashMap<>(links);
        BlockPosition position = BlockPosition.of(block);
        next.put(position.key(), new Link(position, crateId.toLowerCase(java.util.Locale.ROOT)));
        save(next);
    }

    public boolean remove(Block block) throws IOException {
        var next = new LinkedHashMap<>(links);
        boolean existed = next.remove(BlockPosition.of(block).key()) != null;
        if (existed) save(next);
        return existed;
    }

    private void save(Map<String, Link> next) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 1);
        List<Map<String, Object>> values = next.values().stream().map(link -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("world", link.position().world());
            value.put("x", link.position().x());
            value.put("y", link.position().y());
            value.put("z", link.position().z());
            value.put("crate", link.crateId());
            return (Map<String, Object>) value;
        }).toList();
        yaml.set("locations", values);
        AtomicFiles.write(file, yaml.saveToString());
        links = Map.copyOf(next);
    }

    private static int exact(Number number, String field) {
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < -30_000_000 || value > 30_000_000) {
            throw new IllegalArgumentException("Invalid location " + field);
        }
        return (int) value;
    }
}

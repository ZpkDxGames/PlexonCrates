package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.model.BlockPosition;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.block.Block;

/** Main-thread location index backed asynchronously by SQLite. */
public final class LocationStore {
    public record Link(BlockPosition position, String crateId, Instant updatedAt) {}
    public record Snapshot(Map<String, Link> links) {
        public Snapshot { links = Map.copyOf(links); }
    }

    private final DatabaseService database;
    private final Logger logger;
    private Map<String, Link> links;

    public LocationStore(DatabaseService database, Logger logger, Snapshot snapshot) {
        this.database = database;
        this.logger = logger;
        apply(snapshot);
    }

    public static Snapshot fromDatabase(Collection<DatabaseService.StoredLocation> stored, CrateRegistry crates) {
        var links = new LinkedHashMap<String, Link>();
        for (DatabaseService.StoredLocation value : stored) {
            if (crates.find(value.crateId()).isEmpty()) {
                throw new IllegalArgumentException("Database location references unknown crate: " + value.crateId());
            }
            BlockPosition position = new BlockPosition(value.worldUuid(), value.worldName(),
                    value.x(), value.y(), value.z());
            Link link = new Link(position, value.crateId().toLowerCase(Locale.ROOT), value.updatedAt());
            if (links.putIfAbsent(position.key(), link) != null) {
                throw new IllegalArgumentException("Duplicate database crate location: " + position.key());
            }
        }
        return new Snapshot(links);
    }

    public void apply(Snapshot snapshot) {
        links = snapshot.links();
    }

    public Optional<Link> at(Block block) {
        return Optional.ofNullable(links.get(BlockPosition.of(block).key()));
    }

    public Optional<Link> at(BlockPosition position) {
        return Optional.ofNullable(links.get(position.key()));
    }

    public Collection<Link> all() {
        return links.values();
    }

    public long count(String crateId) {
        return links.values().stream().filter(link -> link.crateId().equalsIgnoreCase(crateId)).count();
    }

    public void set(Block block, String crateId) {
        BlockPosition position = BlockPosition.of(block);
        Link existing = links.get(position.key());
        if (existing != null && !existing.crateId().equalsIgnoreCase(crateId)) {
            throw new IllegalStateException("This block is already linked to crate " + existing.crateId());
        }
        Instant now = Instant.now();
        Link link = new Link(position, crateId.toLowerCase(Locale.ROOT), now);
        var next = new LinkedHashMap<>(links);
        next.put(position.key(), link);
        links = Map.copyOf(next);
        database.saveLocation(toStored(link)).whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.SEVERE, "Could not persist crate location " + position.key(), error);
        });
    }

    public boolean remove(Block block) {
        return remove(BlockPosition.of(block));
    }

    public boolean remove(BlockPosition position) {
        Link removed = links.get(position.key());
        if (removed == null) return false;
        var next = new LinkedHashMap<>(links);
        next.remove(position.key());
        links = Map.copyOf(next);
        database.removeLocation(toStored(removed)).whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.SEVERE, "Could not remove crate location " + position.key(), error);
        });
        return true;
    }

    private static DatabaseService.StoredLocation toStored(Link link) {
        BlockPosition position = link.position();
        return new DatabaseService.StoredLocation(position.worldUuid(), position.worldName(), position.x(),
                position.y(), position.z(), link.crateId(), link.updatedAt());
    }
}

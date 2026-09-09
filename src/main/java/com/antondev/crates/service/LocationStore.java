package com.antondev.crates.service;

import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.model.BlockPosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Main-thread linked-location store backed asynchronously by SQLite.
 *
 * <p>The canonical location map is accompanied by in-memory exact-block, chunk, and crate indexes so
 * runtime display/particle work scales with relevant loaded/nearby chunks rather than every stored link.
 */
public final class LocationStore {
    public record Link(BlockPosition position, String crateId, Instant updatedAt) {}
    public record Snapshot(Map<String, Link> links) {
        public Snapshot { links = Map.copyOf(links); }
    }

    private final DatabaseService database;
    private final Logger logger;
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final Map<String, Link> exactIndex = new HashMap<>();
    private final Map<String, Map<Long, List<Link>>> chunkIndex = new HashMap<>();
    private final Map<String, List<Link>> crateIndex = new HashMap<>();

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

    /** Rebuilds every runtime index from one validated snapshot. Main-thread only. */
    public void apply(Snapshot snapshot) {
        links.clear();
        exactIndex.clear();
        chunkIndex.clear();
        crateIndex.clear();
        for (Link link : snapshot.links().values()) {
            links.put(link.position().key(), link);
            index(link);
        }
    }

    public Optional<Link> at(Block block) {
        return Optional.ofNullable(exactIndex.get(blockKey(block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ())));
    }

    public Optional<Link> at(BlockPosition position) {
        Link direct = exactIndex.get(position.key());
        if (direct != null) return Optional.of(direct);
        return Optional.ofNullable(exactIndex.get(nameBlockKey(position.worldName(), position.x(), position.y(), position.z())));
    }

    /** Exact lookup by the canonical position key already exposed by Link.position().key(). */
    public Optional<Link> byPositionKey(String positionKey) {
        return Optional.ofNullable(links.get(positionKey));
    }

    public Collection<Link> all() {
        return Collections.unmodifiableCollection(links.values());
    }

    public int size() {
        return links.size();
    }

    public long count(String crateId) {
        List<Link> indexed = crateIndex.get(normalizeCrate(crateId));
        return indexed == null ? 0L : indexed.size();
    }

    public Collection<Link> forCrate(String crateId) {
        List<Link> indexed = crateIndex.get(normalizeCrate(crateId));
        return indexed == null ? List.of() : Collections.unmodifiableList(indexed);
    }

    /** Returns only links indexed to one chunk. No global location scan is performed. */
    public Collection<Link> inChunk(World world, int chunkX, int chunkZ) {
        return inChunk(world.getUID(), world.getName(), chunkX, chunkZ);
    }

    /** UUID/name overload used by tests and migration-safe callers without forcing a Bukkit world lookup. */
    public Collection<Link> inChunk(UUID worldUuid, String worldName, int chunkX, int chunkZ) {
        Map<Long, List<Link>> worldChunks = worldChunks(worldUuid, worldName);
        if (worldChunks == null) return List.of();
        List<Link> indexed = worldChunks.get(packChunk(chunkX, chunkZ));
        return indexed == null ? List.of() : Collections.unmodifiableList(indexed);
    }

    /**
     * Returns links from chunks surrounding a center chunk. Distance filtering remains the caller's job;
     * this method exists to cheaply produce a small candidate set for display/particle work.
     */
    public Collection<Link> nearbyChunks(World world, int centerChunkX, int centerChunkZ, int chunkRadius) {
        return nearbyChunks(world.getUID(), world.getName(), centerChunkX, centerChunkZ, chunkRadius);
    }

    public Collection<Link> nearbyChunks(UUID worldUuid, String worldName, int centerChunkX, int centerChunkZ,
            int chunkRadius) {
        if (chunkRadius < 0) throw new IllegalArgumentException("chunkRadius cannot be negative");
        Map<Long, List<Link>> worldChunks = worldChunks(worldUuid, worldName);
        if (worldChunks == null || worldChunks.isEmpty()) return List.of();
        List<Link> result = new ArrayList<>();
        for (int x = centerChunkX - chunkRadius; x <= centerChunkX + chunkRadius; x++) {
            for (int z = centerChunkZ - chunkRadius; z <= centerChunkZ + chunkRadius; z++) {
                List<Link> bucket = worldChunks.get(packChunk(x, z));
                if (bucket != null) result.addAll(bucket);
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    public void set(Block block, String crateId) {
        BlockPosition position = BlockPosition.of(block);
        Link existing = at(position).orElse(null);
        if (existing != null && !existing.crateId().equalsIgnoreCase(crateId)) {
            throw new IllegalStateException("This block is already linked to crate " + existing.crateId());
        }
        Instant now = Instant.now();
        Link link = new Link(position, normalizeCrate(crateId), now);
        if (existing != null) {
            links.remove(existing.position().key());
            deindex(existing);
        }
        links.put(position.key(), link);
        index(link);
        database.saveLocation(toStored(link)).whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.SEVERE, "Could not persist crate location " + position.key(), error);
        });
    }

    public boolean remove(Block block) {
        return remove(BlockPosition.of(block));
    }

    public boolean remove(BlockPosition position) {
        Link removed = at(position).orElse(null);
        if (removed == null) return false;
        links.remove(removed.position().key());
        deindex(removed);
        database.removeLocation(toStored(removed)).whenComplete((ignored, error) -> {
            if (error != null) logger.log(Level.SEVERE, "Could not remove crate location " + position.key(), error);
        });
        return true;
    }

    private void index(Link link) {
        BlockPosition position = link.position();
        exactIndex.put(position.key(), link);
        exactIndex.put(nameBlockKey(position.worldName(), position.x(), position.y(), position.z()), link);
        if (position.worldUuid() != null) {
            exactIndex.put(uuidBlockKey(position.worldUuid(), position.x(), position.y(), position.z()), link);
        }
        long chunk = packChunk(position.x() >> 4, position.z() >> 4);
        for (String worldKey : worldKeys(position.worldUuid(), position.worldName())) {
            chunkIndex.computeIfAbsent(worldKey, ignored -> new HashMap<>())
                    .computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(link);
        }
        crateIndex.computeIfAbsent(normalizeCrate(link.crateId()), ignored -> new ArrayList<>()).add(link);
    }

    private void deindex(Link link) {
        BlockPosition position = link.position();
        exactIndex.remove(position.key(), link);
        exactIndex.remove(nameBlockKey(position.worldName(), position.x(), position.y(), position.z()), link);
        if (position.worldUuid() != null) {
            exactIndex.remove(uuidBlockKey(position.worldUuid(), position.x(), position.y(), position.z()), link);
        }
        long chunk = packChunk(position.x() >> 4, position.z() >> 4);
        for (String worldKey : worldKeys(position.worldUuid(), position.worldName())) {
            Map<Long, List<Link>> worldChunks = chunkIndex.get(worldKey);
            if (worldChunks == null) continue;
            List<Link> bucket = worldChunks.get(chunk);
            if (bucket != null) {
                bucket.remove(link);
                if (bucket.isEmpty()) worldChunks.remove(chunk);
            }
            if (worldChunks.isEmpty()) chunkIndex.remove(worldKey);
        }
        String crateId = normalizeCrate(link.crateId());
        List<Link> crateLinks = crateIndex.get(crateId);
        if (crateLinks != null) {
            crateLinks.remove(link);
            if (crateLinks.isEmpty()) crateIndex.remove(crateId);
        }
    }

    private Map<Long, List<Link>> worldChunks(UUID worldUuid, String worldName) {
        if (worldUuid != null) {
            Map<Long, List<Link>> byUuid = chunkIndex.get(uuidWorldKey(worldUuid));
            if (byUuid != null) return byUuid;
        }
        return chunkIndex.get(nameWorldKey(worldName));
    }

    private static Set<String> worldKeys(UUID worldUuid, String worldName) {
        Set<String> keys = new LinkedHashSet<>(2);
        if (worldUuid != null) keys.add(uuidWorldKey(worldUuid));
        keys.add(nameWorldKey(worldName));
        return keys;
    }

    private static String blockKey(UUID worldUuid, String worldName, int x, int y, int z) {
        return worldUuid == null ? nameBlockKey(worldName, x, y, z) : uuidBlockKey(worldUuid, x, y, z);
    }

    private static String uuidBlockKey(UUID worldUuid, int x, int y, int z) {
        return worldUuid + ":" + x + ":" + y + ":" + z;
    }

    private static String nameBlockKey(String worldName, int x, int y, int z) {
        return worldName.toLowerCase(Locale.ROOT) + ":" + x + ":" + y + ":" + z;
    }

    private static String uuidWorldKey(UUID worldUuid) {
        return "uuid:" + worldUuid;
    }

    private static String nameWorldKey(String worldName) {
        return "name:" + worldName.toLowerCase(Locale.ROOT);
    }

    static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static String normalizeCrate(String crateId) {
        return crateId.toLowerCase(Locale.ROOT);
    }

    private static DatabaseService.StoredLocation toStored(Link link) {
        BlockPosition position = link.position();
        return new DatabaseService.StoredLocation(position.worldUuid(), position.worldName(), position.x(),
                position.y(), position.z(), link.crateId(), link.updatedAt());
    }
}

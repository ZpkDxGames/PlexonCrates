package com.antondev.crates.model;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockPosition(UUID worldUuid, String worldName, int x, int y, int z) {
    public BlockPosition {
        if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("World name cannot be empty");
    }

    public BlockPosition(String worldName, int x, int y, int z) {
        this(null, worldName, x, y, z);
    }

    public static BlockPosition of(Block block) {
        return new BlockPosition(block.getWorld().getUID(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** Compatibility alias retained for the 1.0 public model. */
    public String world() { return worldName; }

    public String key() {
        String worldIdentity = worldUuid == null ? worldName.toLowerCase(Locale.ROOT) : worldUuid.toString();
        return worldIdentity + ":" + x + ":" + y + ":" + z;
    }

    public World loadedWorld() {
        World byUuid = worldUuid == null ? null : Bukkit.getWorld(worldUuid);
        return byUuid != null ? byUuid : Bukkit.getWorld(worldName);
    }

    public Block loadedBlock() {
        World loaded = loadedWorld();
        return loaded == null ? null : loaded.getBlockAt(x, y, z);
    }

    public Location center(double verticalOffset) {
        World loaded = loadedWorld();
        return loaded == null ? null : new Location(loaded, x + 0.5, y + verticalOffset, z + 0.5);
    }

    public boolean inChunk(World candidate, int chunkX, int chunkZ) {
        boolean sameWorld = worldUuid != null ? candidate.getUID().equals(worldUuid)
                : candidate.getName().equalsIgnoreCase(worldName);
        return sameWorld && (x >> 4) == chunkX && (z >> 4) == chunkZ;
    }
}

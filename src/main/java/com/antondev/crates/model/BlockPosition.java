package com.antondev.crates.model;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record BlockPosition(String world, int x, int y, int z) {
    public static BlockPosition of(Block block) {
        return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public String key() {
        return world.toLowerCase(Locale.ROOT) + ":" + x + ":" + y + ":" + z;
    }

    public World loadedWorld() {
        return Bukkit.getWorld(world);
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
        return candidate.getName().equalsIgnoreCase(world) && (x >> 4) == chunkX && (z >> 4) == chunkZ;
    }
}

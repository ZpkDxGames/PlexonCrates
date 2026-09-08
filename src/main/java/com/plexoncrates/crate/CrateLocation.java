package com.plexoncrates.crate;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record CrateLocation(UUID worldId, String worldName, int x, int y, int z) {
    public CrateLocation {
        Objects.requireNonNull(worldName, "worldName");
    }

    public static CrateLocation of(Block block) {
        World world = block.getWorld();
        return new CrateLocation(world.getUID(), world.getName(), block.getX(), block.getY(), block.getZ());
    }

    public String key() {
        return worldName.toLowerCase(java.util.Locale.ROOT) + ":" + x + ":" + y + ":" + z;
    }

    public Location center() {
        World world = worldId == null ? Bukkit.getWorld(worldName) : Bukkit.getWorld(worldId);
        if (world == null) return null;
        return new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
    }
}

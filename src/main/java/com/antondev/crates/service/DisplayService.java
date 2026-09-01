package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

public final class DisplayService {
    private final PlexonCrates plugin;
    private final NamespacedKey marker;
    private final Map<String, UUID> holograms = new HashMap<>();
    private BukkitTask particles;

    public DisplayService(PlexonCrates plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "crate_hologram");
    }

    public void refresh() {
        stop();
        if (plugin.settings().hologramsEnabled()) {
            for (LocationStore.Link link : plugin.locations().all()) spawn(link);
        }
        if (plugin.settings().particlesEnabled() && plugin.settings().particleCount() > 0) {
            particles = plugin.getServer().getScheduler().runTaskTimer(plugin, this::particles,
                    plugin.settings().particleInterval(), plugin.settings().particleInterval());
        }
    }

    public void stop() {
        if (particles != null) {
            particles.cancel();
            particles = null;
        }
        for (UUID uuid : holograms.values()) {
            var entity = plugin.getServer().getEntity(uuid);
            if (entity != null) entity.remove();
        }
        holograms.clear();
    }

    public void chunkLoaded(Chunk chunk) {
        if (!plugin.settings().hologramsEnabled()) return;
        for (LocationStore.Link link : plugin.locations().all()) {
            if (link.position().inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) spawn(link);
        }
    }

    public void chunkUnloaded(Chunk chunk) {
        for (LocationStore.Link link : plugin.locations().all()) {
            if (link.position().inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) remove(link.position());
        }
    }

    private void spawn(LocationStore.Link link) {
        BlockPosition position = link.position();
        World world = position.loadedWorld();
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return;
        Crate crate = plugin.crates().find(link.crateId()).orElse(null);
        if (crate == null || !crate.enabled() || holograms.containsKey(position.key())) return;
        Location location = position.center(plugin.settings().hologramOffset());
        if (location == null) return;
        Component content = Component.empty();
        for (int index = 0; index < crate.hologramLines().size(); index++) {
            if (index > 0) content = content.append(Component.newline());
            content = content.append(crate.hologramLines().get(index));
        }
        Component finalContent = content;
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(finalContent);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(plugin.settings().hologramLineWidth());
            entity.setShadowed(plugin.settings().hologramShadowed());
            entity.setSeeThrough(plugin.settings().hologramSeeThrough());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.getPersistentDataContainer().set(marker, PersistentDataType.STRING, position.key());
        });
        holograms.put(position.key(), display.getUniqueId());
    }

    private void remove(BlockPosition position) {
        UUID uuid = holograms.remove(position.key());
        if (uuid == null) return;
        var entity = plugin.getServer().getEntity(uuid);
        if (entity != null) entity.remove();
    }

    private void particles() {
        double range = plugin.settings().particleViewRange();
        double rangeSquared = range * range;
        for (LocationStore.Link link : plugin.locations().all()) {
            BlockPosition position = link.position();
            World world = position.loadedWorld();
            if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
            Crate crate = plugin.crates().find(link.crateId()).orElse(null);
            if (crate == null || !crate.enabled()) continue;
            Location center = position.center(1.12);
            if (center == null || !hasNearbyPlayer(world, center, rangeSquared)) continue;
            world.spawnParticle(plugin.settings().particle(), center, plugin.settings().particleCount(),
                    plugin.settings().particleHorizontalSpread(), plugin.settings().particleVerticalSpread(),
                    plugin.settings().particleHorizontalSpread(), 0.01);
        }
    }

    private static boolean hasNearbyPlayer(World world, Location location, double rangeSquared) {
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= rangeSquared) return true;
        }
        return false;
    }
}

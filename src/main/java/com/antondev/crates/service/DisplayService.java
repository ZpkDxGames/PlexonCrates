package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

/** Runtime-only hologram and particle presentation. Reward correctness never depends on this service. */
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
            // Reconcile only currently loaded chunks. Stored links in unloaded chunks remain index-only
            // and are materialized naturally by chunkLoaded().
            for (World world : plugin.getServer().getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    for (LocationStore.Link link : plugin.locations().inChunk(world, chunk.getX(), chunk.getZ())) {
                        spawn(link);
                    }
                }
            }
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
        for (LocationStore.Link link : plugin.locations().inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            spawn(link);
        }
    }

    public void chunkUnloaded(Chunk chunk) {
        for (LocationStore.Link link : plugin.locations().inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ())) {
            remove(link.position());
        }
    }

    public int activeHolograms() {
        return holograms.size();
    }

    private void spawn(LocationStore.Link link) {
        BlockPosition position = link.position();
        World world = position.loadedWorld();
        if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) return;
        Crate crate = plugin.runtime().find(link.crateId()).orElse(null);
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
            // Display entities store this as a 64-block multiplier; config stays in blocks.
            entity.setViewRange((float) Math.max(0.1, plugin.settings().hologramViewRange() / 64.0));
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
        if (plugin.getServer().getOnlinePlayers().isEmpty() || plugin.locations().size() == 0) return;

        double range = plugin.settings().particleViewRange();
        double rangeSquared = range * range;
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0));

        // Build a viewer-aware candidate set from nearby indexed chunks. Multiple players near the same
        // crate intentionally deduplicate to one world particle emission for this pass.
        Map<String, LocationStore.Link> candidates = new LinkedHashMap<>();
        Map<UUID, List<Player>> viewersByWorld = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            viewersByWorld.computeIfAbsent(world.getUID(), ignored -> new ArrayList<>()).add(player);
            Chunk chunk = player.getChunk();
            for (LocationStore.Link link : plugin.locations().nearbyChunks(world, chunk.getX(), chunk.getZ(), chunkRadius)) {
                candidates.putIfAbsent(link.position().key(), link);
            }
        }

        for (LocationStore.Link link : candidates.values()) {
            BlockPosition position = link.position();
            World world = position.loadedWorld();
            if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
            Crate crate = plugin.runtime().find(link.crateId()).orElse(null);
            if (crate == null || !crate.enabled()) continue;
            Location center = position.center(1.12);
            List<Player> viewers = viewersByWorld.get(world.getUID());
            if (center == null || viewers == null || !hasNearbyPlayer(viewers, center, rangeSquared)) continue;
            world.spawnParticle(plugin.settings().particle(), center, plugin.settings().particleCount(),
                    plugin.settings().particleHorizontalSpread(), plugin.settings().particleVerticalSpread(),
                    plugin.settings().particleHorizontalSpread(), 0.01);
        }
    }

    private static boolean hasNearbyPlayer(List<Player> players, Location location, double rangeSquared) {
        for (Player player : players) {
            if (player.getLocation().distanceSquared(location) <= rangeSquared) return true;
        }
        return false;
    }
}

package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.animation.CrateParticleCoordinator;
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
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

/** Runtime-only hologram and particle presentation. Reward correctness never depends on this service. */
public final class DisplayService {
    private final PlexonCrates plugin;
    private final NamespacedKey marker;
    private final Map<String, UUID> holograms = new HashMap<>();
    private final CrateParticleCoordinator particles;

    public DisplayService(PlexonCrates plugin) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "crate_hologram");
        this.particles = new CrateParticleCoordinator(plugin);
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
        particles.refresh();
    }

    public void stop() {
        particles.stop();
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

    public int activeHolograms() { return holograms.size(); }
    public boolean particleCoordinatorRunning() { return particles.running(); }
    public long particleCandidateLocations() { return particles.candidateLocations(); }
    public long particleEmittedLocations() { return particles.emittedLocations(); }
    public long particleEmittedParticles() { return particles.emittedParticles(); }
    public long particleBudgetDeferrals() { return particles.budgetDeferrals(); }

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
}

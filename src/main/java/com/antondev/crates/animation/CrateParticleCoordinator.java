package com.antondev.crates.animation;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.BlockPosition;
import com.antondev.crates.model.Crate;
import com.antondev.crates.service.LocationStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * One shared, bounded idle-particle scheduler for every linked physical crate.
 *
 * <p>The coordinator never owns transaction state. It derives candidate crates from the existing
 * {@link LocationStore} chunk index, scopes emissions to nearby receivers, and applies global,
 * per-crate and per-viewer budgets before invoking Bukkit particle APIs.</p>
 */
public final class CrateParticleCoordinator {
    private final PlexonCrates plugin;
    private final IdleAnimationProfileStore profiles;
    private BukkitTask task;
    private int cursor;
    private long elapsedTicks;
    private long candidateLocations;
    private long emittedLocations;
    private long emittedParticles;
    private long budgetDeferrals;
    private boolean particleFailureLogged;

    public CrateParticleCoordinator(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = IdleAnimationProfileStore.shared(plugin);
    }

    /** Rebuilds scheduling from the current validated settings and linked-location state. */
    public void refresh() {
        stopTask();
        cursor = 0;
        elapsedTicks = 0L;
        particleFailureLogged = false;
        if (!plugin.isEnabled() || !plugin.settings().particlesEnabled()
                || plugin.locations().size() == 0) return;
        long interval = plugin.settings().particleInterval();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        stopTask();
        cursor = 0;
        elapsedTicks = 0L;
    }

    public boolean running() {
        return task != null && !task.isCancelled();
    }

    public long candidateLocations() { return candidateLocations; }
    public long emittedLocations() { return emittedLocations; }
    public long emittedParticles() { return emittedParticles; }
    public long budgetDeferrals() { return budgetDeferrals; }

    private void stopTask() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (!plugin.isEnabled() || !plugin.settings().particlesEnabled()
                || plugin.locations().size() == 0) {
            stopTask();
            return;
        }
        IdleAnimationProfile legacy = plugin.settings().idleParticleProfile();
        long interval = Math.max(1L, plugin.settings().particleInterval());
        elapsedTicks += interval;
        if (plugin.getServer().getOnlinePlayers().isEmpty()) return;

        double maximumRange = profiles.maximumReceiverRange(legacy);
        int chunkRadius = Math.max(1, (int) Math.ceil(maximumRange / 16.0));
        Map<String, LocationStore.Link> candidates = new LinkedHashMap<>();
        Map<UUID, List<Player>> viewersByWorld = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            viewersByWorld.computeIfAbsent(world.getUID(), ignored -> new ArrayList<>()).add(player);
            Chunk chunk = player.getChunk();
            for (LocationStore.Link link : plugin.locations().nearbyChunks(
                    world, chunk.getX(), chunk.getZ(), chunkRadius)) {
                candidates.putIfAbsent(link.position().key(), link);
            }
        }
        if (candidates.isEmpty()) return;

        List<LocationStore.Link> ordered = new ArrayList<>(candidates.values());
        candidateLocations += ordered.size();
        int total = ordered.size();
        int processCount = Math.min(total, plugin.settings().particleMaxLocationsPerTick());
        if (total > processCount) budgetDeferrals += total - processCount;
        int start = plugin.settings().particleStagger() ? Math.floorMod(cursor, total) : 0;
        int globalRemaining = plugin.settings().particleMaxParticlesPerTick();
        Map<UUID, Integer> viewerRemaining = new HashMap<>();
        int processed = 0;

        for (; processed < processCount && globalRemaining > 0; processed++) {
            LocationStore.Link link = ordered.get((start + processed) % total);
            BlockPosition position = link.position();
            World world = position.loadedWorld();
            if (world == null || !world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) continue;
            Crate crate = plugin.runtime().find(link.crateId()).orElse(null);
            if (crate == null || !crate.enabled()) continue;
            IdleAnimationProfile profile = profiles.resolve(crate.id(), legacy);
            if (!profile.enabled()) continue;
            Location origin = position.center(0.08);
            List<Player> viewers = viewersByWorld.get(world.getUID());
            if (origin == null || viewers == null || viewers.isEmpty()) continue;

            List<IdleAnimationMath.Offset> offsets = IdleAnimationMath.sample(profile, elapsedTicks);
            if (offsets.isEmpty()) continue;
            double rangeSquared = profile.receiverRange() * profile.receiverRange();
            int crateRemaining = Math.min(profile.maxPerCratePerTick(), globalRemaining);
            boolean emittedForCrate = false;
            for (Player viewer : viewers) {
                if (crateRemaining <= 0 || globalRemaining <= 0) break;
                if (viewer.getLocation().distanceSquared(origin) > rangeSquared) continue;
                int receiverRemaining = viewerRemaining.computeIfAbsent(
                        viewer.getUniqueId(), ignored -> profile.maxPerViewerPerTick());
                if (receiverRemaining <= 0) {
                    budgetDeferrals++;
                    continue;
                }
                for (IdleAnimationMath.Offset offset : offsets) {
                    if (crateRemaining <= 0 || globalRemaining <= 0 || receiverRemaining <= 0) break;
                    int emit = Math.min(profile.particlesPerPoint(),
                            Math.min(crateRemaining, Math.min(globalRemaining, receiverRemaining)));
                    if (emit <= 0) break;
                    Location location = origin.clone().add(offset.x(), offset.y(), offset.z());
                    if (!emit(viewer, profile, location, emit)) return;
                    emittedForCrate = true;
                    emittedParticles += emit;
                    crateRemaining -= emit;
                    globalRemaining -= emit;
                    receiverRemaining -= emit;
                }
                viewerRemaining.put(viewer.getUniqueId(), receiverRemaining);
            }
            if (emittedForCrate) emittedLocations++;
            if (crateRemaining <= 0 || globalRemaining <= 0) budgetDeferrals++;
        }

        if (processed < processCount) budgetDeferrals += processCount - processed;
        if (plugin.settings().particleStagger()) cursor = (start + Math.max(1, processCount)) % total;
    }

    private boolean emit(Player viewer, IdleAnimationProfile profile, Location location, int count) {
        try {
            viewer.spawnParticle(profile.particle(), location, count, 0.0, 0.0, 0.0, 0.0);
            return true;
        } catch (RuntimeException error) {
            if (!particleFailureLogged) {
                particleFailureLogged = true;
                plugin.getLogger().log(Level.WARNING,
                        "Idle particle profile failed at runtime; disabling the shared particle coordinator", error);
            }
            stopTask();
            return false;
        }
    }
}

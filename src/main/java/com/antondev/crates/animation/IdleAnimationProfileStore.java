package com.antondev.crates.animation;

import com.antondev.crates.PlexonCrates;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import org.bukkit.Bukkit;

/** Shared live owner for idle-animations.yml with ordered off-thread persistence. */
public final class IdleAnimationProfileStore {
    private static final Map<PlexonCrates, IdleAnimationProfileStore> SHARED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final PlexonCrates plugin;
    private final File file;
    private final IdleAnimationProfiles profiles;
    private final Object writeLock = new Object();
    private CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);

    private IdleAnimationProfileStore(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        ensureBundledFile(plugin);
        this.file = new File(plugin.getDataFolder(), "idle-animations.yml");
        this.profiles = new IdleAnimationProfiles(
                IdleAnimationProfiles.load(file, plugin.settings().idleParticleProfile()));
    }

    public static IdleAnimationProfileStore shared(PlexonCrates plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(plugin, IdleAnimationProfileStore::new);
        }
    }

    public IdleAnimationProfiles.Snapshot snapshot() { return profiles.snapshot(); }

    public IdleAnimationProfile resolve(String crateId, IdleAnimationProfile legacy) {
        return profiles.resolve(crateId, legacy);
    }

    public double maximumReceiverRange(IdleAnimationProfile legacy) {
        return profiles.maximumReceiverRange(legacy);
    }

    public IdleAnimationProfiles.Snapshot reload() {
        IdleAnimationProfiles.Snapshot loaded = IdleAnimationProfiles.load(
                file, plugin.settings().idleParticleProfile());
        profiles.apply(loaded);
        return loaded;
    }

    public CompletableFuture<Void> mutate(UnaryOperator<IdleAnimationProfiles.Snapshot> change) {
        Objects.requireNonNull(change, "change");
        IdleAnimationProfiles.Snapshot next;
        synchronized (writeLock) {
            next = Objects.requireNonNull(change.apply(profiles.snapshot()), "mutation result");
            profiles.apply(next);
            String yaml = IdleAnimationProfiles.serialize(next);
            pendingWrite = pendingWrite.handle((ignored, previousError) -> null)
                    .thenCompose(ignored -> runAsync(() -> persist(yaml)));
            return pendingWrite;
        }
    }

    public CompletableFuture<Void> flush() {
        synchronized (writeLock) { return pendingWrite; }
    }

    private CompletableFuture<Void> runAsync(CheckedRunnable work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            try {
                work.run();
                future.complete(null);
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
            return future;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                work.run();
                future.complete(null);
            } catch (Throwable error) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist idle-animations.yml", error);
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private void persist(String yaml) throws IOException {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureBundledFile(PlexonCrates plugin) {
        File target = new File(plugin.getDataFolder(), "idle-animations.yml");
        if (target.isFile()) return;
        plugin.getDataFolder().mkdirs();
        plugin.saveResource("idle-animations.yml", false);
    }

    @FunctionalInterface
    private interface CheckedRunnable { void run() throws Exception; }
}

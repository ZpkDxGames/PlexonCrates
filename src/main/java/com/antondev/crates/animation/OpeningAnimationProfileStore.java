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

/**
 * Shared live owner for animations.yml. Reads occur during service construction;
 * mutations swap an immutable in-memory snapshot immediately and serialize disk
 * writes off the primary server thread in mutation order.
 */
public final class OpeningAnimationProfileStore {
    private static final Map<PlexonCrates, OpeningAnimationProfileStore> SHARED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final PlexonCrates plugin;
    private final File file;
    private final OpeningAnimationProfiles profiles;
    private final Object writeLock = new Object();
    private CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);

    private OpeningAnimationProfileStore(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        ensureBundledFile(plugin);
        this.file = new File(plugin.getDataFolder(), "animations.yml");
        this.profiles = new OpeningAnimationProfiles(
                OpeningAnimationProfiles.load(file, plugin.settings().defaultAnimation()));
    }

    public static OpeningAnimationProfileStore shared(PlexonCrates plugin) {
        Objects.requireNonNull(plugin, "plugin");
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(plugin, OpeningAnimationProfileStore::new);
        }
    }

    public OpeningAnimationProfiles.Snapshot snapshot() {
        return profiles.snapshot();
    }

    public OpeningAnimationProfile resolve(String crateId,
                                            com.antondev.crates.domain.crate.AnimationType legacy) {
        return profiles.resolve(crateId, legacy);
    }

    /** Reloads from disk synchronously; intended for the plugin's configuration reload phase. */
    public OpeningAnimationProfiles.Snapshot reload() {
        OpeningAnimationProfiles.Snapshot loaded = OpeningAnimationProfiles.load(
                file, plugin.settings().defaultAnimation());
        profiles.apply(loaded);
        return loaded;
    }

    /**
     * Atomically swaps a validated snapshot and queues its durable YAML write.
     * The returned future completes after that exact snapshot is persisted.
     */
    public CompletableFuture<Void> mutate(UnaryOperator<OpeningAnimationProfiles.Snapshot> change) {
        Objects.requireNonNull(change, "change");
        OpeningAnimationProfiles.Snapshot next;
        synchronized (writeLock) {
            next = Objects.requireNonNull(change.apply(profiles.snapshot()), "mutation result");
            // Snapshot constructor/helpers perform all reference/id validation before live state changes.
            profiles.apply(next);
            String yaml = OpeningAnimationProfiles.serialize(next);
            pendingWrite = pendingWrite.handle((ignored, previousError) -> null)
                    .thenCompose(ignored -> runAsync(() -> persist(yaml)));
            return pendingWrite;
        }
    }

    public CompletableFuture<Void> flush() {
        synchronized (writeLock) {
            return pendingWrite;
        }
    }

    private CompletableFuture<Void> runAsync(CheckedRunnable work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) {
            // Mock/unit callers and very early bootstrap can persist directly because no server tick is active.
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
                plugin.getLogger().log(Level.SEVERE, "Could not persist animations.yml", error);
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
        File target = new File(plugin.getDataFolder(), "animations.yml");
        if (target.isFile()) return;
        plugin.getDataFolder().mkdirs();
        plugin.saveResource("animations.yml", false);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}

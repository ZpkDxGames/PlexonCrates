package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** Serial live key-registry mutation boundary. Filesystem durability completes before primary-thread activation. */
public final class KeyMutationService {
    private final PlexonCrates plugin;
    private final AtomicBoolean busy = new AtomicBoolean();

    public KeyMutationService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public boolean busy() { return busy.get(); }

    public CompletableFuture<Void> createCaptured(String id, Component displayName, ItemStack item, String editor) {
        ItemStack captured = item == null ? null : item.clone();
        return commit(() -> plugin.keys().prepareCreateCaptured(
                plugin.keys().beginPreparedMutation(), id, displayName, captured, editor), () -> { });
    }

    public CompletableFuture<Void> replaceCaptured(String id, Component displayName, ItemStack item,
                                                   boolean keepPreviousAsLegacy, String editor) {
        ItemStack captured = item == null ? null : item.clone();
        return commit(() -> plugin.keys().prepareReplaceCaptured(
                plugin.keys().beginPreparedMutation(), id, displayName, captured, keepPreviousAsLegacy, editor), () -> { });
    }

    public CompletableFuture<Void> bindExternal(String id, String editor) {
        if (plugin.keys().definition(id).isPresent()) return CompletableFuture.completedFuture(null);
        return commit(() -> plugin.keys().prepareBindExternal(
                plugin.keys().beginPreparedMutation(), id, editor), () -> { });
    }

    public CompletableFuture<Void> delete(String id, String editor) {
        return commit(() -> plugin.keys().prepareDelete(
                plugin.keys().beginPreparedMutation(), id, editor), () -> plugin.keys().clearCachedTemplate(id));
    }

    public CompletableFuture<List<String>> importDefinitions(Path sourceFile, String editor) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Key import must be requested on the primary thread"));
        }
        if (!busy.compareAndSet(false, true)) return busyFailure();
        Path source = sourceFile.toAbsolutePath().normalize();
        CompletableFuture<List<String>> future = plugin.io().submit(() -> {
            if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".yml")) {
                throw new IllegalArgumentException("Key import source must be an existing .yml file");
            }
            return Files.readString(source, StandardCharsets.UTF_8);
        }).thenCompose(payload -> primary(() -> plugin.keys().prepareImport(
                plugin.keys().beginPreparedMutation(), payload, editor)))
                .thenCompose(prepared -> plugin.io().run(() -> plugin.keys().writePreparedMutation(prepared.mutation()))
                        .thenCompose(ignored -> primary(() -> {
                            plugin.keys().installPreparedMutation(prepared.mutation());
                            return prepared.importedIds();
                        })));
        return future.whenComplete((ignored, error) -> busy.set(false));
    }

    private CompletableFuture<Void> commit(CheckedPreparation preparation, Runnable afterInstall) {
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Key mutation must be requested on the primary thread"));
        }
        if (!busy.compareAndSet(false, true)) return busyFailure();
        KeyService.PreparedMutation prepared;
        try {
            prepared = preparation.prepare();
        } catch (Exception error) {
            busy.set(false);
            return CompletableFuture.failedFuture(error);
        }
        CompletableFuture<Void> future = plugin.io().run(() -> plugin.keys().writePreparedMutation(prepared))
                .thenCompose(ignored -> primary(() -> {
                    plugin.keys().installPreparedMutation(prepared);
                    afterInstall.run();
                    return null;
                }));
        return future.whenComplete((ignored, error) -> busy.set(false));
    }

    private <T> CompletableFuture<T> primary(Callable<T> task) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(task.call());
                } catch (Exception error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private static <T> CompletableFuture<T> busyFailure() {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Another key-registry mutation is already in progress"));
    }

    @FunctionalInterface
    private interface CheckedPreparation {
        KeyService.PreparedMutation prepare() throws Exception;
    }
}

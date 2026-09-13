package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.Crate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/** Live administrator crate import/export boundary with bounded filesystem I/O. */
public final class CrateTransferService {
    private final PlexonCrates plugin;

    public CrateTransferService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public CompletableFuture<Crate> importDraft(
            UUID actorId, String actorName, Path sourceFile, String newId) {
        Path source = sourceFile.toAbsolutePath().normalize();
        return plugin.io().submit(() -> {
            if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".yml")) {
                throw new IllegalArgumentException("Import source must be an existing .yml file");
            }
            return Files.readString(source, StandardCharsets.UTF_8);
        }).thenCompose(sourceYaml -> primary(() ->
                plugin.crates().prepareImportedActivation(sourceYaml, newId, actorName)))
                .thenCompose(prepared -> plugin.draftCreation()
                        .activatePrepared(actorId, actorName, prepared));
    }

    public CompletableFuture<Path> export(String crateId, Path exportDirectory) {
        try {
            requirePrimary();
            CrateRegistry.PreparedExport prepared = plugin.crates().prepareExport(crateId, exportDirectory);
            return plugin.io().submit(() -> plugin.crates().writeExport(prepared));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
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
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(task.call());
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    private static void requirePrimary() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Crate export preparation requires the primary thread");
        }
    }
}

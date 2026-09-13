package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.database.DatabaseService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;

/** Confirmed crate deletion sequence: draft -> mirror -> canonical store -> primary activation. */
public final class CrateDeletionService {
    private final PlexonCrates plugin;

    public CrateDeletionService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public CompletableFuture<DatabaseService.DeleteResult> delete(
            UUID actorId, String actorName, String crateId) {
        Objects.requireNonNull(actorId, "actorId");
        String actor = Objects.requireNonNull(actorName, "actorName");
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Crate deletion must be requested on the primary thread"));
        }
        CrateRegistry.PreparedDeletion prepared;
        try {
            prepared = plugin.crates().prepareDeletion(crateId);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
        return plugin.draftSessions().discardCrate(actorId, prepared.crateId())
                .thenCompose(ignored -> plugin.io().run(() -> plugin.crates().deleteMirror(prepared)))
                .thenCompose(ignored -> plugin.definitionRepository()
                        .delete(prepared.crateId(), actorId, actor))
                .thenCompose(deleted -> primary(() -> {
                    plugin.runtime().remove(deleted.runtimeRevision(), deleted.definitionRevision(), prepared.crateId());
                    plugin.forgetDefinitionRevision(prepared.crateId());
                    plugin.crates().installDeletion(prepared);
                    if (!deleted.removed()) {
                        plugin.database().audit(new DatabaseService.AuditRecord(actorId, actor, "DELETE", "CRATE",
                                prepared.crateId(), "Deleted confirmed unpublished crate definition", Instant.now()));
                    }
                    return deleted;
                }));
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
                try { future.complete(task.call()); }
                catch (Exception error) { future.completeExceptionally(error); }
            });
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }
}

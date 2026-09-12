package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.Crate;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import org.bukkit.Bukkit;

/** Durable-first coordinator for new and cloned administrator crate drafts. */
public final class CrateDraftCreationService {
    private final PlexonCrates plugin;

    public CrateDraftCreationService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public CompletableFuture<Crate> create(UUID actorId, String actorName, String crateId) {
        try {
            requirePrimary();
            return activate(actorId, actorName, plugin.crates().prepareNewDraft(crateId, actorName));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Crate> createQuick(UUID actorId, String actorName) {
        try {
            requirePrimary();
            return activate(actorId, actorName, plugin.crates().prepareQuickDraft(actorName));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public CompletableFuture<Crate> cloneDraft(UUID actorId, String actorName, String sourceId, String newId) {
        try {
            requirePrimary();
            return activate(actorId, actorName, plugin.crates().prepareCloneDraft(sourceId, newId, actorName));
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private CompletableFuture<Crate> activate(UUID actorId, String actorName,
                                               CrateRegistry.PreparedDraftActivation candidate) {
        return plugin.draftSessions().openCrate(actorId, actorName, candidate.crateId(),
                        plugin.definitionRevision(candidate.crateId()), candidate.payload())
                .thenCompose(view -> primary(() -> {
                    DraftSessionService.View current = plugin.draftSessions()
                            .view(actorId, candidate.crateId()).orElse(view);
                    if (!current.writable()) {
                        throw new DraftSessionService.DraftAccessException(
                                "This draft is currently edited by "
                                        + (current.ownerName().isBlank() ? "another administrator" : current.ownerName()));
                    }
                    byte[] durablePayload = plugin.draftSessions().payload(actorId, candidate.crateId())
                            .orElseThrow(() -> new IllegalStateException("The durable draft payload is unavailable"));
                    CrateRegistry.PreparedDraftActivation durable =
                            plugin.crates().prepareDurableDraft(candidate.crateId(), durablePayload);
                    Crate installed = plugin.crates().installPreparedDraft(durable);
                    plugin.crates().queuePreparedDraftMirror(durable);
                    return installed;
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
            throw new IllegalStateException("Crate draft candidate preparation requires the primary thread");
        }
    }
}

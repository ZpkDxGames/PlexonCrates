package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.Text;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.item.ItemSnapshotCodec;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Durable exact-item recovery queue. Claims are reserved before inventory
 * mutation and are never retried automatically after an uncertain boundary.
 */
public final class ClaimService {
    private static final int PAGE_SIZE = 20;

    private final PlexonCrates plugin;
    private final ItemSnapshotCodec snapshots = new ItemSnapshotCodec();

    public ClaimService(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Moves interrupted reservations to manual review during startup. */
    public void recover() {
        plugin.database().recoverClaimingClaims().whenComplete((count, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not recover interrupted Claim Inbox entries", error);
            } else if (count != null && count > 0) {
                plugin.getLogger().warning("Moved " + count
                        + " interrupted Claim Inbox attempt(s) to manual review; no claim was retried automatically.");
            }
        });
    }

    public CompletableFuture<List<DatabaseService.ClaimEntry>> list(UUID playerId, int page) {
        int safePage = Math.max(1, page);
        return plugin.database().loadClaims(playerId, PAGE_SIZE, (safePage - 1) * PAGE_SIZE);
    }

    public CompletableFuture<Integer> pendingCount(UUID playerId) {
        return plugin.database().pendingClaimCount(playerId);
    }

    /** Queues one exact item stack using an idempotent source token. */
    public CompletableFuture<DatabaseService.ClaimEntry> enqueueItem(
            UUID playerId, String sourceType, String sourceId, String crateId, String rewardId,
            String idempotencyToken, ItemStack item) {
        ItemSnapshotCodec.Snapshot snapshot = snapshots.capture(item);
        return plugin.database().createItemClaim(playerId, sourceType, sourceId, crateId, rewardId,
                idempotencyToken, snapshot.bytes(), snapshot.capturedAmount(), snapshot.sha256(), Instant.now());
    }

    public CompletableFuture<DatabaseService.ClaimEntry> enqueueVirtualKey(
            UUID playerId, String sourceType, String sourceId, String crateId, String rewardId,
            String idempotencyToken, String keyId, int amount) {
        return plugin.database().createVirtualKeyClaim(playerId, sourceType, sourceId, crateId, rewardId,
                idempotencyToken, keyId, amount, Instant.now());
    }

    /**
     * Attempts one pending claim. All Bukkit inventory work stays on the primary
     * thread; database reservation/completion remains on the bounded writer.
     */
    public void claim(Player player, UUID claimId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Claims must begin on the primary server thread");
        String attempt = UUID.randomUUID().toString();
        plugin.database().reserveClaim(player.getUniqueId(), claimId, attempt).whenComplete((reserved, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    // Reservation happened, but no inventory mutation could have
                    // started while the player was offline. Release it for a
                    // later deliberate attempt instead of leaving a false
                    // CLAIMING entry behind until the next restart.
                    if (error == null && reserved != null && reserved.isPresent()) {
                        plugin.database().releaseClaim(claimId, attempt, "Player disconnected before claim delivery");
                    }
                    return;
                }
                if (error != null) {
                    plugin.messages().send(player, "database-error");
                    return;
                }
                if (reserved == null || reserved.isEmpty()) {
                    player.sendMessage(Text.parse("<yellow>That claim is no longer pending, or it needs administrator review.</yellow>"));
                    return;
                }
                if (reserved.get().virtualKeyId() != null) {
                    deliverVirtualReserved(player, reserved.get(), attempt);
                } else {
                    deliverReserved(player, reserved.get(), attempt);
                }
            });
        });
    }

    private void deliverVirtualReserved(Player player, DatabaseService.ClaimEntry claim, String attempt) {
        String creditToken = "claim:" + claim.claimId() + ":credit";
        plugin.database().creditVirtualKeys(player.getUniqueId(), claim.virtualKeyId(), claim.virtualKeyAmount(),
                creditToken, "CLAIM", claim.claimId().toString(), null).whenComplete((credited, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || credited == null || !credited.applied()) {
                    review(player, claim, attempt, "Virtual-key credit could not be finalized", error);
                    return;
                }
                plugin.database().completeClaim(claim.claimId(), attempt).whenComplete((completed, completionError) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (completionError != null || completed == null || completed.isEmpty()) {
                            player.sendMessage(Text.parse("<red>The virtual-key claim was credited but needs administrator review.</red>"));
                        } else if (player.isOnline()) {
                            player.sendMessage(Text.parse("<green>Virtual-key claim delivered exactly.</green>"));
                        }
                    });
                });
            });
        });
    }

    private void deliverReserved(Player player, DatabaseService.ClaimEntry claim, String attempt) {
        if (claim.itemBytes() == null) {
            plugin.database().markClaimReview(claim.claimId(), attempt,
                    "Virtual-key claims are not delivered by the physical-item Claim Inbox");
            player.sendMessage(Text.parse("<red>This claim needs administrator review.</red>"));
            return;
        }
        ItemSnapshotCodec.Snapshot snapshot;
        try {
            snapshot = new ItemSnapshotCodec.Snapshot(claim.itemBytes(), "unknown", claim.itemAmount(),
                    claim.itemBytes().length, claim.itemSha256(), false, false, claim.createdAt());
        } catch (RuntimeException error) {
            review(player, claim, attempt, "Stored exact-item metadata is invalid", error);
            return;
        }
        List<ItemStack> stacks;
        try {
            stacks = snapshots.deliveryStacks(snapshot, claim.itemAmount());
        } catch (RuntimeException error) {
            review(player, claim, attempt, "Stored exact item could not be decoded", error);
            return;
        }
        if (!InventoryPlanner.fits(player.getInventory().getStorageContents(), stacks)) {
            plugin.database().releaseClaim(claim.claimId(), attempt, "Inventory does not have enough space");
            player.sendMessage(Text.parse("<yellow>Your inventory cannot fit this exact claim. Nothing was changed.</yellow>"));
            return;
        }
        var leftovers = new java.util.ArrayList<ItemStack>();
        for (ItemStack stack : stacks) leftovers.addAll(player.getInventory().addItem(stack).values());
        if (!leftovers.isEmpty()) {
            review(player, claim, attempt, "Inventory changed during exact claim insertion", null);
            return;
        }
        plugin.database().completeClaim(claim.claimId(), attempt).whenComplete((completed, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null || completed == null || completed.isEmpty()) {
                    plugin.database().markClaimReview(claim.claimId(), attempt,
                            "Inventory insertion completed but claim finalization was uncertain");
                    player.sendMessage(Text.parse("<red>The claim was delivered but needs administrator review before it can be retried.</red>"));
                } else if (player.isOnline()) {
                    player.sendMessage(Text.parse("<green>Claim delivered exactly.</green>"));
                }
            });
        });
    }

    private void review(Player player, DatabaseService.ClaimEntry claim, String attempt,
                        String reason, Throwable error) {
        if (error != null) plugin.getLogger().log(Level.WARNING,
                "Claim " + claim.claimId() + " moved to review: " + reason, error);
        plugin.database().markClaimReview(claim.claimId(), attempt, reason);
        player.sendMessage(Text.parse("<red>This exact claim needs administrator review.</red>"));
    }
}

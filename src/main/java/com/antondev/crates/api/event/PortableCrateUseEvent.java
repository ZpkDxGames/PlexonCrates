package com.antondev.crates.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a portable token and its durable issuance have been validated,
 * but before the issuance is reserved or the matching item is consumed.
 */
public final class PortableCrateUseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID issueId;
    private final String crateId;
    private final String revisionPolicy;
    private final long pinnedRevision;
    private boolean cancelled;

    public PortableCrateUseEvent(Player player, UUID issueId, String crateId,
                                 String revisionPolicy, long pinnedRevision) {
        this.player = Objects.requireNonNull(player, "player");
        this.issueId = Objects.requireNonNull(issueId, "issueId");
        this.crateId = Objects.requireNonNull(crateId, "crateId");
        this.revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
        this.pinnedRevision = pinnedRevision;
    }

    public Player player() { return player; }
    public UUID issueId() { return issueId; }
    public String crateId() { return crateId; }
    public String revisionPolicy() { return revisionPolicy; }
    public long pinnedRevision() { return pinnedRevision; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

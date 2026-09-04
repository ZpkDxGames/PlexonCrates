package com.antondev.crates.api.event;

import com.antondev.crates.model.Crate;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Cancellable primary-thread event fired after validation and before the definition transaction. */
public final class CrateDraftPublishEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID actorId;
    private final String actorName;
    private final UUID draftId;
    private final long draftRevision;
    private final long basePublishedRevision;
    private final Crate candidate;
    private boolean cancelled;

    public CrateDraftPublishEvent(UUID actorId, String actorName, UUID draftId, long draftRevision,
                                  long basePublishedRevision, Crate candidate) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.actorName = Objects.requireNonNull(actorName, "actorName");
        this.draftId = Objects.requireNonNull(draftId, "draftId");
        this.draftRevision = draftRevision;
        this.basePublishedRevision = basePublishedRevision;
        this.candidate = Objects.requireNonNull(candidate, "candidate");
    }

    public UUID actorId() { return actorId; }
    public String actorName() { return actorName; }
    public UUID draftId() { return draftId; }
    public long draftRevision() { return draftRevision; }
    public long basePublishedRevision() { return basePublishedRevision; }
    public Crate candidate() { return candidate; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

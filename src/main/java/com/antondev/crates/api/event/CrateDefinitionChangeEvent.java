package com.antondev.crates.api.event;

import com.antondev.crates.model.Crate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired on the primary server thread after a crate definition change is persisted and installed. */
public final class CrateDefinitionChangeEvent extends Event {
    public enum ChangeType { CREATED, UPDATED, PUBLISHED, DISABLED, ARCHIVED, DELETED }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Crate definition;
    private final ChangeType changeType;

    public CrateDefinitionChangeEvent(Crate definition, ChangeType changeType) {
        this.definition = definition;
        this.changeType = changeType;
    }

    public String crateId() { return definition.id(); }
    public Crate definition() { return definition; }
    public ChangeType changeType() { return changeType; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

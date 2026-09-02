package com.antondev.crates.api.event;

import com.antondev.crates.model.BlockPosition;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class CrateLinkEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String crateId;
    private final BlockPosition position;
    private boolean cancelled;

    public CrateLinkEvent(Player player, String crateId, BlockPosition position) {
        this.player = player;
        this.crateId = crateId;
        this.position = position;
    }
    public Player player() { return player; }
    public String crateId() { return crateId; }
    public BlockPosition position() { return position; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

package com.antondev.crates.api.event;

import com.antondev.crates.domain.opening.OpeningPlan;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class CratePreOpenEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final OpeningPlan plan;
    private boolean cancelled;

    public CratePreOpenEvent(Player player, OpeningPlan plan) {
        this.player = player;
        this.plan = plan;
    }

    public Player player() { return player; }
    public OpeningPlan plan() { return plan; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

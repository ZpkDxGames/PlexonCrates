package com.antondev.crates.api.event;

import com.antondev.crates.domain.opening.OpeningPlan;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class CrateOpenEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final OpeningPlan plan;
    private final int overflowCount;

    public CrateOpenEvent(Player player, OpeningPlan plan, int overflowCount) {
        this.player = player;
        this.plan = plan;
        this.overflowCount = overflowCount;
    }

    public Player player() { return player; }
    public OpeningPlan plan() { return plan; }
    public int overflowCount() { return overflowCount; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

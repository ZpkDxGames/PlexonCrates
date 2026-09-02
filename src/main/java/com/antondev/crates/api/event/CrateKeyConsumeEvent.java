package com.antondev.crates.api.event;

import com.antondev.crates.domain.opening.OpeningPlan;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class CrateKeyConsumeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final OpeningPlan plan;

    public CrateKeyConsumeEvent(Player player, OpeningPlan plan) {
        this.player = player;
        this.plan = plan;
    }

    public Player player() { return player; }
    public OpeningPlan plan() { return plan; }
    public String keyId() { return plan.keyId(); }
    public int amount() { return plan.keyAmount(); }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

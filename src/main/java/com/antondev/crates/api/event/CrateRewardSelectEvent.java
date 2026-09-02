package com.antondev.crates.api.event;

import com.antondev.crates.domain.opening.OpeningPlan;
import com.antondev.crates.domain.opening.RewardDelivery;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class CrateRewardSelectEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final OpeningPlan plan;
    private final RewardDelivery delivery;

    public CrateRewardSelectEvent(Player player, OpeningPlan plan, RewardDelivery delivery) {
        this.player = player;
        this.plan = plan;
        this.delivery = delivery;
    }

    public Player player() { return player; }
    public OpeningPlan plan() { return plan; }
    public RewardDelivery delivery() { return delivery; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

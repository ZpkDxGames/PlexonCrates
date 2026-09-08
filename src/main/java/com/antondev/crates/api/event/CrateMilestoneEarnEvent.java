package com.antondev.crates.api.event;

import com.antondev.crates.domain.opening.OpeningPlan;
import com.antondev.crates.model.CrateMilestone;
import com.antondev.crates.service.MilestoneService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Immutable notification fired only after milestone state and claims commit. */
public final class CrateMilestoneEarnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final OpeningPlan plan;
    private final CrateMilestone milestone;
    private final MilestoneService.Earned earning;

    public CrateMilestoneEarnEvent(Player player, OpeningPlan plan, CrateMilestone milestone,
                                   MilestoneService.Earned earning) {
        this.player = java.util.Objects.requireNonNull(player, "player");
        this.plan = java.util.Objects.requireNonNull(plan, "plan");
        this.milestone = java.util.Objects.requireNonNull(milestone, "milestone");
        this.earning = java.util.Objects.requireNonNull(earning, "earning");
    }

    public Player player() { return player; }
    public OpeningPlan plan() { return plan; }
    public CrateMilestone milestone() { return milestone; }
    public MilestoneService.Earned earning() { return earning; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}

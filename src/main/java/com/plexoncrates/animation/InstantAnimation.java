package com.plexoncrates.animation;

import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Immediate opening preset with no scheduled visual sequence. */
public final class InstantAnimation implements Animation {
    @Override
    public void play(Player player, Crate crate, Reward reward, int durationTicks, Runnable complete) {
        complete.run();
    }

    @Override
    public void cancel(UUID playerId) {
        // INSTANT has no scheduled state to cancel.
    }
}

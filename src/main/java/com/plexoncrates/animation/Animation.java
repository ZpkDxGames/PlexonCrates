package com.plexoncrates.animation;

import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import java.util.UUID;
import org.bukkit.entity.Player;

public interface Animation {
    void play(Player player, Crate crate, Reward reward, int durationTicks, Runnable complete);
    void cancel(UUID playerId);
}

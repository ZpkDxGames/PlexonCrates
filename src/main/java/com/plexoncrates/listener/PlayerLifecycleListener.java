package com.plexoncrates.listener;

import com.plexoncrates.core.PlexonCrates;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final PlexonCrates plugin;public PlayerLifecycleListener(PlexonCrates plugin){this.plugin=plugin;}
    @EventHandler public void onJoin(PlayerJoinEvent event){plugin.database().virtualKeys(event.getPlayer().getUniqueId()).exceptionally(error->{plugin.getLogger().log(Level.FINE,"Virtual key preload failed for "+event.getPlayer().getName(),error);return java.util.Map.of();});}
    @EventHandler public void onQuit(PlayerQuitEvent event){plugin.animations().cancel(event.getPlayer().getUniqueId());}
}

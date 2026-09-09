package com.plexoncrates.listener;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.manager.CrateManager;
import java.util.Optional;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class PlayerInteractListener implements Listener {
    private final PlexonCrates plugin;
    private final ConfigManager config;
    private final CrateManager crates;

    public PlayerInteractListener(PlexonCrates plugin, ConfigManager config, CrateManager crates) {
        this.plugin = plugin;
        this.config = config;
        this.crates = crates;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        try {
            Optional<Crate> found = crates.at(event.getClickedBlock());
            if (found.isEmpty()) return;
            event.setCancelled(true);

            Crate crate = found.get();
            if (!event.getPlayer().hasPermission("plexoncrates.use")) return;
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                if (event.getPlayer().hasPermission("plexoncrates.preview")) {
                    plugin.gui().openPreview(event.getPlayer(), crate);
                }
                return;
            }

            if (!config.physicalKeysEnabled()) {
                if (event.getPlayer().hasPermission("plexoncrates.preview")) plugin.gui().openPreview(event.getPlayer(), crate);
                return;
            }

            // Avoid serializing/scanning the entire inventory on the common held-key path.
            boolean shouldOpen = config.requireHeldPhysicalKey()
                    ? plugin.keys().matchesPhysical(event.getItem(), crate)
                    : plugin.keys().countPhysical(event.getPlayer(), crate) > 0;

            if (shouldOpen && event.getPlayer().hasPermission("plexoncrates.open")) {
                plugin.openings().openPhysical(event.getPlayer(), crate, event.getClickedBlock().getLocation());
            } else if (event.getPlayer().hasPermission("plexoncrates.preview")) {
                plugin.gui().openPreview(event.getPlayer(), crate);
            }
        } catch (Throwable error) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.SEVERE,
                    "Physical crate interaction failed safely for " + event.getPlayer().getName(), error);
            event.getPlayer().sendMessage("§8[§6PlexonCrates§8] §cThis crate could not be opened safely. Check the server console.");
        }
    }
}

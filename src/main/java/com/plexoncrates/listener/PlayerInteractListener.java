package com.plexoncrates.listener;

import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.manager.CrateManager;
import java.util.Optional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class PlayerInteractListener implements Listener {
    private final PlexonCrates plugin;private final ConfigManager config;private final CrateManager crates;
    public PlayerInteractListener(PlexonCrates plugin,ConfigManager config,CrateManager crates){this.plugin=plugin;this.config=config;this.crates=crates;}
    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent event){if(event.getHand()!=EquipmentSlot.HAND||event.getClickedBlock()==null)return;if(event.getAction()!=Action.RIGHT_CLICK_BLOCK&&event.getAction()!=Action.LEFT_CLICK_BLOCK)return;Optional<Crate> found=crates.at(event.getClickedBlock());if(found.isEmpty())return;event.setCancelled(true);Crate crate=found.get();if(!event.getPlayer().hasPermission("plexoncrates.use"))return;if(event.getAction()==Action.LEFT_CLICK_BLOCK){if(event.getPlayer().hasPermission("plexoncrates.preview"))plugin.gui().openPreview(event.getPlayer(),crate);return;}boolean heldMatch=plugin.keys().matchesPhysical(event.getItem(),crate);boolean hasPhysical=plugin.keys().countPhysical(event.getPlayer(),crate)>0;boolean shouldOpen=config.physicalKeysEnabled()&&(config.requireHeldPhysicalKey()?heldMatch:hasPhysical);if(shouldOpen&&event.getPlayer().hasPermission("plexoncrates.open"))plugin.openings().openPhysical(event.getPlayer(),crate,event.getClickedBlock().getLocation());else if(event.getPlayer().hasPermission("plexoncrates.preview"))plugin.gui().openPreview(event.getPlayer(),crate);}
}

package com.plexoncrates.listener;

import com.plexoncrates.manager.CrateManager;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataType;

public final class BlockProtectionListener implements Listener {
    private final CrateManager crates;
    public BlockProtectionListener(com.plexoncrates.core.PlexonCrates plugin,CrateManager crates){this.crates=crates;}
    @EventHandler(ignoreCancelled=true) public void onBreak(BlockBreakEvent event){if(crates.at(event.getBlock()).isEmpty())return;if(!event.getPlayer().hasPermission("plexoncrates.admin.break")){event.setCancelled(true);return;}crates.unlink(event.getBlock());}
    @EventHandler(ignoreCancelled=true) public void onPlace(BlockPlaceEvent event){BlockState state=event.getBlockPlaced().getState();if(!(state instanceof TileState tile))return;String copied=tile.getPersistentDataContainer().get(crates.crateBlockKey(),PersistentDataType.STRING);if(copied==null||crates.isRegisteredLocation(event.getBlockPlaced()))return;tile.getPersistentDataContainer().remove(crates.crateBlockKey());tile.update(true,false);}
    @EventHandler(ignoreCancelled=true) public void onBlockExplode(BlockExplodeEvent event){event.blockList().removeIf(block->crates.at(block).isPresent());}
    @EventHandler(ignoreCancelled=true) public void onEntityExplode(EntityExplodeEvent event){event.blockList().removeIf(block->crates.at(block).isPresent());}
    @EventHandler(ignoreCancelled=true) public void onPistonExtend(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(block->crates.at(block).isPresent()))event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void onPistonRetract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(block->crates.at(block).isPresent()))event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void onPhysics(BlockPhysicsEvent event){if(crates.at(event.getBlock()).isPresent())event.setCancelled(true);}
}

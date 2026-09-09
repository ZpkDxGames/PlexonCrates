package com.plexoncrates.listener;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.manager.CrateManager;
import java.util.logging.Level;
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

/** Protects linked crate blocks without performing TileState/PDC reads on hot physics paths. */
public final class BlockProtectionListener implements Listener {
    private final PlexonCrates plugin;
    private final CrateManager crates;

    public BlockProtectionListener(PlexonCrates plugin, CrateManager crates) {
        this.plugin = plugin;
        this.crates = crates;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!crates.isRegisteredLocation(event.getBlock()) && crates.at(event.getBlock()).isEmpty()) return;
        if (!event.getPlayer().hasPermission("plexoncrates.admin.break")) {
            event.setCancelled(true);
            return;
        }
        try {
            crates.unlink(event.getBlock());
        } catch (Throwable error) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.SEVERE, "Could not safely unlink a broken crate block", error);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        BlockState state = event.getBlockPlaced().getState();
        if (!(state instanceof TileState tile)) return;
        String copied = tile.getPersistentDataContainer().get(crates.crateBlockKey(), PersistentDataType.STRING);
        if (copied == null || crates.isRegisteredLocation(event.getBlockPlaced())) return;
        tile.getPersistentDataContainer().remove(crates.crateBlockKey());
        tile.update(true, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(crates::isRegisteredLocation);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(crates::isRegisteredLocation);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(crates::isRegisteredLocation)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(crates::isRegisteredLocation)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        // BlockPhysicsEvent is extremely hot. Never call Block#getState or deserialize PDC here.
        if (crates.isRegisteredLocation(event.getBlock())) event.setCancelled(true);
    }
}

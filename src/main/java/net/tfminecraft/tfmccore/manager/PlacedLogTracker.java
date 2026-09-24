package net.tfminecraft.tfmccore.manager;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;

/** Placement markers saved with each chunk; unrecorded logs remain eligible. */
public class PlacedLogTracker implements Listener {

    public static boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_HYPHAE")
                || name.endsWith("CRIMSON_STEM") || name.endsWith("WARPED_STEM");
    }

    public static boolean isPlaced(Block block) {
        return isLog(block.getType()) && block.getChunk().getPersistentDataContainer()
                .has(key(block), PersistentDataType.BYTE);
    }

    private static NamespacedKey key(Block block) {
        // Local X/Z plus signed Y are unique within a chunk, including below Y=0.
        return new NamespacedKey("tfmccore", "placed_log/" + (block.getX() & 15)
                + "/" + block.getY() + "/" + (block.getZ() & 15));
    }

    private static void mark(Block block) {
        block.getChunk().getPersistentDataContainer().set(key(block), PersistentDataType.BYTE, (byte) 1);
    }

    private static void clear(Block block) {
        var data = block.getChunk().getPersistentDataContainer();
        NamespacedKey key = key(block);
        if (data.has(key, PersistentDataType.BYTE)) {
            data.remove(key);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (isLog(block.getType())) {
            mark(block);
        } else {
            clear(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // DropManager reads the marker at NORMAL priority, before this cleanup.
        clear(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        clear(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(PlacedLogTracker::clear);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(PlacedLogTracker::clear);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        event.getBlocks().forEach(state -> clear(state.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        // Paper supplies the movement direction for both extension and retraction.
        move(event.getBlocks(), event.getDirection());
    }

    private static void move(List<Block> blocks, BlockFace direction) {
        // Snapshot before clearing: a destination can also be another moved source.
        List<Block> placedDestinations = blocks.stream().filter(PlacedLogTracker::isPlaced)
                .map(block -> block.getRelative(direction)).toList();
        for (Block block : blocks) {
            clear(block);
            // Paper includes blocks destroyed by the piston in this list too.
            if (block.getPistonMoveReaction() != PistonMoveReaction.BREAK) {
                clear(block.getRelative(direction));
            }
        }
        placedDestinations.forEach(PlacedLogTracker::mark);
    }
}

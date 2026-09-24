package net.tfminecraft.tfmccore.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;

class PlacedLogTrackerTest {
    private final PlacedLogTracker tracker = new PlacedLogTracker();
    private final LogTestWorld world = new LogTestWorld();
    private final Block log = world.block(-1, -30, -1);

    @Test
    void placementSurvivesNewTrackerAndBlockWrappersAndStripping() throws Exception {
        place(log);
        Block reloaded = mock(Block.class);
        var savedChunk = log.getChunk();
        when(reloaded.getChunk()).thenReturn(savedChunk);
        when(reloaded.getX()).thenReturn(-1);
        when(reloaded.getY()).thenReturn(-30);
        when(reloaded.getZ()).thenReturn(-1);
        when(reloaded.getType()).thenReturn(Material.STRIPPED_OAK_LOG);
        assertTrue(PlacedLogTracker.isPlaced(reloaded));
        dispatch(new PlacedLogTracker(), new BlockBreakEvent(reloaded, mock(Player.class)));
        assertFalse(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void positionsAreIsolatedAcrossHeightChunksAndWorlds() throws Exception {
        place(log);
        assertFalse(PlacedLogTracker.isPlaced(world.block(-1, -29, -1)));
        assertFalse(PlacedLogTracker.isPlaced(world.block(15, -30, -1)));
        assertFalse(PlacedLogTracker.isPlaced(new LogTestWorld().block(-1, -30, -1)));
        assertTrue(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void cancelledPlacementAndBreakDoNotChangeMarkers() throws Exception {
        BlockPlaceEvent placement = placement(log);
        when(placement.isCancelled()).thenReturn(true);
        dispatch(tracker, placement);
        assertFalse(PlacedLogTracker.isPlaced(log));
        place(log);
        BlockBreakEvent breaking = new BlockBreakEvent(log, mock(Player.class));
        breaking.setCancelled(true);
        dispatch(tracker, breaking);
        assertTrue(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void deniedBuildDoesNotMarkLog() throws Exception {
        BlockPlaceEvent event = placement(log);
        when(event.canBuild()).thenReturn(false);
        dispatch(tracker, event);
        assertFalse(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void successfulBreakAndNonLogReplacementClearMarkers() throws Exception {
        place(log);
        dispatch(tracker, new BlockBreakEvent(log, mock(Player.class)));
        assertFalse(PlacedLogTracker.isPlaced(log));
        place(log);
        when(log.getType()).thenReturn(Material.STONE);
        place(log);
        when(log.getType()).thenReturn(Material.OAK_LOG);
        assertFalse(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void naturalGrowthClearsOldPlacementOnlyWhenSuccessful() throws Exception {
        place(log);
        StructureGrowEvent event = mock(StructureGrowEvent.class);
        BlockState state = mock(BlockState.class);
        when(state.getBlock()).thenReturn(log);
        when(event.getBlocks()).thenReturn(List.of(state));
        when(event.isCancelled()).thenReturn(true);
        dispatch(tracker, event);
        assertTrue(PlacedLogTracker.isPlaced(log));
        when(event.isCancelled()).thenReturn(false);
        dispatch(tracker, event);
        assertFalse(PlacedLogTracker.isPlaced(log));
    }

    @Test
    void pistonChainPreservesPlacedAndNaturalOriginsAcrossChunkBoundary() throws Exception {
        Block first = world.block(15, 80, 0);
        Block second = world.block(16, 80, 0);
        Block third = world.block(17, 80, 0);
        place(first);
        place(third); // Stale destination marker must not infect a natural log.
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(first,
                List.of(first, second), BlockFace.EAST);
        dispatch(tracker, event);
        assertFalse(PlacedLogTracker.isPlaced(first));
        assertTrue(PlacedLogTracker.isPlaced(second));
        assertFalse(PlacedLogTracker.isPlaced(third));

        dispatch(tracker, new BlockPistonRetractEvent(first, List.of(second), BlockFace.WEST));
        assertTrue(PlacedLogTracker.isPlaced(first));
        assertFalse(PlacedLogTracker.isPlaced(second));
    }

    @Test
    void cancelledPistonDoesNotMoveMarker() throws Exception {
        place(log);
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(log, List.of(log), BlockFace.UP);
        event.setCancelled(true);
        dispatch(tracker, event);
        assertTrue(PlacedLogTracker.isPlaced(log));
        assertFalse(PlacedLogTracker.isPlaced(log.getRelative(BlockFace.UP)));
    }

    @Test
    void pistonBreakingFlowerDoesNotClearUnmovedLogBeyondIt() throws Exception {
        Block pushed = world.block(0, 80, 0);
        Block flower = world.block(1, 80, 0);
        Block untouched = world.block(2, 80, 0);
        when(flower.getType()).thenReturn(Material.DANDELION);
        when(flower.getPistonMoveReaction()).thenReturn(PistonMoveReaction.BREAK);
        place(pushed);
        place(untouched);
        dispatch(tracker, new BlockPistonExtendEvent(pushed, List.of(pushed, flower), BlockFace.EAST));
        when(flower.getType()).thenReturn(Material.OAK_LOG);
        assertTrue(PlacedLogTracker.isPlaced(flower));
        assertTrue(PlacedLogTracker.isPlaced(untouched));
        assertFalse(PlacedLogTracker.isPlaced(pushed));
    }

    @Test
    void fireAndExplosionsClearDestroyedLogs() throws Exception {
        place(log);
        BlockBurnEvent burn = mock(BlockBurnEvent.class);
        when(burn.getBlock()).thenReturn(log);
        dispatch(tracker, burn);
        assertFalse(PlacedLogTracker.isPlaced(log));
        place(log);
        BlockExplodeEvent blockExplosion = mock(BlockExplodeEvent.class);
        when(blockExplosion.blockList()).thenReturn(List.of(log));
        dispatch(tracker, blockExplosion);
        assertFalse(PlacedLogTracker.isPlaced(log));
        place(log);
        EntityExplodeEvent entityExplosion = mock(EntityExplodeEvent.class);
        when(entityExplosion.blockList()).thenReturn(List.of(log));
        dispatch(tracker, entityExplosion);
        assertFalse(PlacedLogTracker.isPlaced(log));
    }

    private void place(Block block) throws Exception {
        dispatch(tracker, placement(block));
    }

    static BlockPlaceEvent placement(Block block) {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlockPlaced()).thenReturn(block);
        when(event.canBuild()).thenReturn(true);
        return event;
    }

    private static void dispatch(PlacedLogTracker tracker, Event event) throws Exception {
        // Use Bukkit's dispatcher so ignoreCancelled is exercised, not bypassed.
        for (var method : PlacedLogTracker.class.getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler == null || !method.getParameterTypes()[0].isInstance(event)) {
                continue;
            }
            new RegisteredListener(tracker, (listener, delivered) -> {
                try {
                    method.invoke(listener, delivered);
                } catch (ReflectiveOperationException error) {
                    throw new EventException(error);
                }
            }, handler.priority(), mock(Plugin.class), handler.ignoreCancelled()).callEvent(event);
        }
    }
}

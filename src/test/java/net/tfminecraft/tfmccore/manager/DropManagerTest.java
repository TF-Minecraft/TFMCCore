package net.tfminecraft.tfmccore.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import net.tfminecraft.tfmccore.loader.DropLoader;
import net.tfminecraft.tfmccore.reference.Drop;

class DropManagerTest {

    @ParameterizedTest
    @EnumSource(value = Material.class, names = ".*_LEAVES", mode = EnumSource.Mode.MATCH_ALL)
    void placedLeavesNeverScheduleCustomDropsOrSuppressVanilla(Material material) {
        exerciseBreak(material, true);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = ".*_LEAVES", mode = EnumSource.Mode.MATCH_ALL)
    void treeGrownLeavesStillProduceCustomDrops(Material material) {
        // Naturally generated and sapling-grown trees both use persistent=false.
        exerciseBreak(material, false);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = {"OAK_LOG", "STONE", "WHEAT", "KELP"})
    void otherBlocksKeepTheirCustomDrops(Material material) {
        exerciseBreak(material, false);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = ".*(_LOG|_WOOD|_HYPHAE|CRIMSON_STEM|WARPED_STEM)",
            mode = EnumSource.Mode.MATCH_ALL)
    void placedLogsNeverScheduleCustomDropsOrSuppressVanilla(Material material) {
        exerciseBreak(material, true);
    }

    @ParameterizedTest
    @EnumSource(value = Material.class, names = ".*(_LOG|_WOOD|_HYPHAE|CRIMSON_STEM|WARPED_STEM)",
            mode = EnumSource.Mode.MATCH_ALL)
    void treeGrownLogsStillProduceCustomDrops(Material material) {
        exerciseBreak(material, false);
    }

    private void exerciseBreak(Material material, boolean persistent) {
        Block block = new LogTestWorld().block(0, 80, 0);
        when(block.getType()).thenReturn(material);
        if (persistent && !material.name().endsWith("_LEAVES")) {
            new PlacedLogTracker().onPlace(PlacedLogTrackerTest.placement(block));
        }
        if (material.name().endsWith("_LEAVES")) {
            Leaves leaves = mock(Leaves.class);
            when(leaves.isPersistent()).thenReturn(persistent);
            when(block.getBlockData()).thenReturn(leaves);
        } else {
            when(block.getBlockData()).thenReturn(mock(BlockData.class));
        }
        World world = mock(World.class);
        when(world.getName()).thenReturn("test");
        when(block.getWorld()).thenReturn(world);
        Location location = mock(Location.class);
        when(block.getLocation()).thenReturn(location);
        Block afterBreak = mock(Block.class);
        when(location.getBlock()).thenReturn(afterBreak);
        when(afterBreak.getType()).thenReturn(Material.AIR);

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack tool = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        ItemAPI itemApi = mock(ItemAPI.class);
        when(itemApi.getChecker()).thenReturn(mock(ItemChecker.class));

        Drop drop = mock(Drop.class);
        when(drop.appliesToMaterial(material)).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskLater(nullable(Plugin.class), any(Runnable.class), eq(5L)))
                .thenReturn(mock(BukkitTask.class));
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        try (var loader = mockStatic(DropLoader.class);
                var tlibs = mockStatic(TLibs.class);
                var bukkit = mockStatic(Bukkit.class)) {
            loader.when(DropLoader::get).thenReturn(List.of(drop));
            tlibs.when(TLibs::getItemAPI).thenReturn(itemApi);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

            new DropManager().blockBreak(event);

            assertFalse(event.isCancelled());
            if (persistent) {
                assertTrue(event.isDropItems(), "Vanilla harvesting must remain available");
                verifyNoInteractions(scheduler);
                verify(drop, never()).trigger(any(), any(), any(), any());
            } else {
                assertFalse(event.isDropItems(), "Matched table still suppresses vanilla drops");
                ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).runTaskLater(nullable(Plugin.class), task.capture(), eq(5L));
                task.getValue().run();
                verify(drop).trigger(player, block, tool, material);
            }
        }
    }
}

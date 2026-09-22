package net.tfminecraft.tfmccore.manager;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.loader.DropLoader;
import net.tfminecraft.tfmccore.reference.Drop;
import net.tfminecraft.tfmccore.reference.DropDebug;

public class DropManager implements Listener{
    @EventHandler
    public void blockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        ItemStack tool = p.getInventory().getItemInMainHand();
        Material original = b.getType();

        boolean materialMatch = false;
        for (Drop drop : DropLoader.get()) {
            if (drop.appliesToMaterial(original)) {
                materialMatch = true;
                break;
            }
        }
        if (!materialMatch) {
            return;
        }

        String toolPath = TLibs.getItemAPI().getChecker().getAsStringPath(tool);
        DropDebug.log("break " + p.getName()
                + " " + b.getWorld().getName()
                + " " + b.getX() + "," + b.getY() + "," + b.getZ()
                + " material=" + original
                + " tool=" + (toolPath == null || toolPath.isBlank() ? "empty" : toolPath)
                + " cancelled=" + e.isCancelled()
                + " isDropItems=" + e.isDropItems());

        for (Drop drop : DropLoader.get()) {
            if (!drop.appliesToMaterial(original)) {
                continue;
            }
            String skip = drop.skipReasonForBroken(p, tool, original);
            if (skip != null) {
                DropDebug.log("table " + drop.getId() + " skip: " + skip);
                continue;
            }

            boolean keepVanilla = drop.keepsVanillaDrops();
            boolean suppressed = false;
            if (!keepVanilla && e.isDropItems()) {
                e.setDropItems(false);
                suppressed = true;
            }
            DropDebug.log("table " + drop.getId()
                    + " match vanilla_drops=" + keepVanilla
                    + " suppressedVanilla=" + suppressed
                    + " scheduling trigger in 5 ticks");

            new BukkitRunnable() {
                public void run() {
                    Material now = b.getLocation().getBlock().getType();
                    if (now.equals(original)) {
                        DropDebug.log("table " + drop.getId()
                                + " skip trigger: delayed type still " + now
                                + " (snapshot " + original + ")");
                        return;
                    }
                    DropDebug.log("table " + drop.getId()
                            + " delayed proceed now=" + now
                            + " snapshot=" + original);
                    drop.trigger(p, b, tool, original);
                }
            }.runTaskLater(TFMCCore.getInstance(), 5L);
        }
    }
}

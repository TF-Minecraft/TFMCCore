package net.tfminecraft.tfmccore.golem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tfmccore.cache.Cache;

public class GolemListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onStatueScrape(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!isCopperGolemStatue(block.getType())) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack item = handItem(player, hand);
        boolean axe = isAxe(item);
        boolean spawnScrape = block.getType() == Material.COPPER_GOLEM_STATUE && axe;
        boolean shouldBlock = Cache.preventGolemScrape && spawnScrape;

        log("statue"
            + " player=" + player.getName()
            + " hand=" + hand
            + " block=" + block.getType()
            + " item=" + itemType(item)
            + " axe=" + axe
            + " prevent=" + Cache.preventGolemScrape
            + " spawnScrape=" + spawnScrape
            + " cancelledBefore=" + (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY)
            + " willCancel=" + shouldBlock);

        if (shouldBlock) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onGolemEntityInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof CopperGolem golem)) return;
        Player player = event.getPlayer();
        ItemStack item = handItem(player, event.getHand());
        log("entity"
            + " player=" + player.getName()
            + " hand=" + event.getHand()
            + " weather=" + golem.getWeatheringState()
            + " item=" + itemType(item)
            + " axe=" + isAxe(item)
            + " cancelled=" + event.isCancelled());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onCopperGolemSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof CopperGolem golem)) return;
        boolean reanimate = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.REANIMATE;
        boolean shouldBlock = Cache.preventGolemScrape && reanimate;
        log("spawn"
            + " reason=" + event.getSpawnReason()
            + " weather=" + golem.getWeatheringState()
            + " prevent=" + Cache.preventGolemScrape
            + " cancelledBefore=" + event.isCancelled()
            + " willCancel=" + shouldBlock
            + " loc=" + formatLoc(event.getLocation().getBlock()));
        if (shouldBlock) {
            event.setCancelled(true);
        }
    }

    private static boolean isCopperGolemStatue(Material type) {
        return type == Material.COPPER_GOLEM_STATUE
            || type == Material.EXPOSED_COPPER_GOLEM_STATUE
            || type == Material.WEATHERED_COPPER_GOLEM_STATUE
            || type == Material.OXIDIZED_COPPER_GOLEM_STATUE
            || type == Material.WAXED_COPPER_GOLEM_STATUE
            || type == Material.WAXED_EXPOSED_COPPER_GOLEM_STATUE
            || type == Material.WAXED_WEATHERED_COPPER_GOLEM_STATUE
            || type == Material.WAXED_OXIDIZED_COPPER_GOLEM_STATUE;
    }

    private static boolean isAxe(ItemStack item) {
        return item != null && Tag.ITEMS_AXES.isTagged(item.getType());
    }

    private static ItemStack handItem(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }

    private static String itemType(ItemStack item) {
        return item == null ? "AIR" : item.getType().name();
    }

    private static String formatLoc(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static void log(String message) {
        Bukkit.getLogger().info("[TFMCCore] golem-scrape " + message);
    }
}

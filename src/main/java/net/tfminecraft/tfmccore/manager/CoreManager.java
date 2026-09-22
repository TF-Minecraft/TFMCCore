package net.tfminecraft.tfmccore.manager;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.armour.ArmorEquipEvent;
import net.tfminecraft.tfmccore.cache.Cache;

public class CoreManager implements Listener{
    @EventHandler
	public void preventBoneMeal(PlayerInteractEvent e) {
        if(Cache.allowBoneMeal) return;
		if(!e.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
		Player p = e.getPlayer();
		ItemStack item = p.getInventory().getItemInMainHand();
		if((e.getClickedBlock().getBlockData() instanceof Ageable)) {
			if(TLibs.getItemAPI().getChecker().checkItemWithPath(item, "v.bone_meal")) {
                e.setCancelled(true);
                return;
            }
		}
	}

    @EventHandler
	public void equipArmor(ArmorEquipEvent e) {
		Player p = e.getPlayer();
		PotionEffect weak = new PotionEffect(PotionEffectType.WEAKNESS, (int) Math.floor(Cache.armourTime*20), 2, false, false);
		p.addPotionEffect(weak);
	}

    @EventHandler
	public void blockShield(EntityDamageByEntityEvent e) {
        if(!Cache.limitShields) return;
		if(e.getEntity() instanceof Player ) {
		    Player player = (Player) e.getEntity();
		    if(player.isBlocking() == true) {
		        player.damage(e.getDamage());
		        e.setCancelled(true);
		    }
		}
	}

    @EventHandler
	public void brewEvent(BrewEvent e) {
        if(Cache.allowBrewing) return;
		e.setCancelled(true);
	}

	@EventHandler
	public void enchantEvent(PlayerInteractEvent e) {
        if(Cache.allowEnchanting) return;
		if(e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
		if(e.getClickedBlock().getType() != Material.ENCHANTING_TABLE) return;
		e.setCancelled(true);
	}

    @EventHandler
	public void craftEvent(PrepareItemCraftEvent e) {
		ItemStack result = e.getInventory().getResult();
		if(result == null) return;
		if(Cache.blockedCrafts.contains(result.getType())) {
			e.getInventory().setResult(new ItemStack(Material.AIR, 1));
		}
	}

    @EventHandler
	public void blockConsume(PlayerItemConsumeEvent e) {
		if(Cache.blockedConsume.contains(e.getItem().getType())) {
			e.setCancelled(true);
			e.getPlayer().sendMessage("§cCannot consume this item!");
		}
	}
	@EventHandler
	public void stopHorseArcher(EntityShootBowEvent e) {
        if(Cache.horseArchery) return;
		if(e.getEntity() instanceof Player) {
			Player p = (Player) e.getEntity();
			if(p.isInsideVehicle()) {
				e.setCancelled(true);
			}
		}
	}
}

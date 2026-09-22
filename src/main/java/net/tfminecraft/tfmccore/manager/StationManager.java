package net.tfminecraft.tfmccore.manager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tfmccore.loader.StationLoader;
import net.tfminecraft.tfmccore.reference.Station;

public class StationManager implements Listener {
	@EventHandler
	public void openStationEvent(PlayerInteractEvent e) {
		if (e.getHand() != null && e.getHand() != EquipmentSlot.HAND) {
			return;
		}
		if (!e.getAction().equals(Action.RIGHT_CLICK_BLOCK) || e.getClickedBlock() == null) {
			return;
		}
		Player p = e.getPlayer();
		boolean sneaking = p.isSneaking();
		for (Station station : StationLoader.get()) {
			if (!TLibs.getBlockAPI().getChecker().checkBlock(e.getClickedBlock(), station.getBlock())) {
				continue;
			}
			if (!station.matchesClick(sneaking)) {
				return;
			}
			openStation(p, station.getId());
			e.setCancelled(true);
			return;
		}
	}

	private void openStation(Player p, String station) {
		Bukkit.dispatchCommand(
				Bukkit.getConsoleSender(),
				"mi stations open " + station + " " + p.getName());
	}
}

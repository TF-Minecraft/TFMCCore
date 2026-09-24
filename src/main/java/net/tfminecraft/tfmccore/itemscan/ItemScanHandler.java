package net.tfminecraft.tfmccore.itemscan;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** @deprecated Subscribe through TLibs directly in new consumers. */
@Deprecated
public interface ItemScanHandler extends net.tfminecraft.tlibs.itemscan.ItemScanHandler {

    boolean matches(ItemStack stack);

    void update(Player player, Inventory inventory, int slot, ItemStack stack);
}

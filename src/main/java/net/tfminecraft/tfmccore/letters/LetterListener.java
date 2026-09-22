package net.tfminecraft.tfmccore.letters;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.util.TextUtil;

public class LetterListener implements Listener {

    private final LetterItems items = new LetterItems();

    @EventHandler
    public void onBookSign(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        // Read from the slot the event names, not the main hand - books can be signed from the off-hand.
        // No replacement exposes the originating slot; retain it for deferred book restoration.
        @SuppressWarnings("removal")
        int slot = event.getSlot();
        if (slot < 0 || slot >= player.getInventory().getSize()) return;
        ItemStack handItem = player.getInventory().getItem(slot);
        if (!items.isLetter(handItem)) return;
        if (!event.isSigning()) {
            // Vanilla writes a plain book and quill when the editor closes. Put the
            // unsigned letter back two ticks later, after ArmourShop's one-tick skin restore.
            BookMeta edited = event.getNewBookMeta();
            ItemStack previous = handItem.clone();
            Bukkit.getScheduler().runTaskLater(TFMCCore.getInstance(), () -> {
                if (!player.isOnline()) return;
                ItemStack restored = items.createEditedLetter(edited, previous);
                if (restored == null) {
                    warn("Failed to restore edited letter for " + player.getName());
                    return;
                }
                player.getInventory().setItem(slot, restored);
            }, 2L);
            return;
        }
        // Cancel so the vanilla written book is never produced - we hand out our own item instead.
        event.setCancelled(true);

        ItemStack sealed = items.createSealedLetter(event.getNewBookMeta(), player);
        if (sealed == null) {
            warn("Failed to create sealed letter for " + player.getName());
            sendMessage(player, LetterConfig.creationFailedMessage);
            return;
        }
        // The next-tick hop is required - setting the item inside the cancelled event does not stick.
        Bukkit.getScheduler().runTask(TFMCCore.getInstance(), () -> {
            player.getInventory().setItem(slot, sealed);
            sendMessage(player, LetterConfig.signedMessage);
        });
    }

    @EventHandler
    public void onBookOpen(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked != null && (clicked.getType() == Material.CHISELED_BOOKSHELF || clicked.getType() == Material.LECTERN)) return;
        // No guard on which hand was used - off-hand letters open too - but the swap below
        // must go back into that same hand, or the other hand's item gets overwritten.
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        ItemStack item = event.getItem();
        if (!items.isSealedLetter(item)) return;
        BookMeta current = (BookMeta) item.getItemMeta();
        if (current == null) return;

        Player player = event.getPlayer();
        ItemStack opened = items.createOpenedLetter(current);
        if (opened == null) {
            warn("Failed to create opened letter for " + player.getName());
            sendMessage(player, LetterConfig.openFailedMessage);
            return;
        }
        // Not cancelled - the book still opens for reading, so swap the item on the next tick.
        Bukkit.getScheduler().runTask(TFMCCore.getInstance(), () -> {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(opened);
            } else {
                player.getInventory().setItemInMainHand(opened);
            }
            sendMessage(player, LetterConfig.openedMessage);
        });
    }

    private void sendMessage(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        player.sendMessage(TextUtil.color(message));
    }

    private void warn(String message) {
        TFMCCore instance = TFMCCore.getInstance();
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }
}

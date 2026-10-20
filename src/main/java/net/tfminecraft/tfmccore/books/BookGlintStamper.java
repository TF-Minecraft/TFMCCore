package net.tfminecraft.tfmccore.books;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Hides the enchantment glint signed books have by default by storing the override on the
 * books themselves. Clients report a hash of every stack they click; a glint changed only in
 * packets never matches the server's book, and the resync that follows can arrive after the
 * next click and leave a ghost copy on the cursor. Books this sets are tagged, so turning the
 * option off puts the glint back on only those.
 */
public final class BookGlintStamper implements Listener {

    static final long SWEEP_TICKS = 20;
    /** Book handlers compare the book with their own copy a tick later; leave it alone meanwhile. */
    static final int QUIET_TICKS = 5;

    static final NamespacedKey KEY = new NamespacedKey("tfmccore", "hidden_glint");

    private final Map<UUID, Integer> quietUntil = new HashMap<>();

    public static BookGlintStamper register(JavaPlugin plugin) {
        BookGlintStamper stamper = new BookGlintStamper();
        plugin.getServer().getPluginManager().registerEvents(stamper, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, stamper::sweep, SWEEP_TICKS, SWEEP_TICKS);
        return stamper;
    }

    /** Brings online players' own inventories in line with the option. */
    void sweep() {
        int now = Bukkit.getCurrentTick();
        quietUntil.values().removeIf(until -> until <= now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!quietUntil.containsKey(player.getUniqueId())) update(player.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (isStorage(top)) update(top);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEdit(PlayerEditBookEvent event) {
        quiet(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.WRITTEN_BOOK) quiet(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        quietUntil.remove(event.getPlayer().getUniqueId());
    }

    private void quiet(Player player) {
        quietUntil.put(player.getUniqueId(), Bukkit.getCurrentTick() + QUIET_TICKS);
    }

    /** Containers in the world. Plugin menus may match their own icons exactly, so skip them. */
    static boolean isStorage(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        // A lectern resets to the first page when its book is set
        if (holder instanceof Lectern) return false;
        // Menus are often opened with the player as holder; of a player's own, only the ender chest
        if (holder instanceof HumanEntity human) return inventory.equals(human.getEnderChest());
        return holder instanceof BlockInventoryHolder || holder instanceof DoubleChest || holder instanceof Entity;
    }

    void update(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack updated = updated(contents[slot]);
            if (updated != null) inventory.setItem(slot, updated);
        }
    }

    /** A copy of a signed book matching the option, or null when nothing changes. */
    ItemStack updated(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.has(KEY, PersistentDataType.BYTE)) {
            if (Cache.hideBookGlint) return null;
            data.remove(KEY);
            if (meta.hasEnchantmentGlintOverride() && !meta.getEnchantmentGlintOverride()) {
                meta.setEnchantmentGlintOverride(null);
            }
        } else {
            // Glint true is the written book default and is never stored, so only a book
            // that already turned it off has an override
            if (!Cache.hideBookGlint || meta.hasEnchantmentGlintOverride()) return null;
            meta.setEnchantmentGlintOverride(false);
            data.set(KEY, PersistentDataType.BYTE, (byte) 1);
        }
        ItemStack copy = item.clone();
        copy.setItemMeta(meta);
        return copy;
    }
}

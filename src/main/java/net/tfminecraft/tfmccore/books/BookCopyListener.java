package net.tfminecraft.tfmccore.books;

import java.util.function.Predicate;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Lets players copy written books at a crafting table. MIReplacer makes every Book and
 * Quill an MMOItem with disable-crafting, and MMOItems cancels any vanilla recipe that
 * uses one, so the copy showed but could not be taken. Only that cancel is undone: the
 * craft must be uncancelled before MMOItems' NORMAL handler, cancelled after it, and use
 * an item with MMOItems' disable-crafting tag. HIGHEST handlers can still cancel it.
 */
public final class BookCopyListener implements Listener {

    static final NamespacedKey BOOK_CLONING = NamespacedKey.minecraft("book_cloning");
    static final String DISABLE_CRAFTING = "MMOITEMS_DISABLE_CRAFTING";

    private final Predicate<ItemStack> craftingDisabled;
    private CraftItemEvent checked;
    private boolean cancelledBefore;

    BookCopyListener(Predicate<ItemStack> craftingDisabled) {
        this.craftingDisabled = craftingDisabled;
    }

    public static void register(JavaPlugin plugin) {
        // MMOItems needs MythicLib, so without it nothing cancels book copies
        if (!plugin.getServer().getPluginManager().isPluginEnabled("MythicLib")) return;
        plugin.getServer().getPluginManager().registerEvents(
                new BookCopyListener(BookCopyListener::mmoItemsDisablesCrafting), plugin);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void beforeMmoItems(CraftItemEvent event) {
        if (!Cache.allowBookCopies) return;
        if (!(event.getRecipe() instanceof Keyed keyed) || !BOOK_CLONING.equals(keyed.getKey())) return;
        checked = event;
        cancelledBefore = event.isCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void afterMmoItems(CraftItemEvent event) {
        if (checked != event) return;
        checked = null;
        if (cancelledBefore || !event.isCancelled()) return;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item != null && !item.isEmpty() && craftingDisabled.test(item)) {
                event.setCancelled(false);
                return;
            }
        }
    }

    static boolean mmoItemsDisablesCrafting(ItemStack item) {
        return NBTItem.get(item).getBoolean(DISABLE_CRAFTING);
    }
}

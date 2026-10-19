package net.tfminecraft.tfmccore.books;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tfmccore.cache.Cache;

/**
 * Lets players copy written books at a crafting table. MIReplacer makes every Book and
 * Quill an MMOItem with disable-crafting, and MMOItems cancels any vanilla recipe that
 * uses one, so the copy showed but could not be taken. MMOItems' own click handlers are
 * wrapped so that only a book copy cancel made by that handler call is undone; cancels
 * from every other plugin, at any priority, stay.
 */
public final class BookCopyUnblocker {

    static final NamespacedKey BOOK_CLONING = NamespacedKey.minecraft("book_cloning");
    static final String MMOITEMS_LISTENER = "net.Indyuce.mmoitems.listener.DisableInteractions";

    private BookCopyUnblocker() {
    }

    public static void register(JavaPlugin plugin) {
        // MMOItems is not a dependency, so it may enable after TFMCCore: wait until startup ends
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("MMOItems")) return;
            int wrapped = wrap(InventoryClickEvent.getHandlerList());
            if (wrapped == 0) {
                plugin.getLogger().warning("MMOItems craft handler not found; book copies stay blocked");
            } else {
                plugin.getLogger().info("Book copies: wrapped " + wrapped + " MMOItems click handlers");
            }
        });
    }

    /** Replaces each MMOItems DisableInteractions registration with one that undoes its book copy cancels. */
    static int wrap(HandlerList handlers) {
        int wrapped = 0;
        for (RegisteredListener original : handlers.getRegisteredListeners()) {
            if (!original.getListener().getClass().getName().equals(MMOITEMS_LISTENER)) continue;
            handlers.unregister(original);
            handlers.register(new RegisteredListener(original.getListener(), executor(original),
                    original.getPriority(), original.getPlugin(), original.isIgnoringCancelled()));
            wrapped++;
        }
        return wrapped;
    }

    static EventExecutor executor(RegisteredListener original) {
        return (listener, event) -> {
            boolean copy = Cache.allowBookCopies && isBookCopy(event);
            boolean cancelledBefore = copy && ((Cancellable) event).isCancelled();
            original.callEvent(event);
            if (copy && !cancelledBefore && ((Cancellable) event).isCancelled()) {
                ((Cancellable) event).setCancelled(false);
            }
        };
    }

    private static boolean isBookCopy(Event event) {
        return event instanceof CraftItemEvent craft
                && craft.getRecipe() instanceof Keyed keyed
                && BOOK_CLONING.equals(keyed.getKey());
    }
}

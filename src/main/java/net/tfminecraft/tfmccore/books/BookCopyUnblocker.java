package net.tfminecraft.tfmccore.books;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.server.PluginEnableEvent;
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
public final class BookCopyUnblocker implements Listener {

    static final NamespacedKey BOOK_CLONING = NamespacedKey.minecraft("book_cloning");
    static final String MMOITEMS_LISTENER = "net.Indyuce.mmoitems.listener.DisableInteractions";

    private final JavaPlugin plugin;
    private final HandlerList handlers;
    private final List<Wrapped> wrapped = new ArrayList<>();

    BookCopyUnblocker(JavaPlugin plugin, HandlerList handlers) {
        this.plugin = plugin;
        this.handlers = handlers;
    }

    public static BookCopyUnblocker register(JavaPlugin plugin) {
        BookCopyUnblocker unblocker = new BookCopyUnblocker(plugin, InventoryClickEvent.getHandlerList());
        plugin.getServer().getPluginManager().registerEvents(unblocker, plugin);
        // MMOItems is not a dependency: it registers its handlers in its own onEnable, before or after ours
        if (plugin.getServer().getPluginManager().isPluginEnabled("MMOItems")) unblocker.wrapAndLog();
        return unblocker;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("MMOItems")) wrapAndLog();
    }

    void wrapAndLog() {
        int count = wrap();
        if (wrapped.isEmpty()) {
            plugin.getLogger().warning("MMOItems craft handler not found; book copies stay blocked");
        } else if (count > 0) {
            plugin.getLogger().info("Book copies: wrapped " + count + " MMOItems click handlers");
        }
    }

    /** Replaces each unwrapped MMOItems DisableInteractions registration with one that undoes its book copy cancels. */
    int wrap() {
        int count = 0;
        for (RegisteredListener original : handlers.getRegisteredListeners()) {
            if (original instanceof Wrapped
                    || !original.getListener().getClass().getName().equals(MMOITEMS_LISTENER)) continue;
            Wrapped wrapper = new Wrapped(original);
            replace(original, wrapper);
            wrapped.add(wrapper);
            count++;
        }
        return count;
    }

    /**
     * Swaps one registration for another in the same place. HandlerList only appends, so the
     * listeners after it in its priority slot are taken out and added back behind the replacement.
     */
    private void replace(RegisteredListener old, RegisteredListener replacement) {
        List<RegisteredListener> later = new ArrayList<>();
        boolean found = false;
        for (RegisteredListener registered : handlers.getRegisteredListeners()) {
            if (registered == old) {
                found = true;
            } else if (found && registered.getPriority() == old.getPriority()) {
                later.add(registered);
            }
        }
        handlers.unregister(old);
        later.forEach(handlers::unregister);
        handlers.register(replacement);
        later.forEach(handlers::register);
    }

    /** Puts MMOItems' own registrations back, so a TFMCCore disable or reload leaves nothing of ours behind. */
    public void unwrap() {
        List<RegisteredListener> current = Arrays.asList(handlers.getRegisteredListeners());
        for (Wrapped wrapper : wrapped) {
            // Gone when MMOItems was disabled meanwhile; its original must not come back then
            if (!current.contains(wrapper)) continue;
            replace(wrapper, wrapper.original);
        }
        wrapped.clear();
    }

    static final class Wrapped extends RegisteredListener {
        final RegisteredListener original;

        Wrapped(RegisteredListener original) {
            super(original.getListener(), executor(original), original.getPriority(),
                    original.getPlugin(), original.isIgnoringCancelled());
            this.original = original;
        }
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

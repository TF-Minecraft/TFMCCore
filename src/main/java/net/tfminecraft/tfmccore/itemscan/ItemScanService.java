package net.tfminecraft.tfmccore.itemscan;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.tfminecraft.tfmccore.TFMCCore;

/** Compatibility entry point for plugins compiled against Core 2.0.x. */
@Deprecated
public final class ItemScanService {
    private static ItemScanService instance;
    private final net.tfminecraft.tlibs.itemscan.ItemScanService delegate;
    private final Set<ItemScanHandler> handlers = ConcurrentHashMap.newKeySet();

    private ItemScanService(net.tfminecraft.tlibs.itemscan.ItemScanService delegate) {
        this.delegate = delegate;
    }

    public static ItemScanService get() {
        var service = net.tfminecraft.tlibs.itemscan.ItemScanService.get();
        if (service == null) return null;
        if (instance == null || instance.delegate != service) {
            instance = new ItemScanService(service);
        }
        return instance;
    }

    /** TLibs owns startup; retained only for binary compatibility. */
    public static void start(TFMCCore plugin) {
        get();
    }

    /** Remove legacy subscriptions without stopping other plugins' scanner. */
    public void stop() {
        handlers.forEach(delegate::unsubscribe);
        handlers.clear();
    }

    public void subscribe(ItemScanHandler handler) {
        if (handler != null && handlers.add(handler)) delegate.subscribe(handler);
    }

    public void unsubscribe(ItemScanHandler handler) {
        if (handler != null && handlers.remove(handler)) delegate.unsubscribe(handler);
    }
}

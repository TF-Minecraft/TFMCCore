package net.tfminecraft.tfmccore.resourcepack;

import com.google.gson.JsonParser;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ResourcePackListener implements Listener {
    private final Logger logger;

    public ResourcePackListener(Logger logger) { this.logger = logger; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void compactOverlays(ItemsAdderPackCompressedEvent event) {
        if (!Cache.compactResourcePackOverlays) return;
        try {
            var metadata = event.getEntry("pack.mcmeta");
            if (metadata == null || metadata.isRemoved()) return;
            TreeSet<String> paths = new TreeSet<>();
            for (Object key : event.getEntries().keySet()) {
                String path = (String) key;
                if (!event.getEntry(path).isRemoved()) paths.add(path);
            }
            var result = OverlayCompactor.plan(JsonParser.parseString(metadata.getText()).getAsJsonObject(), paths);
            if (result.isEmpty()) return;
            var plan = result.get();
            // Resolve all sources first. An unreadable entry leaves the original pack intact.
            Map<String, byte[]> sourceBytes = new LinkedHashMap<>();
            for (String source : plan.copies().values()) {
                if (!sourceBytes.containsKey(source)) sourceBytes.put(source, event.getEntry(source).getBytes());
            }
            String updatedMetadata = plan.metadata().toString();
            plan.removals().forEach(event::removeEntry);
            plan.copies().forEach((destination, source) -> event.setEntry(destination, sourceBytes.get(source)));
            event.setTextEntry("pack.mcmeta", updatedMetadata);
            logger.info("Compacted ItemsAdder compatibility overlays below format 75; "
                    + plan.removals().size() + " old entries replaced by " + plan.copies().size() + " entries.");
        } catch (RuntimeException exception) {
            logger.warning("Resource-pack overlay compaction skipped: " + exception.getMessage());
        }
    }
}

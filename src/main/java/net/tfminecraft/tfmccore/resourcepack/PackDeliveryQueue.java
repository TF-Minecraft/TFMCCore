package net.tfminecraft.tfmccore.resourcepack;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Server-thread queue for original IA requests waiting for exact-generation publication. */
final class PackDeliveryQueue implements AutoCloseable {
    private record Pending(Player player, String hash, boolean required, Component prompt, Runnable fallback, long deadline) {}
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final MultipartPackService service;
    private final LongSupplier clock;

    PackDeliveryQueue(MultipartPackService service, LongSupplier clock) { this.service = service; this.clock = clock; }

    void add(Player player, String hash, boolean required, Component prompt, Runnable fallback) {
        pending.put(player.getUniqueId(), new Pending(player, hash, required, prompt, fallback,
                clock.getAsLong() + TimeUnit.SECONDS.toNanos(60)));
    }

    /** Wait for an active reload to finish; never substitute an older published pack. */
    void drain() {
        for (Pending request : List.copyOf(pending.values())) {
            UUID id = request.player().getUniqueId();
            if (!request.player().isOnline()) { pending.remove(id, request); continue; }
            if (service.loading(id)) continue;
            if (service.sendPublished(request.player(), request.hash(), request.required(), request.prompt(), request.fallback())) {
                pending.remove(id, request);
            } else if (clock.getAsLong() >= request.deadline()) {
                pending.remove(id, request);
                request.fallback().run();
            }
        }
    }

    void quit(UUID player) { pending.remove(player); }

    @Override public void close() {
        List<Pending> waiting = List.copyOf(pending.values());
        pending.clear();
        waiting.stream().filter(request -> request.player().isOnline()).forEach(request -> request.fallback().run());
    }
}

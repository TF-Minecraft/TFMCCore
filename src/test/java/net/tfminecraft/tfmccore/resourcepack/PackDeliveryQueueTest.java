package net.tfminecraft.tfmccore.resourcepack;

import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class PackDeliveryQueueTest {
    private final UUID playerId = UUID.randomUUID();
    private final String hash = "a".repeat(40);
    private final MultipartPackService service = mock(MultipartPackService.class);
    private final Player player = mock(Player.class);
    private final Runnable fallback = mock(Runnable.class);
    private final AtomicLong clock = new AtomicLong();
    private final PackDeliveryQueue queue = new PackDeliveryQueue(service, clock::get);

    @BeforeEach void setup() {
        when(player.getUniqueId()).thenReturn(playerId); when(player.isOnline()).thenReturn(true);
    }

    @Test void waitsForRequestedGenerationAndPreservesRequiredFlagAndPrompt() {
        Component prompt = Component.text("Server textures");
        queue.add(player, hash, true, prompt, fallback);
        queue.drain(); verifyNoInteractions(fallback);
        when(service.sendPublished(player, hash, true, prompt, fallback)).thenReturn(true);
        queue.drain(); queue.drain();
        verify(service, times(2)).sendPublished(player, hash, true, prompt, fallback);
    }

    @Test void newerRebuildSupersedesWaitingRequestAndWaitsForExistingReload() {
        queue.add(player, hash, false, null, fallback);
        String newer = "b".repeat(40); queue.add(player, newer, false, null, fallback);
        when(service.loading(playerId)).thenReturn(true); clock.set(TimeUnit.SECONDS.toNanos(61)); queue.drain();
        verify(service, never()).sendPublished(any(), any(), anyBoolean(), any(), any()); verifyNoInteractions(fallback);
        when(service.loading(playerId)).thenReturn(false);
        when(service.sendPublished(player, newer, false, null, fallback)).thenReturn(true); queue.drain();
        verify(service).sendPublished(player, newer, false, null, fallback); verifyNoInteractions(fallback);
    }

    @Test void unpublishedPackFallsBackOnce() {
        queue.add(player, hash, false, null, fallback); clock.set(TimeUnit.SECONDS.toNanos(61));
        queue.drain(); queue.drain(); verify(fallback, times(1)).run();
    }

    @Test void multipartFailureKeepsOriginalFallback() {
        queue.add(player, hash, false, null, fallback);
        when(service.sendPublished(player, hash, false, null, fallback)).thenAnswer(call -> {
            call.getArgument(4, Runnable.class).run(); return true;
        });
        queue.drain(); queue.drain(); verify(fallback, times(1)).run();
    }

    @Test void quitAndOfflinePlayersDiscardQueuedDelivery() {
        queue.add(player, hash, false, null, fallback); queue.quit(playerId); queue.drain(); verifyNoInteractions(service);
        queue.add(player, hash, false, null, fallback); when(player.isOnline()).thenReturn(false);
        queue.drain(); queue.close(); verifyNoInteractions(service, fallback);
    }

    @Test void shutdownReleasesOriginalOnce() {
        queue.add(player, hash, false, null, fallback); queue.close(); queue.close();
        verify(fallback, times(1)).run();
    }
}

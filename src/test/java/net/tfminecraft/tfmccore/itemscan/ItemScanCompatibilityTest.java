package net.tfminecraft.tfmccore.itemscan;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class ItemScanCompatibilityTest {
    @Test
    void legacySubscribersShareProviderAndUnsubscribeWithoutStoppingIt() {
        var provider = mock(net.tfminecraft.tlibs.itemscan.ItemScanService.class);
        try (var lookup = mockStatic(net.tfminecraft.tlibs.itemscan.ItemScanService.class)) {
            lookup.when(net.tfminecraft.tlibs.itemscan.ItemScanService::get).thenReturn(provider);
            var legacy = ItemScanService.get();
            assertSame(legacy, ItemScanService.get());
            var handler = mock(ItemScanHandler.class);
            legacy.subscribe(handler);
            legacy.subscribe(handler);
            legacy.subscribe(null);
            verify(provider, times(1)).subscribe(handler);
            legacy.unsubscribe(handler);
            legacy.unsubscribe(handler);
            verify(provider, times(1)).unsubscribe(handler);
            legacy.subscribe(handler);
            legacy.stop();
            verify(provider, times(2)).unsubscribe(handler);
            verify(provider, never()).stop();
        }
    }

    @Test
    void legacyStartDoesNotCreateASecondScanner() {
        var provider = mock(net.tfminecraft.tlibs.itemscan.ItemScanService.class);
        try (var lookup = mockStatic(net.tfminecraft.tlibs.itemscan.ItemScanService.class)) {
            lookup.when(net.tfminecraft.tlibs.itemscan.ItemScanService::get).thenReturn(provider);
            ItemScanService.start(null);
            lookup.verify(net.tfminecraft.tlibs.itemscan.ItemScanService::get);
            lookup.verifyNoMoreInteractions();
        }
    }

    @Test
    void providerRestartGetsFreshCompatibilityAdapter() {
        try (var lookup = mockStatic(net.tfminecraft.tlibs.itemscan.ItemScanService.class)) {
            lookup.when(net.tfminecraft.tlibs.itemscan.ItemScanService::get)
                    .thenReturn(mock(net.tfminecraft.tlibs.itemscan.ItemScanService.class));
            var previous = ItemScanService.get();
            lookup.when(net.tfminecraft.tlibs.itemscan.ItemScanService::get).thenReturn(null);
            assertNull(ItemScanService.get());
            lookup.when(net.tfminecraft.tlibs.itemscan.ItemScanService::get)
                    .thenReturn(mock(net.tfminecraft.tlibs.itemscan.ItemScanService.class));
            assertNotSame(previous, ItemScanService.get());
        }
    }
}

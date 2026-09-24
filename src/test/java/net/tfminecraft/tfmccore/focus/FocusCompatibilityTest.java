package net.tfminecraft.tfmccore.focus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class FocusCompatibilityTest {
    @Test
    void releasedConsumerCallsUseTheCharacterOwnersService() {
        var provider = mock(net.tfminecraft.rpcharacters.focus.FocusService.class);
        var player = mock(Player.class);
        var legacy = new FocusService(provider);
        when(provider.getPoints(player)).thenReturn(37);
        when(provider.getMax()).thenReturn(200);
        when(provider.trySpend(player, 12)).thenReturn(true);
        when(provider.restore(player)).thenReturn(true);
        assertEquals(37, legacy.getPoints(player));
        assertEquals(200, legacy.getMax());
        assertTrue(legacy.trySpend(player, 12));
        assertTrue(legacy.restore(player));
        legacy.grant(player, 4);
        verify(provider).grant(player, 4);
    }

    @Test
    void legacyConfigReadsReflectTheOwnersConfiguration() {
        int before = net.tfminecraft.rpcharacters.focus.FocusConfig.max;
        try {
            net.tfminecraft.rpcharacters.focus.FocusConfig.max = 321;
            FocusConfig.refresh();
            assertEquals(321, FocusConfig.max);
        } finally {
            net.tfminecraft.rpcharacters.focus.FocusConfig.max = before;
            FocusConfig.refresh();
        }
    }
    @Test
    void legacyEntryPointRecoversWhenProviderBecomesAvailable() {
        var state = new java.util.concurrent.atomic.AtomicReference<net.tfminecraft.rpcharacters.focus.FocusService>();
        assertNull(FocusService.resolve(state::get));
        var provider = mock(net.tfminecraft.rpcharacters.focus.FocusService.class);
        state.set(provider);
        var recovered = FocusService.resolve(state::get);
        assertNotNull(recovered);
        recovered.getMax();
        verify(provider).getMax();
        assertNull(FocusService.resolve(() -> { throw new NoSuchMethodError("old RPCharacters"); }));
    }
}

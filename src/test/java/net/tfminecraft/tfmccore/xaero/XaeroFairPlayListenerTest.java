package net.tfminecraft.tfmccore.xaero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.tfminecraft.tfmccore.cache.Cache;

class XaeroFairPlayListenerTest {
    private final Plugin plugin = mock(Plugin.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final Player player = mock(Player.class);

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.isOnline()).thenReturn(true);
        Cache.xaeroFairPlay = true;
    }

    @AfterEach
    void reset() {
        Cache.xaeroFairPlay = true;
    }

    @Test
    void sendsRawFairPlayCodeShortlyAfterEveryJoin() {
        new XaeroFairPlayListener(plugin).onJoin(new PlayerJoinEvent(player, (Component) null));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), task.capture(), eq(20L));
        verify(player, never()).sendMessage(any(Component.class));
        task.getValue().run();

        verify(player).sendMessage(XaeroFairPlayListener.FAIR_PLAY);
        TextComponent sent = (TextComponent) XaeroFairPlayListener.FAIR_PLAY;
        assertEquals("§f§a§i§r§x§a§e§r§o", sent.content());
        assertEquals(0, sent.children().size());
    }

    @Test
    void skipsPlayersWhoLeftBeforeTheDelay() {
        when(player.isOnline()).thenReturn(false);
        XaeroFairPlayListener.send(player);
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    void configCanTurnItOff() {
        Cache.xaeroFairPlay = false;
        XaeroFairPlayListener.send(player);
        verify(player, never()).sendMessage(any(Component.class));
    }
}

package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class RequiredPackGateTest {
  private final JavaPlugin plugin = mock(JavaPlugin.class);
  private final Server server = mock(Server.class);
  private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
  private final Logger logger = mock(Logger.class);
  private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
  private final List<Player> sends = new ArrayList<>();
  private final List<Consumer<Boolean>> completions = new ArrayList<>();
  private final UUID playerId = UUID.randomUUID();
  private final Player player = player();
  private BiConsumer<Player, Consumer<Boolean>> delivery =
      (target, done) -> {
        sends.add(target);
        completions.add(done);
      };
  private RequiredPackGate gate;
  @org.junit.jupiter.api.io.TempDir Path temp;

  @BeforeEach
  void setUp() {
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getScheduler()).thenReturn(scheduler);
    when(server.getPlayer(playerId)).thenReturn(player);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              tasks.add(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    gate = new RequiredPackGate(plugin, (target, done) -> delivery.accept(target, done), null);
  }

  private Player player() {
    Player value = mock(Player.class);
    when(value.getUniqueId()).thenReturn(playerId);
    when(value.getName()).thenReturn("Ada");
    when(value.isOnline()).thenReturn(true);
    return value;
  }

  private void join(Player target) {
    gate.join(new PlayerJoinEvent(target, Component.empty()));
  }

  private void quit(Player target) {
    gate.quit(new PlayerQuitEvent(target, Component.empty()));
  }

  /** Every metadata value written for this player, in order. */
  private List<String> states(Player target) {
    ArgumentCaptor<MetadataValue> values = ArgumentCaptor.forClass(MetadataValue.class);
    verify(target, atLeast(0)).setMetadata(eq(RequiredPackGate.METADATA), values.capture());
    values.getAllValues().forEach(value -> assertSame(plugin, value.getOwningPlugin()));
    return values.getAllValues().stream().map(MetadataValue::asString).toList();
  }

  @Test
  void joinMarksLoadingBeforeSendingOnTheNextTickThenReady() {
    join(player);
    assertEquals(List.of("loading"), states(player));
    assertTrue(sends.isEmpty(), "Nothing is sent inside the join event");
    assertTrue(gate.loading(playerId));
    tasks.removeFirst().run();
    assertEquals(List.of(player), sends);
    completions.getFirst().accept(true);
    completions.getFirst().accept(false);
    assertEquals(List.of("loading", "ready"), states(player));
    assertFalse(gate.loading(playerId));
    verify(logger).info("Required resource pack ready for Ada");
    assertFalse(gate.retry(player), "Ready players have nothing to retry");
  }

  @Test
  void failureMarksFailedAndRetrySetsLoadingBeforeAFreshAttempt() {
    join(player);
    tasks.removeFirst().run();
    assertFalse(gate.retry(player), "No retry while loading");
    completions.getFirst().accept(false);
    assertEquals(List.of("loading", "failed"), states(player));
    delivery =
        (target, done) -> {
          assertEquals(List.of("loading", "failed", "loading"), states(target));
          sends.add(target);
          completions.add(done);
        };
    assertTrue(gate.retry(player));
    assertTrue(gate.loading(playerId));
    assertFalse(gate.retry(player));
    completions.getFirst().accept(true);
    assertEquals(List.of("loading", "failed", "loading"), states(player), "Old attempts are stale");
    completions.get(1).accept(true);
    assertEquals(List.of("loading", "failed", "loading", "ready"), states(player));
  }

  @Test
  void retryOnlyServesTheCurrentOnlineConnection() {
    assertFalse(gate.retry(player), "Unknown players have no attempt");
    join(player);
    tasks.removeFirst().run();
    completions.getFirst().accept(false);
    Player stale = player();
    assertFalse(gate.retry(stale));
    when(player.isOnline()).thenReturn(false);
    assertFalse(gate.retry(player));
    when(player.isOnline()).thenReturn(true);
    gate.close();
    assertFalse(gate.retry(player));
    assertEquals(1, sends.size());
  }

  @Test
  void deliveryExceptionsMarkFailed() {
    delivery =
        (target, done) -> {
          throw new IllegalStateException("channel closed");
        };
    join(player);
    tasks.removeFirst().run();
    assertEquals(List.of("loading", "failed"), states(player));
    verify(logger).warning("Required resource pack failed for Ada: channel closed");
  }

  @Test
  void synchronousCompletionInsideDeliveryCounts() {
    delivery = (target, done) -> done.accept(false);
    join(player);
    tasks.removeFirst().run();
    assertEquals(List.of("loading", "failed"), states(player));
  }

  @Test
  void rejoinOverwritesMetadataAndOldConnectionCallbacksAndQuitsCannotTouchIt() {
    join(player);
    tasks.removeFirst().run();
    Consumer<Boolean> oldCompletion = completions.getFirst();
    Player newer = player();
    when(server.getPlayer(playerId)).thenReturn(newer);
    join(newer);
    Runnable newerSend = tasks.removeFirst();
    oldCompletion.accept(true);
    quit(player);
    verify(player, never()).removeMetadata(any(), any());
    assertTrue(gate.loading(playerId));
    assertEquals(List.of("loading"), states(player));
    assertEquals(List.of("loading"), states(newer));
    newerSend.run();
    assertEquals(List.of(player, newer), sends);
    completions.get(1).accept(true);
    assertEquals(List.of("loading", "ready"), states(newer));
  }

  @Test
  void anOldConnectionsQueuedSendNeverGoesOut() {
    join(player);
    Runnable stale = tasks.removeFirst();
    Player newer = player();
    when(server.getPlayer(playerId)).thenReturn(newer);
    join(newer);
    stale.run();
    assertTrue(sends.isEmpty());
    tasks.removeFirst().run();
    assertEquals(List.of(newer), sends);
  }

  @Test
  void quitRemovesMetadataAndLaterCallbacksDoNothing() {
    join(player);
    tasks.removeFirst().run();
    quit(player);
    verify(player).removeMetadata(RequiredPackGate.METADATA, plugin);
    assertFalse(gate.loading(playerId));
    completions.getFirst().accept(true);
    assertEquals(List.of("loading"), states(player));
    quit(player);
    verify(player, times(1)).removeMetadata(RequiredPackGate.METADATA, plugin);
  }

  @Test
  void aQueuedSendForAPlayerWhoAlreadyLeftIsDropped() {
    join(player);
    when(server.getPlayer(playerId)).thenReturn(null);
    tasks.removeFirst().run();
    assertTrue(sends.isEmpty());
  }

  @Test
  void disableRemovesMetadataUnregistersAndIgnoresLateCallbacks() {
    try (MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
      join(player);
      tasks.removeFirst().run();
      gate.close();
      verify(player).removeMetadata(RequiredPackGate.METADATA, plugin);
      handlers.verify(() -> HandlerList.unregisterAll(gate));
      completions.getFirst().accept(true);
      assertEquals(List.of("loading"), states(player));
      assertFalse(gate.loading(playerId));
    }
  }

  @Test
  void factoryChoosesMultipartThenStandardThenFailsClosed() throws Exception {
    MultipartPackService multipart = mock(MultipartPackService.class);
    RequiredPackGate viaMultipart = RequiredPackGate.create(plugin, multipart, Path.of("ia"));
    viaMultipart.join(new PlayerJoinEvent(player, Component.empty()));
    tasks.removeFirst().run();
    verify(multipart).sendRequired(eq(player), any());

    RequiredPackGate unavailable = RequiredPackGate.create(plugin, null, null);
    verify(logger).warning(contains("without ItemsAdder"));
    unavailable.join(new PlayerJoinEvent(player, Component.empty()));
    tasks.removeFirst().run();
    assertFalse(unavailable.loading(playerId));
    ArgumentCaptor<MetadataValue> values = ArgumentCaptor.forClass(MetadataValue.class);
    verify(player, atLeastOnce()).setMetadata(eq(RequiredPackGate.METADATA), values.capture());
    assertEquals("failed", values.getValue().asString());

    // The standard path owns its fallback: a quit or disable cancels its pending request.
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        MockedStatic<dev.lone.itemsadder.api.ItemsAdder> itemsAdder =
            mockStatic(dev.lone.itemsadder.api.ItemsAdder.class);
        MockedStatic<HandlerList> handlers = mockStatic(HandlerList.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      when(plugin.isEnabled()).thenReturn(true);
      List<Runnable> timeouts = new ArrayList<>();
      when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(3600L)))
          .thenAnswer(
              call -> {
                timeouts.add(call.getArgument(1));
                return mock(BukkitTask.class);
              });
      Path ia = temp;
      java.nio.file.Files.createDirectories(ia.resolve("output"));
      java.nio.file.Files.createDirectories(ia.resolve("storage/cache/various"));
      java.nio.file.Files.writeString(ia.resolve("output/generated.zip"), "zip");
      java.nio.file.Files.writeString(
          ia.resolve("storage/cache/various/resourcepacks.yml"), "last_hash: " + "a".repeat(40));
      itemsAdder
          .when(() -> dev.lone.itemsadder.api.ItemsAdder.getPackUrl(true))
          .thenReturn("http://127.0.0.1/pack.zip");
      RequiredPackGate standard = RequiredPackGate.create(plugin, null, ia);
      for (int connection = 0; connection < 2; connection++) {
        standard.join(new PlayerJoinEvent(player, Component.empty()));
        tasks.removeFirst().run();
        if (connection == 0) standard.quit(new PlayerQuitEvent(player, Component.empty()));
        else standard.close();
      }
      verify(player, times(2))
          .sendResourcePacks(any(net.kyori.adventure.resource.ResourcePackRequest.class));
      timeouts.forEach(Runnable::run);
      verify(player, never()).removeResourcePacks(any(UUID.class));
      assertFalse(standard.loading(playerId));
    }
  }

  @Test
  void standardFallbackRefusesOfflinePlayersAndAfterClose() {
    StandardPackFallback standard = new StandardPackFallback(plugin, Path.of("missing"));
    List<Boolean> results = new ArrayList<>();
    when(player.isOnline()).thenReturn(false);
    standard.send(player, results::add);
    when(player.isOnline()).thenReturn(true);
    standard.close();
    standard.send(player, results::add);
    assertEquals(List.of(false, false), results);
    verify(player, never()).sendResourcePacks(any(net.kyori.adventure.resource.ResourcePackRequest.class));
  }
}

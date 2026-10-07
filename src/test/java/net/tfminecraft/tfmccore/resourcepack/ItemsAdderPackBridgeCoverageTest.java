package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class ItemsAdderPackBridgeCoverageTest {
  @Test
  void failedPollRegistrationRemovesTheAlreadyRegisteredPacketListener() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    ProtocolManager manager = mock(ProtocolManager.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getScheduler()).thenReturn(scheduler);
    IllegalStateException failure = new IllegalStateException("scheduler stopped");
    when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(10L)))
        .thenThrow(failure);
    try (var bukkit = mockStatic(Bukkit.class);
        var library = mockStatic(ProtocolLibrary.class)) {
      bukkit.when(Bukkit::getServer).thenReturn(server);
      bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
      bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
      bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
      library.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);

      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  new ItemsAdderPackBridge(
                      plugin, mock(MultipartPackService.class), UUID.randomUUID())));

      ArgumentCaptor<PacketListener> listener = ArgumentCaptor.forClass(PacketListener.class);
      verify(manager).addPacketListener(listener.capture());
      var removals =
          mockingDetails(manager).getInvocations().stream()
              .filter(call -> call.getMethod().getName().equals("removePacketListener"))
              .toList();
      assertEquals(
          1, removals.size(), "A failed constructor must undo its packet listener registration");
      assertSame(listener.getValue(), removals.getFirst().getArgument(0));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"cancelled", "temporary", "unrelated", "missing-hash", "bad-hash", "closed"})
  void onlyActiveItemsAdderPacketsWithAValidSourceHashAreIntercepted(String reason) {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      switch (reason) {
        case "cancelled" -> when(packet.event().isCancelled()).thenReturn(true);
        case "temporary" -> when(packet.event().isPlayerTemporary()).thenReturn(true);
        case "unrelated" ->
            when(packet.container().getUUIDs().read(0)).thenReturn(UUID.randomUUID());
        case "missing-hash" -> when(packet.container().getStrings().read(1)).thenReturn(null);
        case "bad-hash" -> when(packet.container().getStrings().read(1)).thenReturn("A".repeat(40));
        case "closed" -> fixture.stop();
        default -> throw new AssertionError(reason);
      }

      fixture.listener.onPacketSending(packet.event());

      verify(packet.event(), never()).setCancelled(anyBoolean());
      verify(packet.container(), never()).deepClone();
      assertTrue(fixture.mainTasks.isEmpty());
      verifyNoInteractions(fixture.service);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void queuedDeliveryWaitsForTheMatchingGenerationAndPreservesTheRequiredFlag(boolean required) {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      when(packet.container().getBooleans().read(0)).thenReturn(required);

      fixture.listener.onPacketSending(packet.event());

      verify(packet.container()).deepClone();
      verify(packet.event()).setCancelled(true);
      verifyNoInteractions(fixture.service);
      fixture.mainTasks.removeFirst().run();
      fixture.poll.run();
      verify(fixture.service)
          .sendPublished(
              eq(fixture.player), eq(fixture.hash), eq(required), isNull(), any(Runnable.class));
      when(fixture.service.sendPublished(
              eq(fixture.player), eq(fixture.hash), eq(required), isNull(), any(Runnable.class)))
          .thenReturn(true);
      fixture.poll.run();
      fixture.poll.run();
      verify(fixture.service, times(2))
          .sendPublished(
              eq(fixture.player), eq(fixture.hash), eq(required), isNull(), any(Runnable.class));
      verify(fixture.manager, never()).sendServerPacket(any(), any(), anyBoolean());
    }
  }

  @Test
  void unsupportedPromptLayoutRetainsTheOriginalPacket() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      when(packet.prompt().read(0))
          .thenThrow(new IllegalArgumentException("unsupported prompt layout"));

      fixture.listener.onPacketSending(packet.event());

      verify(packet.event(), never()).setCancelled(true);
      verify(packet.container(), never()).deepClone();
      verify(fixture.logger)
          .warning(
              "Cannot replace ItemsAdder pack; retaining original delivery: unsupported prompt"
                  + " layout");
      assertTrue(fixture.mainTasks.isEmpty());
    }
  }

  @Test
  void aRejectedMainThreadTaskRetainsTheOriginalPacket() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      when(fixture.scheduler.runTask(eq(fixture.plugin), any(Runnable.class)))
          .thenThrow(new IllegalStateException("plugin disabled"));

      fixture.listener.onPacketSending(packet.event());

      verify(packet.event(), never()).setCancelled(true);
      verify(fixture.logger)
          .warning("Cannot replace ItemsAdder pack; retaining original delivery: plugin disabled");
      fixture.poll.run();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void nestedPassThroughScopesRestoreInterceptionEvenWhenTheActionThrows() {
    try (Fixture fixture = new Fixture()) {
      Packet outer = fixture.packet();
      Packet inner = fixture.packet();
      Packet afterInner = fixture.packet();
      IllegalStateException failure = new IllegalStateException("ItemsAdder failed");

      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  fixture.bridge.passThrough(
                      () -> {
                        fixture.listener.onPacketSending(outer.event());
                        fixture.bridge.passThrough(
                            () -> fixture.listener.onPacketSending(inner.event()));
                        fixture.listener.onPacketSending(afterInner.event());
                        throw failure;
                      })));

      for (Packet skipped : new Packet[] {outer, inner, afterInner}) {
        verify(skipped.event(), never()).setCancelled(anyBoolean());
      }
      assertTrue(fixture.mainTasks.isEmpty());
      Packet normal = fixture.packet();
      fixture.listener.onPacketSending(normal.event());
      verify(normal.event()).setCancelled(true);
      assertEquals(1, fixture.mainTasks.size());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void shutdownBeforeTheMainThreadCallbackReplaysOnlyForOnlinePlayers(boolean online) {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      fixture.listener.onPacketSending(packet.event());
      fixture.stop();
      when(fixture.player.isOnline()).thenReturn(online);

      fixture.mainTasks.removeFirst().run();

      if (online)
        verify(fixture.manager).sendServerPacket(fixture.player, packet.original(), false);
      else verify(fixture.manager, never()).sendServerPacket(any(), any(), anyBoolean());
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void playersWhoDisconnectBeforeQueueingDoNotReceiveDeferredDelivery() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      fixture.listener.onPacketSending(packet.event());
      when(fixture.player.isOnline()).thenReturn(false);

      fixture.mainTasks.removeFirst().run();
      fixture.poll.run();
      fixture.stop();

      verifyNoInteractions(fixture.service);
      verify(fixture.manager, never()).sendServerPacket(any(), any(), anyBoolean());
    }
  }

  @Test
  void shutdownReleasesQueuedOriginalPacketsAndCancelsThePoll() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      fixture.listener.onPacketSending(packet.event());
      fixture.mainTasks.removeFirst().run();

      fixture.stop();

      verify(fixture.manager).removePacketListener(fixture.listener);
      verify(fixture.pollTask).cancel();
      verify(fixture.manager).sendServerPacket(fixture.player, packet.original(), false);
      fixture.poll.run();
      verifyNoInteractions(fixture.service);
    }
  }

  @Test
  void quitDiscardsWaitingPacketsWithoutReplayingThem() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      fixture.listener.onPacketSending(packet.event());
      fixture.mainTasks.removeFirst().run();

      fixture.bridge.quit(fixture.playerId);
      fixture.poll.run();
      fixture.stop();

      verifyNoInteractions(fixture.service);
      verify(fixture.manager, never()).sendServerPacket(any(), any(), anyBoolean());
    }
  }

  @Test
  void failedMultipartDeliveryReplaysTheCapturedOriginalWithoutReenteringPacketListeners() {
    try (Fixture fixture = new Fixture()) {
      Packet packet = fixture.packet();
      fixture.listener.onPacketSending(packet.event());
      fixture.mainTasks.removeFirst().run();
      when(fixture.service.sendPublished(
              eq(fixture.player), eq(fixture.hash), anyBoolean(), isNull(), any(Runnable.class)))
          .thenAnswer(
              call -> {
                call.getArgument(4, Runnable.class).run();
                return true;
              });

      fixture.poll.run();
      fixture.poll.run();

      verify(fixture.manager).sendServerPacket(fixture.player, packet.original(), false);
      verify(fixture.service)
          .sendPublished(
              eq(fixture.player), eq(fixture.hash), anyBoolean(), isNull(), any(Runnable.class));
    }
  }

  private record Packet(
      PacketEvent event,
      PacketContainer container,
      PacketContainer original,
      StructureModifier<Optional<WrappedChatComponent>> prompt) {}

  /** ProtocolLib needs Bukkit material classes during static packet-cloner initialization. */
  private static final class Fixture implements AutoCloseable {
    final JavaPlugin plugin = mock(JavaPlugin.class);
    final Server server = mock(Server.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final BukkitTask pollTask = mock(BukkitTask.class);
    final ProtocolManager manager = mock(ProtocolManager.class);
    final MultipartPackService service = mock(MultipartPackService.class);
    final Player player = mock(Player.class);
    final UUID playerId = UUID.randomUUID();
    final UUID packId = UUID.randomUUID();
    final String hash = "a".repeat(40);
    final Logger logger = mock(Logger.class);
    final ArrayDeque<Runnable> mainTasks = new ArrayDeque<>();
    final MockedStatic<Bukkit> bukkit;
    final MockedStatic<ProtocolLibrary> library;
    final MockedStatic<MinecraftReflection> reflection;
    final MockedConstruction<ItemStack> nativeStacks;
    ItemsAdderPackBridge bridge;
    PacketListener listener;
    Runnable poll;

    Fixture() {
      when(plugin.getServer()).thenReturn(server);
      when(plugin.getLogger()).thenReturn(logger);
      when(server.getScheduler()).thenReturn(scheduler);
      when(server.getVersion()).thenReturn("Paper (MC: 1.21.10)");
      when(server.getBukkitVersion()).thenReturn("1.21.10-R0.1-SNAPSHOT");
      when(player.getUniqueId()).thenReturn(playerId);
      when(player.isOnline()).thenReturn(true);
      when(scheduler.runTask(eq(plugin), any(Runnable.class)))
          .thenAnswer(
              call -> {
                mainTasks.add(call.getArgument(1));
                return mock(BukkitTask.class);
              });
      when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(10L)))
          .thenAnswer(
              call -> {
                poll = call.getArgument(1);
                return pollTask;
              });
      doAnswer(
              call -> {
                listener = call.getArgument(0);
                return null;
              })
          .when(manager)
          .addPacketListener(any());
      bukkit = mockStatic(Bukkit.class);
      bukkit.when(Bukkit::getServer).thenReturn(server);
      bukkit.when(Bukkit::getVersion).thenReturn("Paper (MC: 1.21.10)");
      bukkit.when(Bukkit::getBukkitVersion).thenReturn("1.21.10-R0.1-SNAPSHOT");
      bukkit.when(Bukkit::getLogger).thenReturn(logger);
      library = mockStatic(ProtocolLibrary.class);
      library.when(ProtocolLibrary::getProtocolManager).thenReturn(manager);
      reflection = mockStatic(MinecraftReflection.class);
      reflection.when(MinecraftReflection::getItemStackClass).thenReturn(ItemStack.class);
      reflection.when(MinecraftReflection::getBlockClass).thenReturn(Block.class);
      nativeStacks = mockConstruction(ItemStack.class);
      bridge = new ItemsAdderPackBridge(plugin, service, packId);
    }

    @SuppressWarnings("unchecked")
    Packet packet() {
      PacketContainer packet = mock(PacketContainer.class, RETURNS_DEEP_STUBS);
      PacketContainer original = mock(PacketContainer.class);
      PacketEvent event = mock(PacketEvent.class);
      StructureModifier<Optional<WrappedChatComponent>> prompt = mock(StructureModifier.class);
      when(event.getPacket()).thenReturn(packet);
      when(event.getPlayer()).thenReturn(player);
      when(packet.getUUIDs().read(0)).thenReturn(packId);
      when(packet.getStrings().read(1)).thenReturn(hash);
      when(packet.deepClone()).thenReturn(original);
      doReturn(prompt).when(packet).getOptionals(any());
      when(prompt.read(0)).thenReturn(Optional.empty());
      return new Packet(event, packet, original, prompt);
    }

    void stop() {
      if (bridge != null) {
        bridge.close();
        bridge = null;
      }
    }

    @Override
    public void close() {
      stop();
      nativeStacks.close();
      reflection.close();
      library.close();
      bukkit.close();
    }
  }
}

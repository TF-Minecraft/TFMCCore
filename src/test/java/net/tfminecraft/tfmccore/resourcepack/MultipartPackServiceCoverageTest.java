package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import dev.lone.itemsadder.api.ItemsAdder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.tfminecraft.tfmccore.cache.Cache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class MultipartPackServiceCoverageTest {
  @TempDir Path directory;
  private final JavaPlugin plugin = mock(JavaPlugin.class);
  private final Logger logger = mock(Logger.class);
  private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
  private final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
  private final ArrayDeque<Runnable> callbacks = new ArrayDeque<>();
  private final List<Runnable> timeouts = new ArrayList<>();
  private final AtomicReference<Runnable> refresh = new AtomicReference<>();
  private final UUID playerId = UUID.randomUUID();
  private final Player player = mock(Player.class);
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<ItemsAdder> itemsAdder;
  private MockedStatic<HandlerList> handlers;
  private MockedStatic<Executors> executors;
  private MockedConstruction<ItemsAdderPackBridge> bridges;
  private MultipartPackService service;
  private Path itemsAdderDirectory;
  private Path source;
  private Path cache;
  private String sourceHash;
  private int port;

  @BeforeEach
  void setUp() throws Exception {
    itemsAdderDirectory = directory.resolve("ItemsAdder");
    Files.createDirectories(itemsAdderDirectory.resolve("output"));
    Files.createDirectories(itemsAdderDirectory.resolve("storage/cache/various"));
    Files.writeString(
        itemsAdderDirectory.resolve("config.yml"),
        "resource-pack:\n  uuid: " + UUID.randomUUID() + "\n");
    source = itemsAdderDirectory.resolve("output/generated.zip");
    writePack(source);
    sourceHash = PackPartitioner.sha1(source);
    cache = itemsAdderDirectory.resolve("storage/cache/various/resourcepacks.yml");
    Files.writeString(cache, "last_hash: " + sourceHash + "\n");
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    when(plugin.getDataFolder()).thenReturn(directory.resolve("TFMCCore").toFile());
    when(plugin.getLogger()).thenReturn(logger);
    when(plugin.isEnabled()).thenReturn(true);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.isOnline()).thenReturn(true);
    when(player.getName()).thenReturn("Ada");
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              callbacks.add(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(3600L)))
        .thenAnswer(
            call -> {
              timeouts.add(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    when(worker.scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(2L), eq(TimeUnit.SECONDS)))
        .thenAnswer(
            call -> {
              refresh.set(call.getArgument(0));
              return null;
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    itemsAdder = mockStatic(ItemsAdder.class);
    handlers = mockStatic(HandlerList.class);
    executors = mockStatic(Executors.class, CALLS_REAL_METHODS);
    executors
        .when(() -> Executors.newSingleThreadScheduledExecutor(any(ThreadFactory.class)))
        .thenAnswer(
            call -> {
              Thread publisherThread = call.getArgument(0, ThreadFactory.class).newThread(() -> {});
              assertEquals("TFMC-pack-publisher", publisherThread.getName());
              assertTrue(
                  publisherThread.isDaemon(),
                  "Publisher shutdown must not hold the server process open");
              return worker;
            });
    bridges =
        mockConstruction(
            ItemsAdderPackBridge.class,
            (bridge, context) ->
                doAnswer(
                        call -> {
                          call.getArgument(0, Runnable.class).run();
                          return null;
                        })
                    .when(bridge)
                    .passThrough(any(Runnable.class)));
  }

  @AfterEach
  void tearDown() {
    if (service != null) service.close();
    if (bridges != null) bridges.close();
    if (executors != null) executors.close();
    if (handlers != null) handlers.close();
    if (itemsAdder != null) itemsAdder.close();
    if (bukkit != null) bukkit.close();
  }

  @Test
  void publishedPartsUseTheirContentHashesAndServeExactlyThePublishedBytes() throws Exception {
    start();
    refresh.get().run();
    refresh.get().run();
    service.send(player);
    ResourcePackRequest request = request();
    assertEquals(3, request.packs().size());
    assertTrue(request.replace());
    assertFalse(request.required());
    assertNull(request.prompt());
    Map<String, String> payloads = new LinkedHashMap<>();
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
      for (ResourcePackInfo pack : request.packs()) {
        String filename = Path.of(pack.uri().getPath()).getFileName().toString();
        String part = filename.substring(0, filename.length() - 4);
        Path published = publishedRoot().resolve(sourceHash).resolve(filename);
        assertEquals(sourceHash + "/" + filename, pack.uri().getPath().substring(1));
        assertEquals(PackPartitioner.sha1(published), pack.hash());
        assertEquals(
            UUID.nameUUIDFromBytes(
                ("tfmc:" + part + ":" + pack.hash()).getBytes(StandardCharsets.UTF_8)),
            pack.id());
        var response =
            client.send(
                HttpRequest.newBuilder(pack.uri()).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(Files.readAllBytes(published), response.body());
        try (ZipFile zip = new ZipFile(published.toFile())) {
          for (ZipEntry entry : zip.stream().toList()) {
            try (var input = zip.getInputStream(entry)) {
              String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
              if (entry.getName().equals("pack.mcmeta"))
                assertEquals("{\"pack\":{\"pack_format\":69}}", text);
              else
                assertNull(payloads.put(entry.getName(), text), "Each payload belongs in one part");
            }
          }
        }
      }
    }
    assertEquals(
        Map.of(
            "assets/test/textures/icon.png",
            "core texture",
            "assets/modelengine/models/creature.json",
            "model",
            "assets/creature_sounds/sounds.json",
            "sound"),
        payloads);
    verify(logger, times(1)).info("Published three resource-pack parts for " + sourceHash);
    assertTrue(service.loading(playerId));
  }

  @Test
  void matchingAutomaticDeliveryPreservesPromptAndRequiredFlagWithoutApplyingCommandCooldown()
      throws Exception {
    start();
    refresh.get().run();
    Runnable fallback = mock(Runnable.class);
    Component prompt = Component.text("Required server textures");
    assertFalse(service.sendPublished(player, "0".repeat(40), true, prompt, fallback));
    when(player.isOnline()).thenReturn(false);
    assertFalse(service.sendPublished(player, sourceHash, true, prompt, fallback));
    when(player.isOnline()).thenReturn(true);
    assertTrue(service.sendPublished(player, sourceHash, true, prompt, fallback));
    ResourcePackRequest request = request();
    assertEquals(prompt, request.prompt());
    assertTrue(request.required());
    assertFalse(service.sendPublished(player, sourceHash, false, null, fallback));
    complete(request);
    assertTrue(service.sendPublished(player, sourceHash, true, prompt, fallback));
    verifyNoInteractions(fallback);
  }

  @Test
  void allDistinctSuccessfulPartsAreRequiredAndLateStatusesCannotCompleteANewerRequest()
      throws Exception {
    publish();
    Runnable fallback = mock(Runnable.class);
    assertTrue(service.sendPublished(player, sourceHash, false, null, fallback));
    ResourcePackRequest first = request();
    emit(first, UUID.randomUUID(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    emit(first, first.packs().getFirst().id(), ResourcePackStatus.ACCEPTED);
    emit(first, first.packs().getFirst().id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    emit(first, first.packs().getFirst().id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertTrue(service.loading(playerId));
    emit(first, first.packs().get(1).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertTrue(service.loading(playerId));
    emit(first, first.packs().get(2).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertFalse(service.loading(playerId));
    verify(player).sendMessage(Component.text("TFMC resource pack loaded."));
    assertTrue(service.sendPublished(player, sourceHash, false, null, fallback));
    emit(first, first.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    timeouts.getFirst().run();
    assertTrue(
        service.loading(playerId), "Old callbacks and deadlines cannot remove the newer request");
    verifyNoInteractions(fallback);
  }

  @ParameterizedTest
  @EnumSource(
      value = ResourcePackStatus.class,
      names = {"FAILED_DOWNLOAD", "FAILED_RELOAD", "DECLINED", "DISCARDED"})
  void terminalFailureRemovesSplitPacksAndOnlyRetriesWhenThePlayerDidNotDecline(
      ResourcePackStatus status) throws Exception {
    publish();
    Runnable fallback = mock(Runnable.class);
    assertTrue(service.sendPublished(player, sourceHash, false, null, fallback));
    ResourcePackRequest request = request();
    emit(request, request.packs().getFirst().id(), status);
    assertFalse(service.loading(playerId));
    verify(player)
        .removeResourcePacks(
            new java.util.HashSet<>(request.packs().stream().map(ResourcePackInfo::id).toList()));
    if (status == ResourcePackStatus.DECLINED || status == ResourcePackStatus.DISCARDED)
      verifyNoInteractions(fallback);
    else {
      verify(fallback).run();
      verify(player)
          .sendMessage(Component.text("Could not load the split pack. Trying the standard pack."));
    }
    emit(request, request.packs().getFirst().id(), status);
    timeouts.getFirst().run();
    verify(logger, times(1)).warning("Multipart pack " + status + " for Ada");
  }

  @Test
  void unavailablePublicationUsesItemsAdderOnceUntilQuitResetsCommandCooldown() throws Exception {
    start();
    service.send(player);
    service.send(player);
    itemsAdder.verify(() -> ItemsAdder.applyResourcepack(player), times(1));
    verify(bridges.constructed().getFirst()).passThrough(any(Runnable.class));
    verify(player)
        .sendMessage(Component.text("Please wait before requesting the resource pack again."));
    service.quit(new PlayerQuitEvent(player, Component.empty()));
    verify(bridges.constructed().getFirst()).quit(playerId);
    service.send(player);
    itemsAdder.verify(() -> ItemsAdder.applyResourcepack(player), times(2));
  }

  @Test
  void commandsReportAnActiveRequestWithoutSendingAnotherPack() throws Exception {
    publish();
    service.send(player);
    service.send(player);
    verify(player).sendResourcePacks(any(ResourcePackRequest.class));
    verify(player).sendMessage(Component.text("Your resource pack is already loading."));
    service.quit(new PlayerQuitEvent(player, Component.empty()));
    assertFalse(service.loading(playerId));
  }

  @Test
  void offlineOrClosedServicesNeverBeginDeliveryAndShutdownReleasesTheBoundPort() throws Exception {
    publish();
    when(player.isOnline()).thenReturn(false);
    service.send(player);
    verify(player, never()).sendResourcePacks(any(ResourcePackRequest.class));
    service.close();
    refresh.get().run();
    when(player.isOnline()).thenReturn(true);
    service.send(player);
    assertFalse(
        service.sendPublished(
            player, sourceHash, false, null, () -> fail("Closed service fallback")));
    verify(worker).shutdownNow();
    verify(bridges.constructed().getFirst()).close();
    handlers.verify(() -> HandlerList.unregisterAll(service));
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress("127.0.0.1", port));
    }
    itemsAdder.verifyNoInteractions();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void timeoutsOnlyRetryPlayersWhoRemainOnline(boolean online) throws Exception {
    publish();
    Runnable fallback = mock(Runnable.class);
    service.sendPublished(player, sourceHash, false, null, fallback);
    when(player.isOnline()).thenReturn(online);
    timeouts.getFirst().run();
    timeouts.getFirst().run();
    assertFalse(service.loading(playerId));
    if (online) {
      verify(fallback).run();
      verify(player)
          .sendMessage(
              Component.text("Resource-pack loading timed out. Trying the standard pack."));
    } else verifyNoInteractions(fallback);
  }

  @Test
  void synchronousDeliveryFailureRemovesRequestBeforeApplyingTheStandardFallback()
      throws Exception {
    publish();
    doThrow(new IllegalStateException("client disconnected"))
        .when(player)
        .sendResourcePacks(any(ResourcePackRequest.class));
    service.send(player);
    assertFalse(service.loading(playerId));
    verify(player).removeResourcePacks(any(Iterable.class));
    verify(logger).warning("Multipart request failed: client disconnected");
    itemsAdder.verify(() -> ItemsAdder.applyResourcepack(player));
    assertTrue(timeouts.isEmpty());
  }

  @Test
  void callbacksRespectPluginShutdownAndAnOfflinePlayer() throws Exception {
    publish();
    service.send(player);
    ResourcePackRequest request = request();
    when(plugin.isEnabled()).thenReturn(false);
    request
        .callback()
        .packEventReceived(
            request.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD, player);
    assertTrue(callbacks.isEmpty());
    when(plugin.isEnabled()).thenReturn(true);
    when(player.isOnline()).thenReturn(false);
    emit(request, request.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    assertTrue(service.loading(playerId));
    when(player.isOnline()).thenReturn(true);
    request
        .callback()
        .packEventReceived(
            request.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD, player);
    service.close();
    callbacks.removeFirst().run();
    request
        .callback()
        .packEventReceived(
            request.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD, player);
    assertTrue(callbacks.isEmpty());
    itemsAdder.verifyNoInteractions();
  }

  @Test
  void incompleteBuildsUseFallbackWithoutRepeatedWarningsAndRecoverWhenTheCacheChanges()
      throws Exception {
    Files.writeString(cache, "last_hash: invalid\n");
    start();
    refresh.get().run();
    refresh.get().run();
    assertFalse(
        service.sendPublished(
            player, sourceHash, false, null, () -> fail("No request was accepted")));
    verify(logger, times(1))
        .warning(
            "Multipart pack unavailable; single-pack fallback remains active: Invalid source hash");
    Files.writeString(cache, "last_hash: " + sourceHash + "\n");
    Files.setLastModifiedTime(cache, FileTime.fromMillis(System.currentTimeMillis() + 5000));
    refresh.get().run();
    assertTrue(service.sendPublished(player, sourceHash, false, null, () -> {}));
    verify(logger).info("Published three resource-pack parts for " + sourceHash);
  }

  @Test
  void absentSourceLogsOnceAndDoesNotPublishAPartialPack() throws Exception {
    Files.delete(source);
    start();
    refresh.get().run();
    refresh.get().run();
    verify(logger, times(1))
        .warning(startsWith("Multipart pack unavailable; single-pack fallback remains active: "));
    assertFalse(Files.exists(publishedRoot()));
    assertFalse(
        service.sendPublished(player, sourceHash, false, null, () -> fail("No multipart request")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"generation", "file", "close"})
  void aBuildThatBecomesStaleDuringPublicationIsNeverDelivered(String interruption)
      throws Exception {
    PackPublisher.Bundle published = new PackPublisher(publishedRoot()).publish(source, sourceHash);
    AtomicBoolean first = new AtomicBoolean(true);
    try (var publishers =
        mockConstruction(
            PackPublisher.class,
            (publisher, context) ->
                when(publisher.publish(source, sourceHash))
                    .thenAnswer(
                        call -> {
                          if (first.getAndSet(false)) {
                            switch (interruption) {
                              case "generation" ->
                                  service.rebuilding(new ItemsAdderPackCompressedEvent());
                              case "file" ->
                                  Files.setLastModifiedTime(
                                      cache,
                                      FileTime.fromMillis(System.currentTimeMillis() + 5000));
                              case "close" -> service.close();
                              default -> throw new AssertionError(interruption);
                            }
                          }
                          return published;
                        }))) {
      start();
      refresh.get().run();
      assertFalse(
          service.sendPublished(player, sourceHash, false, null, () -> fail("Stale publication")));
      verify(logger, never()).info(startsWith("Published three resource-pack parts"));
      if (!interruption.equals("close")) {
        refresh.get().run();
        assertTrue(service.sendPublished(player, sourceHash, false, null, () -> {}));
        verify(publishers.constructed().getFirst(), times(2)).publish(source, sourceHash);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.test/",
        "http:///missing-host",
        "https://example.test/?key=value",
        "https://example.test/#part",
        "https://user@example.test/",
        "not a URI"
      })
  void invalidPublicUrlsFailBeforeStartingBackgroundResources(String url) {
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new MultipartPackService(
                    plugin, itemsAdderDirectory, new InetSocketAddress("127.0.0.1", port), url));
    assertEquals("Invalid multipart public URL", failure.getMessage());
    verifyNoInteractions(worker);
    assertTrue(bridges.constructed().isEmpty());
  }

  @Test
  void zeroPortIsRejectedBeforeOpeningTheHttpListener() {
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new MultipartPackService(
                    plugin,
                    itemsAdderDirectory,
                    new InetSocketAddress("127.0.0.1", 0),
                    "http://localhost/"));
    assertEquals("Invalid multipart HTTP port", failure.getMessage());
    verifyNoInteractions(worker);
  }

  @Test
  void bridgeConstructionFailureClosesTheWorkerAndHttpListener() throws Exception {
    bridges.close();
    bridges = null;
    try (var failedBridge =
        mockConstruction(
            ItemsAdderPackBridge.class,
            (bridge, context) -> {
              throw new IllegalStateException("ProtocolLib unavailable");
            })) {
      assertThrows(RuntimeException.class, this::start);
      verify(worker).shutdownNow();
      try (ServerSocket socket = new ServerSocket()) {
        socket.bind(new InetSocketAddress("127.0.0.1", port));
      }
    }
  }

  @Test
  void compactionSkipsMissingRemovedOrAlreadySimpleMetadataWithoutChangingPayloads() {
    boolean previous = Cache.compactResourcePackOverlays;
    Cache.compactResourcePackOverlays = true;
    try {
      ResourcePackListener listener = new ResourcePackListener(logger);
      ItemsAdderPackCompressedEvent missing = new ItemsAdderPackCompressedEvent();
      missing.setTextEntry("assets/test/data.txt", "payload");
      listener.compactOverlays(missing);
      assertEquals("payload", missing.getEntry("assets/test/data.txt").getText());
      ItemsAdderPackCompressedEvent removed = new ItemsAdderPackCompressedEvent();
      removed.setTextEntry("pack.mcmeta", "{}");
      removed.removeEntry("pack.mcmeta");
      listener.compactOverlays(removed);
      assertNull(removed.getEntry("pack.mcmeta"));
      ItemsAdderPackCompressedEvent simple = new ItemsAdderPackCompressedEvent();
      simple.setTextEntry("pack.mcmeta", "{\"pack\":{\"pack_format\":69}}");
      simple.setTextEntry("assets/test/removed.txt", "old");
      simple.removeEntry("assets/test/removed.txt");
      listener.compactOverlays(simple);
      assertEquals("{\"pack\":{\"pack_format\":69}}", simple.getEntry("pack.mcmeta").getText());
      assertNull(simple.getEntry("assets/test/removed.txt"));
      verifyNoInteractions(logger);
    } finally {
      Cache.compactResourcePackOverlays = previous;
    }
  }

  // Required delivery for the join gate.

  private final List<Boolean> results = new ArrayList<>();
  private static final String PACK_URL = "http://127.0.0.1:9900/generated.zip";

  private void packUrl(String url) {
    itemsAdder.when(() -> ItemsAdder.getPackUrl(true)).thenReturn(url);
  }

  @Test
  void requiredDeliveryCompletesTrueOnceOnlyAfterAllThreePartsLoad() throws Exception {
    publish();
    service.sendRequired(player, results::add);
    ResourcePackRequest first = request();
    assertTrue(first.required());
    assertTrue(first.replace());
    assertEquals(StandardPackFallback.PROMPT, first.prompt());
    assertEquals(3, first.packs().size());
    for (ResourcePackInfo pack : first.packs()) {
      String part = Path.of(pack.uri().getPath()).getFileName().toString().replace(".zip", "");
      assertNotEquals(
          UUID.nameUUIDFromBytes(("tfmc:" + part + ":" + pack.hash()).getBytes(StandardCharsets.UTF_8)),
          pack.id(),
          "Required parts never reuse the stable manual IDs");
      assertEquals(
          UUID.nameUUIDFromBytes(
              ("tfmc:required:" + part + ":" + pack.hash()).getBytes(StandardCharsets.UTF_8)),
          pack.id(),
          "A connection's first request reuses the client's cached download");
    }
    emit(first, first.packs().get(0).id(), ResourcePackStatus.ACCEPTED);
    emit(first, first.packs().get(0).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    emit(first, first.packs().get(1).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(), results, "Two of three parts is still loading");
    emit(first, first.packs().get(2).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(true), results);
    emit(first, first.packs().get(2).id(), ResourcePackStatus.FAILED_RELOAD);
    timeouts.forEach(Runnable::run);
    assertEquals(List.of(true), results);
    itemsAdder.verify(() -> ItemsAdder.getPackUrl(anyBoolean()), never());

    // A second attempt gets new IDs; the client's late answers to the first cannot reach it.
    service.sendRequired(player, results::add);
    ResourcePackRequest second = request();
    assertTrue(
        java.util.Collections.disjoint(
            first.packs().stream().map(ResourcePackInfo::id).toList(),
            second.packs().stream().map(ResourcePackInfo::id).toList()));
    for (ResourcePackInfo pack : first.packs())
      emit(first, pack.id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(true), results);
    assertTrue(service.loading(playerId));
  }

  @ParameterizedTest
  @EnumSource(
      value = ResourcePackStatus.class,
      names = {"FAILED_DOWNLOAD", "FAILED_RELOAD", "INVALID_URL"})
  void partFailureRemovesThePartsThenSendsTheStandardPackOnce(ResourcePackStatus status)
      throws Exception {
    publish();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    emit(parts, parts.packs().get(0).id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    emit(parts, parts.packs().get(1).id(), status);
    verify(player)
        .removeResourcePacks(
            new java.util.HashSet<>(parts.packs().stream().map(ResourcePackInfo::id).toList()));
    ResourcePackRequest standard = request();
    assertNotSame(parts, standard);
    assertEquals(1, standard.packs().size());
    ResourcePackInfo pack = standard.packs().getFirst();
    assertEquals(java.net.URI.create(PACK_URL), pack.uri());
    assertEquals(sourceHash, pack.hash());
    assertTrue(standard.required());
    assertTrue(standard.replace());
    assertEquals(List.of(), results, "The gate stays loading through the fallback");
    emit(parts, parts.packs().get(2).id(), status);
    timeouts.getFirst().run();
    emit(standard, pack.id(), ResourcePackStatus.ACCEPTED);
    emit(standard, pack.id(), ResourcePackStatus.DOWNLOADED);
    emit(standard, UUID.randomUUID(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(), results);
    emit(standard, pack.id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(true), results);
    emit(standard, pack.id(), ResourcePackStatus.FAILED_DOWNLOAD);
    timeouts.forEach(Runnable::run);
    assertEquals(List.of(true), results);
    verify(player, times(2)).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @ParameterizedTest
  @EnumSource(
      value = ResourcePackStatus.class,
      names = {"FAILED_DOWNLOAD", "DECLINED", "DISCARDED"})
  void aFailedStandardFallbackCompletesFalse(ResourcePackStatus status) throws Exception {
    publish();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    emit(parts, parts.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    ResourcePackRequest standard = request();
    emit(standard, standard.packs().getFirst().id(), status);
    assertEquals(List.of(false), results);
    verify(logger).warning("Standard resource pack " + status + " for Ada");
  }

  @ParameterizedTest
  @EnumSource(
      value = ResourcePackStatus.class,
      names = {"DECLINED", "DISCARDED"})
  void declinedPartsCompleteFalseWithoutAFallback(ResourcePackStatus status) throws Exception {
    publish();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    emit(parts, parts.packs().getFirst().id(), status);
    assertEquals(List.of(false), results);
    verify(player, times(1)).sendResourcePacks(any(ResourcePackRequest.class));
    itemsAdder.verify(() -> ItemsAdder.getPackUrl(anyBoolean()), never());
  }

  @Test
  void timeoutsFallBackOnceThenFailAndRemoveTheStandardPack() throws Exception {
    publish();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    timeouts.removeFirst().run();
    verify(player)
        .sendMessage(Component.text("Resource-pack loading timed out. Trying the standard pack."));
    ResourcePackRequest standard = request();
    UUID id = standard.packs().getFirst().id();
    emit(parts, parts.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    assertEquals(1, timeouts.size(), "The parts may fall back only once");
    timeouts.getFirst().run();
    assertEquals(List.of(false), results);
    verify(player).removeResourcePacks(id);
    verify(logger).warning("Standard resource pack timed out for Ada");
    emit(standard, id, ResourcePackStatus.SUCCESSFULLY_LOADED);
    timeouts.getFirst().run();
    assertEquals(List.of(false), results);
  }

  @Test
  void anUnpublishedBundleSendsTheStandardPackDirectlyAndCachesItsHash() throws Exception {
    start();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest standard = request();
    assertEquals(sourceHash, standard.packs().getFirst().hash());
    emit(standard, standard.packs().getFirst().id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    assertEquals(List.of(true), results);

    // An unchanged source and cache reuse the hash read for that generation.
    FileTime cached = Files.getLastModifiedTime(cache);
    Files.writeString(cache, "last_hash: " + "0".repeat(40) + "\n");
    Files.setLastModifiedTime(cache, cached);
    service.sendRequired(player, results::add);
    ResourcePackRequest again = request();
    assertEquals(sourceHash, again.packs().getFirst().hash());
    assertNotEquals(standard.packs().getFirst().id(), again.packs().getFirst().id());
    emit(standard, standard.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    assertEquals(List.of(true), results, "A late answer to the earlier pack is ignored");
  }

  @ParameterizedTest
  @ValueSource(strings = {"no-url", "blank-url", "bad-url", "url-error", "no-hash", "no-source", "send-error", "timer-error"})
  void anUnavailableStandardPackFailsClosed(String reason) throws Exception {
    start();
    packUrl(PACK_URL);
    switch (reason) {
      case "no-url" -> packUrl(null);
      case "blank-url" -> packUrl(" ");
      case "bad-url" -> packUrl("http://bad host/");
      case "url-error" ->
          itemsAdder
              .when(() -> ItemsAdder.getPackUrl(true))
              .thenThrow(new IllegalStateException("self-host off"));
      case "no-hash" -> Files.writeString(cache, "other: 1\n");
      case "no-source" -> Files.delete(source);
      case "send-error" ->
          doThrow(new IllegalStateException("closed channel"))
              .when(player)
              .sendResourcePacks(any(ResourcePackRequest.class));
      case "timer-error" ->
          when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(3600L)))
              .thenThrow(new IllegalStateException("scheduler stopped"));
      default -> throw new AssertionError(reason);
    }
    service.sendRequired(player, results::add);
    assertEquals(List.of(false), results);
    verify(logger).warning(startsWith("Standard resource pack unavailable for Ada: "));
    if (reason.endsWith("-error") && !reason.equals("url-error"))
      verify(player).removeResourcePacks(any(UUID.class));
    else verify(player, never()).removeResourcePacks(any(UUID.class));
  }

  @Test
  void aSourceRebuiltWhileItsHashIsReadIsNotSent() throws Exception {
    start();
    packUrl(PACK_URL);
    try (var yaml =
        mockStatic(org.bukkit.configuration.file.YamlConfiguration.class, CALLS_REAL_METHODS)) {
      yaml.when(
              () ->
                  org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                      any(java.io.File.class)))
          .thenAnswer(
              call -> {
                Files.setLastModifiedTime(source, FileTime.fromMillis(1234));
                return call.callRealMethod();
              });
      service.sendRequired(player, results::add);
    }
    assertEquals(List.of(false), results);
    verify(logger).warning("Standard resource pack unavailable for Ada: ItemsAdder pack is rebuilding");
    verify(player, never()).sendResourcePacks(any(ResourcePackRequest.class));
  }

  @Test
  void offlineClosedAndThrowingRequiredDeliveriesFailOrFallBack() throws Exception {
    publish();
    packUrl(PACK_URL);
    when(player.isOnline()).thenReturn(false);
    service.sendRequired(player, results::add);
    assertEquals(List.of(false), results);
    when(player.isOnline()).thenReturn(true);
    doThrow(new IllegalStateException("closed channel"))
        .doNothing()
        .when(player)
        .sendResourcePacks(any(ResourcePackRequest.class));
    service.sendRequired(player, results::add);
    verify(logger).warning("Multipart request failed: closed channel");
    ResourcePackRequest standard = request();
    assertEquals(1, standard.packs().size(), "A synchronous part failure falls back");
    service.close();
    service.sendRequired(player, results::add);
    assertEquals(List.of(false, false), results);
    standard.callback().packEventReceived(standard.packs().getFirst().id(), ResourcePackStatus.SUCCESSFULLY_LOADED, player);
    assertTrue(callbacks.isEmpty(), "Closed fallbacks schedule nothing");
    timeouts.forEach(Runnable::run);
    assertEquals(List.of(false, false), results);
  }

  @Test
  void requiredDeliveryReplacesAManualRequestAndQueuedBridgeDelivery() throws Exception {
    publish();
    service.send(player);
    ResourcePackRequest manual = request();
    service.sendRequired(player, results::add);
    verify(bridges.constructed().getFirst()).quit(playerId);
    ResourcePackRequest required = request();
    complete(manual);
    timeouts.getFirst().run();
    assertEquals(List.of(), results, "The manual request's answers and deadline are stale");
    verify(player, never()).sendMessage(Component.text("TFMC resource pack loaded."));
    complete(required);
    assertEquals(List.of(true), results);
  }

  @Test
  void quitDropsRequiredWorkButAnOlderConnectionsQuitKeepsTheNewerOne() throws Exception {
    publish();
    packUrl(PACK_URL);
    Player older = mock(Player.class);
    when(older.getUniqueId()).thenReturn(playerId);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    service.quit(new PlayerQuitEvent(older, Component.empty()));
    assertTrue(service.loading(playerId), "An older connection's quit is not this request's");
    emit(parts, parts.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    ResourcePackRequest standard = request();
    service.quit(new PlayerQuitEvent(older, Component.empty()));
    service.quit(new PlayerQuitEvent(player, Component.empty()));
    assertFalse(service.loading(playerId));
    emit(standard, standard.packs().getFirst().id(), ResourcePackStatus.SUCCESSFULLY_LOADED);
    timeouts.forEach(Runnable::run);
    assertEquals(List.of(), results, "A quit player's attempt never completes");
  }

  @Test
  void eachNewConnectionReusesTheStableIdsSoTheClientCacheHits() throws Exception {
    publish();
    packUrl(PACK_URL);
    service.sendRequired(player, results::add);
    ResourcePackRequest parts = request();
    emit(parts, parts.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD);
    ResourcePackRequest standard = request();
    assertEquals(
        UUID.nameUUIDFromBytes(("tfmc:standard:" + sourceHash).getBytes(StandardCharsets.UTF_8)),
        standard.packs().getFirst().id());
    service.quit(new PlayerQuitEvent(player, Component.empty()));

    Player next = mock(Player.class);
    when(next.getUniqueId()).thenReturn(playerId);
    when(next.isOnline()).thenReturn(true);
    when(next.getName()).thenReturn("Ada");
    service.sendRequired(next, results::add);
    ArgumentCaptor<ResourcePackRequest> captured =
        ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(next).sendResourcePacks(captured.capture());
    ResourcePackRequest again = captured.getValue();
    assertEquals(
        parts.packs().stream().map(ResourcePackInfo::id).toList(),
        again.packs().stream().map(ResourcePackInfo::id).toList());
    again.callback().packEventReceived(again.packs().getFirst().id(), ResourcePackStatus.FAILED_DOWNLOAD, next);
    callbacks.removeFirst().run();
    verify(next, times(2)).sendResourcePacks(captured.capture());
    assertEquals(standard.packs().getFirst().id(), captured.getValue().packs().getFirst().id());
  }

  @Test
  void theGatePredicateIsConsultedForOwnership() throws Exception {
    start();
    assertFalse(service.gated(playerId));
    service.gate(playerId::equals);
    assertTrue(service.gated(playerId));
    assertFalse(service.gated(UUID.randomUUID()));
  }

  private void start() throws IOException {
    service =
        new MultipartPackService(
            plugin,
            itemsAdderDirectory,
            new InetSocketAddress("127.0.0.1", port),
            "http://127.0.0.1:" + port);
  }

  private void publish() throws IOException {
    start();
    refresh.get().run();
  }

  private Path publishedRoot() {
    return directory.resolve("TFMCCore/resource-pack/parts-v1");
  }

  private ResourcePackRequest request() {
    ArgumentCaptor<ResourcePackRequest> request =
        ArgumentCaptor.forClass(ResourcePackRequest.class);
    verify(player, atLeastOnce()).sendResourcePacks(request.capture());
    return request.getValue();
  }

  private void emit(ResourcePackRequest request, UUID id, ResourcePackStatus status) {
    request.callback().packEventReceived(id, status, player);
    callbacks.removeFirst().run();
  }

  private void complete(ResourcePackRequest request) {
    request
        .packs()
        .forEach(pack -> emit(request, pack.id(), ResourcePackStatus.SUCCESSFULLY_LOADED));
  }

  private static void writePack(Path file) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("pack.mcmeta", "{\"pack\":{\"pack_format\":69}}");
    entries.put("assets/test/textures/icon.png", "core texture");
    entries.put("assets/modelengine/models/creature.json", "model");
    entries.put("assets/creature_sounds/sounds.json", "sound");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
      for (var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
  }
}

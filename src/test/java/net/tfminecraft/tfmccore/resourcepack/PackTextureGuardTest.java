package net.tfminecraft.tfmccore.resourcepack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackTextureGuardTest {
  private static final String ATLAS =
      "tfmc_overlay_63_74/assets/minecraft/textures/entity/equipment/humanoid/leather.png";
  @TempDir Path directory;
  private Path pack;
  private final JavaPlugin plugin = mock(JavaPlugin.class);
  private final Server server = mock(Server.class);
  private final PluginManager manager = mock(PluginManager.class);
  private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
  private final Logger logger = mock(Logger.class);
  private final List<Runnable> tasks = new ArrayList<>();
  private final Player staff = mock(Player.class);
  private final Player player = mock(Player.class);

  @BeforeEach
  void server() {
    pack = directory.resolve("generated.zip");
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(logger);
    when(server.getPluginManager()).thenReturn(manager);
    when(server.getScheduler()).thenReturn(scheduler);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(call -> { tasks.add(call.getArgument(1)); return null; });
    when(staff.hasPermission(PackTextureGuard.PERMISSION)).thenReturn(true);
    doReturn(List.of(staff, player)).when(server).getOnlinePlayers();
  }

  private static byte[] png(int width, int height) {
    return ByteBuffer.allocate(33)
        .putLong(0x89504E470D0A1A0AL).putInt(13).putInt(0x49484452)
        .putInt(width).putInt(height).put(new byte[] {8, 6, 0, 0, 0}).array();
  }

  private void write(Map<String, byte[]> entries, long modified) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(pack))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    Files.setLastModifiedTime(pack, FileTime.fromMillis(modified));
  }

  @Test
  void scanReportsOnlyPngHeadersOverTheLimit() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("assets/", new byte[0]);
    entries.put("pack.mcmeta", "{}".getBytes());
    entries.put(ATLAS, png(16448, 64));
    entries.put("assets/a/textures/tall.png", png(16, 20000));
    entries.put("assets/a/textures/limit.png", png(16384, 16384));
    entries.put("assets/a/textures/short.png", new byte[] {1, 2, 3});
    entries.put("assets/a/textures/not_png.png", new byte[40]);
    byte[] wrongChunk = png(20000, 20000);
    wrongChunk[12] = 'X';
    entries.put("assets/a/textures/wrong_chunk.png", wrongChunk);
    entries.put("assets/a/textures/negative.png", png(-1, 16));
    write(entries, 1000);
    assertEquals(
        List.of(
            new PackTextureGuard.Oversized("assets/a/textures/negative.png", -1, 16),
            new PackTextureGuard.Oversized("assets/a/textures/tall.png", 16, 20000),
            new PackTextureGuard.Oversized(ATLAS, 16448, 64)),
        PackTextureGuard.scan(pack, 16384));
  }

  @Test
  void eachNewPackIsCheckedOnceAndStaffAreWarned() throws IOException {
    BukkitTask timer = mock(BukkitTask.class);
    when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), eq(20L), eq(600L)))
        .thenReturn(timer);
    PackTextureGuard guard = new PackTextureGuard(plugin, pack, 16384);
    verify(manager).registerEvents(guard, plugin);

    // No pack yet, then a pack that is still being written.
    guard.check();
    Files.writeString(pack, "partial");
    guard.check();
    assertTrue(guard.found().isEmpty());

    write(Map.of(ATLAS, png(16448, 64)), 2000);
    guard.check();
    verify(logger).severe("The resource pack has 1 texture over 16384 px (e.g. " + ATLAS + ", 16448x64)."
        + " Players whose GPU allows at most 16384 px (most Intel graphics) cannot load the pack: "
        + ATLAS + " 16448x64");
    assertEquals(1, tasks.size());
    tasks.getFirst().run();
    Component warning = Component.text("[TFMC] The resource pack has 1 texture over 16384 px (e.g. "
        + ATLAS + ", 16448x64). Players on Intel graphics can't load it. See the console.", NamedTextColor.RED);
    verify(staff).sendMessage(warning);
    verify(player, never()).sendMessage(any(Component.class));

    // The same pack is not scanned or reported again; joining staff are told.
    guard.check();
    verify(logger, times(1)).severe(anyString());
    guard.onJoin(new PlayerJoinEvent(staff, (Component) null));
    verify(staff, times(2)).sendMessage(warning);

    write(Map.of(ATLAS, png(16448, 64), "assets/b/textures/wide.png", png(20000, 8)), 3000);
    guard.check();
    verify(logger).severe(startsWith("The resource pack has 2 textures over 16384 px (e.g. assets/b/textures/wide.png"));

    // A fixed pack clears the warning.
    write(Map.of(ATLAS, png(64, 32)), 4000);
    guard.check();
    assertTrue(guard.found().isEmpty());
    guard.onJoin(new PlayerJoinEvent(staff, (Component) null));
    verify(staff, times(2)).sendMessage(warning);
    verify(logger, times(2)).severe(anyString());

    guard.close();
    verify(timer).cancel();
  }

  @Test
  void aPackRewrittenDuringTheScanIsScannedAgain() throws IOException {
    PackTextureGuard guard = new PackTextureGuard(plugin, pack, 16384);
    write(Map.of(ATLAS, png(16448, 64)), 2000);
    try (var scan = org.mockito.Mockito.mockStatic(PackTextureGuard.class, CALLS_REAL_METHODS)) {
      scan.when(() -> PackTextureGuard.scan(pack, 16384)).thenAnswer(call -> {
        Files.setLastModifiedTime(pack, FileTime.fromMillis(2500));
        return List.of();
      });
      guard.check();
    }
    assertTrue(guard.found().isEmpty());
    // The next check scans the final file and reports it.
    guard.check();
    assertEquals(1, guard.found().size());
  }

  @Test
  void aScanThatFinishesAfterDisableReportsNothingAndDisableRacesAreHarmless() throws IOException {
    write(Map.of(ATLAS, png(16448, 64)), 2000);
    PackTextureGuard closed = new PackTextureGuard(plugin, pack, 16384);
    closed.close();
    closed.check();
    assertTrue(closed.found().isEmpty());
    verify(logger, never()).severe(anyString());

    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenThrow(new org.bukkit.plugin.IllegalPluginAccessException("disabled"));
    PackTextureGuard racing = new PackTextureGuard(plugin, pack, 16384);
    assertDoesNotThrow(racing::check);
    verify(logger).severe(anyString());
  }

  @Test
  void closingWithoutATimerIsSafe() {
    assertDoesNotThrow(() -> new PackTextureGuard(plugin, pack, 16384).close());
  }
}

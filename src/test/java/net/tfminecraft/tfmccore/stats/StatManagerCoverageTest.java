package net.tfminecraft.tfmccore.stats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.tfminecraft.tlibs.database.SqliteDatabaseException;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class StatManagerCoverageTest {
  private static final UUID FIRST = UUID.fromString("5a933319-50de-470d-b89d-ac8de4c715df");
  private static final UUID SECOND = UUID.fromString("c49bf356-7a5b-4496-8885-5b2a7111934c");

  @TempDir Path root;
  private final Deque<Runnable> callbacks = new ArrayDeque<>();
  private JavaPlugin plugin;
  private StatsConfig config;
  private BukkitScheduler scheduler;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    assertFalse(StatManager.isInitialized(), "A previous test must release its singleton");
    plugin = mock(JavaPlugin.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    config = new StatsConfig();
    scheduler = mock(BukkitScheduler.class);
    when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              callbacks.addLast(call.getArgument(1));
              return mock(BukkitTask.class);
            });
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    bukkit.when(Bukkit::getLogger).thenReturn(mock(Logger.class));
  }

  @AfterEach
  void cleanup() {
    try {
      if (StatManager.isInitialized()) {
        StatManager.getInstance().shutdown();
      }
    } finally {
      bukkit.close();
    }
  }

  @Test
  void lifecycleRejectsPrematureAccessAndDoubleInitializationAndReopensPersistedStats()
      throws Exception {
    assertEquals(
        "StatManager not initialized",
        assertThrows(IllegalStateException.class, StatManager::getInstance).getMessage());

    StatManager manager = initialize();
    assertTrue(StatManager.isInitialized());
    assertSame(manager, StatManager.getInstance());
    assertTrue(Files.isRegularFile(root.resolve("stats.db")));
    assertEquals(
        "StatManager already initialized",
        assertThrows(IllegalStateException.class, () -> StatManager.init(plugin, config))
            .getMessage());
    assertSame(manager, StatManager.getInstance());

    manager.increment(FIRST, "skills", "dash", 9L);
    assertEquals(0L, storedTotal());
    runNext();
    assertEquals(9L, storedTotal());
    manager.shutdown();
    assertFalse(StatManager.isInitialized());
    assertThrows(IllegalStateException.class, StatManager::getInstance);
    assertEquals(9L, storedTotal(), "Shutdown must preserve the committed database");

    StatManager reopened = initialize();
    assertNotSame(manager, reopened);
    assertEquals(9L, reopened.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(Map.of("dash", 9L), reopened.getServerCategoryTotals("skills"));
  }

  @Test
  void failedDatabaseInitializationLeavesTheSingletonAvailableForRetry() throws Exception {
    Path databasePath = Files.createDirectory(root.resolve("stats.db"));
    assertThrows(SqliteDatabaseException.class, () -> StatManager.init(plugin, config));
    assertFalse(StatManager.isInitialized());
    assertThrows(IllegalStateException.class, StatManager::getInstance);

    Files.delete(databasePath);
    StatManager manager = initialize();
    assertEquals(0L, manager.getServerTotal("skills", "dash"));
    assertTrue(Files.isRegularFile(databasePath));
  }

  @Test
  void signedMutationsAreDeferredToTheirAsyncCallbacksAndPersistTheirResults() throws Exception {
    StatManager manager = initialize();
    manager.increment(FIRST, "skills", "dash", 10L);
    assertEquals(1, callbacks.size());
    assertInstanceOf(BukkitRunnable.class, callbacks.getFirst());
    assertEquals(0L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(0L, storedTotal());
    runNext();
    assertEquals(10L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(10L, storedTotal());

    manager.decrement(FIRST, "skills", "dash", 4L);
    assertEquals(10L, storedTotal());
    runNext();
    assertEquals(6L, manager.getPlayerValue(FIRST, "skills", "dash"));

    manager.adjust(FIRST, "skills", "dash", -2L);
    assertEquals(6L, storedTotal());
    runNext();
    assertEquals(4L, manager.getPlayerValue(FIRST, "skills", "dash"));

    manager.adjust(FIRST, "skills", "dash", 3L);
    assertEquals(4L, storedTotal());
    runNext();
    assertEquals(7L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(7L, storedTotal());
    verify(scheduler, times(4)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
    verifyNoMoreInteractions(scheduler);
    assertTrue(callbacks.isEmpty());
  }

  @Test
  void queriesSeparatePlayersAndCategoriesAndCombineServerTotals() {
    StatManager manager = initialize();
    manager.increment(FIRST, "skills", "dash", 8L);
    manager.increment(FIRST, "skills", "heal", 5L);
    manager.increment(SECOND, "skills", "dash", 3L);
    manager.increment(FIRST, "vehicles", "dash", 12L);
    assertEquals(Map.of(), manager.getPlayerCategory(FIRST, "skills"));
    assertEquals(Map.of(), manager.getServerCategoryTotals("skills"));
    assertEquals(4, callbacks.size());
    while (!callbacks.isEmpty()) {
      runNext();
    }

    assertEquals(8L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(3L, manager.getPlayerValue(SECOND, "skills", "dash"));
    assertEquals(Map.of("dash", 8L, "heal", 5L), manager.getPlayerCategory(FIRST, "skills"));
    assertEquals(Map.of("dash", 3L), manager.getPlayerCategory(SECOND, "skills"));
    assertEquals(11L, manager.getServerTotal("skills", "dash"));
    assertEquals(Map.of("dash", 11L, "heal", 5L), manager.getServerCategoryTotals("skills"));
    assertEquals(Map.of("dash", 12L), manager.getServerCategoryTotals("vehicles"));
    assertEquals(0L, manager.getPlayerValue(SECOND, "skills", "heal"));
    assertEquals(Map.of(), manager.getPlayerCategory(SECOND, "vehicles"));
    assertEquals(0L, manager.getServerTotal("missing", "dash"));
    assertEquals(Map.of(), manager.getServerCategoryTotals("missing"));
  }

  static Stream<Arguments> invalidIdentifiers() {
    return Stream.of(
        Arguments.of(null, "skills", "dash"),
        Arguments.of(FIRST, null, "dash"),
        Arguments.of(FIRST, "", "dash"),
        Arguments.of(FIRST, " \t", "dash"),
        Arguments.of(FIRST, "skills", null),
        Arguments.of(FIRST, "skills", ""),
        Arguments.of(FIRST, "skills", " \t"));
  }

  @ParameterizedTest
  @MethodSource("invalidIdentifiers")
  void invalidIdentifiersCannotScheduleAnyMutation(UUID player, String category, String key)
      throws Exception {
    StatManager manager = initialize();
    manager.increment(player, category, key, 1L);
    manager.decrement(player, category, key, 1L);
    manager.adjust(player, category, key, -1L);

    assertTrue(callbacks.isEmpty());
    verifyNoInteractions(scheduler);
    assertEquals(0L, storedTotal());
    assertEquals(Map.of(), manager.getServerCategoryTotals("skills"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
  void incrementAndDecrementRejectNonPositiveAmounts(long amount) throws Exception {
    StatManager manager = initialize();
    manager.increment(FIRST, "skills", "dash", amount);
    manager.decrement(FIRST, "skills", "dash", amount);
    manager.adjust(FIRST, "skills", "dash", 0L);

    assertTrue(callbacks.isEmpty());
    verifyNoInteractions(scheduler);
    assertEquals(0L, storedTotal());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" \t\n"})
  void invalidQueryIdentifiersReturnDefaultsWithoutDisturbingStoredStats(String blank) {
    StatManager manager = initialize();
    manager.increment(FIRST, "skills", "dash", 7L);
    runNext();

    assertEquals(0L, manager.getPlayerValue(null, "skills", "dash"));
    assertEquals(0L, manager.getPlayerValue(FIRST, blank, "dash"));
    assertEquals(0L, manager.getPlayerValue(FIRST, "skills", blank));
    assertEquals(Map.of(), manager.getPlayerCategory(null, "skills"));
    assertEquals(Map.of(), manager.getPlayerCategory(FIRST, blank));
    assertEquals(0L, manager.getServerTotal(blank, "dash"));
    assertEquals(0L, manager.getServerTotal("skills", blank));
    assertEquals(Map.of(), manager.getServerCategoryTotals(blank));
    assertEquals(7L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(Map.of("dash", 7L), manager.getServerCategoryTotals("skills"));
    assertTrue(callbacks.isEmpty());
  }

  @Test
  void disablingStatsStopsNewMutationsWhilePreviouslyStoredStatsRemainReadable() throws Exception {
    StatManager manager = initialize();
    manager.increment(FIRST, "skills", "dash", 7L);
    runNext();
    Path file = root.resolve("stats.yml");
    Files.writeString(file, "enabled: false\n");
    config.load(file.toFile());
    assertFalse(config.isEnabled());

    manager.increment(FIRST, "skills", "dash", 2L);
    manager.decrement(FIRST, "skills", "dash", 3L);
    manager.adjust(FIRST, "skills", "dash", -4L);
    assertTrue(callbacks.isEmpty());
    assertEquals(7L, manager.getPlayerValue(FIRST, "skills", "dash"));
    assertEquals(Map.of("dash", 7L), manager.getPlayerCategory(FIRST, "skills"));
    assertEquals(7L, manager.getServerTotal("skills", "dash"));
    assertEquals(Map.of("dash", 7L), manager.getServerCategoryTotals("skills"));
    assertEquals(7L, storedTotal());

    Files.writeString(file, "enabled: true\n");
    config.load(file.toFile());
    manager.increment(FIRST, "skills", "dash", 2L);
    assertEquals(1, callbacks.size());
    assertEquals(7L, storedTotal());
    runNext();
    assertEquals(9L, storedTotal());
  }

  private StatManager initialize() {
    StatManager.init(plugin, config);
    return StatManager.getInstance();
  }

  private void runNext() {
    assertFalse(callbacks.isEmpty(), "A mutation must first schedule its asynchronous callback");
    callbacks.removeFirst().run();
  }

  private long storedTotal() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("stats.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT COALESCE(SUM(value), 0) FROM stat_totals")) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}

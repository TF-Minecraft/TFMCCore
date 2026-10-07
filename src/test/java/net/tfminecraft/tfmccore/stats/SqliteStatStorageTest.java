package net.tfminecraft.tfmccore.stats;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.tlibs.database.SqliteDatabase;
import net.tfminecraft.tlibs.database.SqliteDatabaseException;
import net.tfminecraft.tfmccore.stats.storage.SqliteStatStorage;

class SqliteStatStorageTest {
    @ParameterizedTest
    @ValueSource(longs = {-1L, -7L, Long.MIN_VALUE})
    void firstDecrementStartsAtZeroAndLaterUpdatesUseTheOriginalDelta(
            long delta, @TempDir Path tempDir) {
        SqliteStatStorage storage = new SqliteStatStorage(tempDir.resolve("first-decrement.db").toFile());
        UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000003");
        try {
            storage.increment(playerUuid, "rpcharacters", "class_warrior", delta);

            assertEquals(0L, storage.getPlayerValue(playerUuid, "rpcharacters", "class_warrior"));
            assertEquals(Map.of("class_warrior", 0L), storage.getPlayerCategory(playerUuid, "rpcharacters"));
            assertEquals(0L, storage.getServerTotal("rpcharacters", "class_warrior"));
            assertEquals(Map.of("class_warrior", 0L), storage.getServerCategoryTotals("rpcharacters"));

            storage.increment(playerUuid, "rpcharacters", "class_warrior", 7L);
            storage.increment(playerUuid, "rpcharacters", "class_warrior", -2L);
            assertEquals(5L, storage.getPlayerValue(playerUuid, "rpcharacters", "class_warrior"));
            assertEquals(5L, storage.getServerTotal("rpcharacters", "class_warrior"));
        } finally {
            storage.close();
        }
    }

    @Test
    void incrementAccumulatesPlayerAndServerTotals(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("stats.db").toFile();
        SqliteStatStorage storage = new SqliteStatStorage(dbFile);

        UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String category = "test";
        String statKey = "smoke";

        storage.increment(playerUuid, category, statKey, 1L);
        storage.increment(playerUuid, category, statKey, 2L);

        assertEquals(3L, storage.getPlayerValue(playerUuid, category, statKey));
        assertEquals(3L, storage.getServerTotal(category, statKey));

        Map<String, Long> playerCategory = storage.getPlayerCategory(playerUuid, category);
        assertEquals(3L, playerCategory.get(statKey));

        Map<String, Long> serverCategory = storage.getServerCategoryTotals(category);
        assertEquals(3L, serverCategory.get(statKey));

        storage.close();
    }

    @Test
    void decrementAndFloorAtZero(@TempDir Path tempDir) {
        File dbFile = tempDir.resolve("stats-decrement.db").toFile();
        SqliteStatStorage storage = new SqliteStatStorage(dbFile);

        UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String category = "test";
        String statKey = "spread";

        storage.increment(playerUuid, category, statKey, 2L);
        storage.increment(playerUuid, category, statKey, -1L);
        assertEquals(1L, storage.getPlayerValue(playerUuid, category, statKey));

        storage.increment(playerUuid, category, statKey, -5L);
        assertEquals(0L, storage.getPlayerValue(playerUuid, category, statKey));
        assertEquals(0L, storage.getServerTotal(category, statKey));

        storage.close();
    }

    @Test
    void absentStatsReturnZeroAndEmptyCategories(@TempDir Path tempDir) {
        SqliteStatStorage storage = new SqliteStatStorage(tempDir.resolve("empty.db").toFile());
        UUID player = UUID.randomUUID();
        try {
            assertEquals(0L, storage.getPlayerValue(player, "vehicles", "broken"));
            assertEquals(Map.of(), storage.getPlayerCategory(player, "vehicles"));
            assertEquals(0L, storage.getServerTotal("vehicles", "broken"));
            assertEquals(Map.of(), storage.getServerCategoryTotals("vehicles"));
        } finally {
            storage.close();
        }
    }

    @Test
    void totalsCombinePlayersWhileKeepingCategoriesAndKeysSeparate(@TempDir Path tempDir) {
        SqliteStatStorage storage = new SqliteStatStorage(tempDir.resolve("totals.db").toFile());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            storage.increment(first, "skills", "skill_dash", 3L);
            storage.increment(second, "skills", "skill_dash", 5L);
            storage.increment(second, "skills", "skill_dash", -2L);
            storage.increment(first, "skills", "skill_heal", 4L);
            storage.increment(first, "other", "skill_dash", 9L);

            assertEquals(3L, storage.getPlayerValue(first, "skills", "skill_dash"));
            assertEquals(Map.of("skill_dash", 3L, "skill_heal", 4L),
                    storage.getPlayerCategory(first, "skills"));
            assertEquals(6L, storage.getServerTotal("skills", "skill_dash"));
            assertEquals(Map.of("skill_dash", 6L, "skill_heal", 4L),
                    storage.getServerCategoryTotals("skills"));
            assertEquals(Map.of("skill_dash", 9L), storage.getServerCategoryTotals("other"));
        } finally {
            storage.close();
        }
    }

    @Test
    void closedDatabaseLogsFailuresAndReturnsSafeQueryDefaults(@TempDir Path tempDir) {
        SqliteStatStorage storage = new SqliteStatStorage(tempDir.resolve("closed.db").toFile());
        UUID player = UUID.randomUUID();
        storage.increment(player, "skills", "skill_dash", 3L);
        storage.close();

        Logger logger = mock(Logger.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            assertDoesNotThrow(() -> storage.increment(player, "skills", "skill_dash", 1L));
            assertEquals(0L, storage.getPlayerValue(player, "skills", "skill_dash"));
            assertEquals(Map.of(), storage.getPlayerCategory(player, "skills"));
            assertEquals(0L, storage.getServerTotal("skills", "skill_dash"));
            assertEquals(Map.of(), storage.getServerCategoryTotals("skills"));
            assertDoesNotThrow(storage::close);

            verify(logger).warning(startsWith("[TFMCCore] failed to increment stat:"));
            verify(logger).warning(startsWith("[TFMCCore] failed to read player stat:"));
            verify(logger).warning(startsWith("[TFMCCore] failed to read player category stats:"));
            verify(logger).warning(startsWith("[TFMCCore] failed to read server stat total:"));
            verify(logger).warning(startsWith("[TFMCCore] failed to read server category totals:"));
            verifyNoMoreInteractions(logger);
        }
    }

    @Test
    void closeFailureIsLoggedWithoutAbortingShutdown(@TempDir Path tempDir) {
        Logger logger = mock(Logger.class);
        try (var database = mockConstruction(SqliteDatabase.class, (mock, context) ->
                    doThrow(new SqliteDatabaseException("close failed")).when(mock).close());
                var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(logger);
            SqliteStatStorage storage = new SqliteStatStorage(tempDir.resolve("close-failure.db").toFile());

            assertDoesNotThrow(storage::close);

            verify(database.constructed().getFirst()).close();
            verify(logger).warning("[TFMCCore] failed to close stats database: close failed");
        }
    }
}

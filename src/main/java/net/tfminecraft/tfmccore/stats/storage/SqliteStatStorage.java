package net.tfminecraft.tfmccore.stats.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;

import net.tfminecraft.tlibs.database.SqliteDatabase;
import net.tfminecraft.tlibs.database.SqliteDatabaseException;

public final class SqliteStatStorage implements StatStorage {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS stat_totals (
              player_uuid TEXT NOT NULL,
              category    TEXT NOT NULL,
              stat_key    TEXT NOT NULL,
              value       INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (player_uuid, category, stat_key)
            )
            """;

    private static final String UPSERT_INCREMENT = """
            INSERT INTO stat_totals (player_uuid, category, stat_key, value)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(player_uuid, category, stat_key)
            DO UPDATE SET value = MAX(0, stat_totals.value + excluded.value)
            """;

    private final SqliteDatabase database;

    public SqliteStatStorage(File dbFile) {
        database = new SqliteDatabase(dbFile);
        initSchema();
    }

    private void initSchema() {
        database.execute(CREATE_TABLE);
    }

    @Override
    public void increment(UUID playerUuid, String category, String statKey, long delta) {
        try {
            database.executeUpdate(
                    UPSERT_INCREMENT,
                    playerUuid.toString(),
                    category,
                    statKey,
                    delta);
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to increment stat: " + e.getMessage());
        }
    }

    @Override
    public long getPlayerValue(UUID playerUuid, String category, String statKey) {
        try {
            return queryLong(
                    "SELECT value FROM stat_totals WHERE player_uuid = ? AND category = ? AND stat_key = ?",
                    playerUuid.toString(),
                    category,
                    statKey);
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to read player stat: " + e.getMessage());
            return 0L;
        }
    }

    @Override
    public Map<String, Long> getPlayerCategory(UUID playerUuid, String category) {
        try {
            return queryKeyValueMap(
                    "SELECT stat_key, value FROM stat_totals WHERE player_uuid = ? AND category = ?",
                    playerUuid.toString(),
                    category);
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to read player category stats: " + e.getMessage());
            return Map.of();
        }
    }

    @Override
    public long getServerTotal(String category, String statKey) {
        try {
            return queryLong(
                    "SELECT COALESCE(SUM(value), 0) FROM stat_totals WHERE category = ? AND stat_key = ?",
                    category,
                    statKey);
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to read server stat total: " + e.getMessage());
            return 0L;
        }
    }

    @Override
    public Map<String, Long> getServerCategoryTotals(String category) {
        try {
            return queryKeyValueMap(
                    "SELECT stat_key, COALESCE(SUM(value), 0) FROM stat_totals WHERE category = ? GROUP BY stat_key",
                    category);
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to read server category totals: " + e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void close() {
        try {
            database.close();
        } catch (SqliteDatabaseException e) {
            Bukkit.getLogger().warning("[TFMCCore] failed to close stats database: " + e.getMessage());
        }
    }

    private long queryLong(String sql, Object... params) {
        synchronized (database) {
            try (PreparedStatement statement = database.getConnection().prepareStatement(sql)) {
                bindParams(statement, params);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return 0L;
                    }
                    return result.getLong(1);
                }
            } catch (Exception e) {
                throw new SqliteDatabaseException("Failed to query: " + sql, e);
            }
        }
    }

    private Map<String, Long> queryKeyValueMap(String sql, Object... params) {
        Map<String, Long> totals = new HashMap<>();
        synchronized (database) {
            try (PreparedStatement statement = database.getConnection().prepareStatement(sql)) {
                bindParams(statement, params);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        totals.put(result.getString(1), result.getLong(2));
                    }
                }
            } catch (Exception e) {
                throw new SqliteDatabaseException("Failed to query: " + sql, e);
            }
        }
        return totals;
    }

    private static void bindParams(PreparedStatement statement, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }
}

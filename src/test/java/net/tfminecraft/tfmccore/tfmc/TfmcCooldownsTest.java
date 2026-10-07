package net.tfminecraft.tfmccore.tfmc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TfmcCooldownsTest {

    private static final long DAY = 86_400_000L;
    private static final UUID PLAYER = UUID.fromString("036f0c7b-ee8d-4294-9f74-c203c1a1adf0");

    @TempDir
    Path dir;

    private long now = 10 * DAY;

    @Test
    void importsConditionalEventsLastUseTimes() throws Exception {
        Path players = Files.createDirectory(dir.resolve("players"));
        Files.writeString(players.resolve(PLAYER + ".yml"), """
                name: Donor
                events:
                  global_booster:
                    one_time: false
                    cooldown: %d
                  banner_give:
                    one_time: false
                    cooldown: 5
                """.formatted(now - DAY));
        Files.writeString(players.resolve("not-a-uuid.yml"), "events: {}");

        TfmcCooldowns cooldowns = cooldowns();
        assertEquals(1, cooldowns.importConditionalEvents(players.toFile(), "global_booster", "booster"));

        assertEquals(2 * DAY, cooldowns.remaining("booster", PLAYER, 3 * DAY));
    }

    @Test
    void persistentCooldownsSurviveARestart() {
        TfmcCooldowns before = cooldowns();
        before.markUsed("booster", PLAYER);
        before.markUsed("map", PLAYER);
        assertTrue(before.exists());

        TfmcCooldowns after = cooldowns();
        after.load();
        assertEquals(3 * DAY, after.remaining("booster", PLAYER, 3 * DAY));
        assertEquals(0L, after.remaining("map", PLAYER, 10_000L));
    }

    @Test
    void formatsLikeConditionalEvents() {
        assertEquals("2d 23h 59m", TfmcCooldowns.format(3 * DAY - 60_000L));
        assertEquals("10s", TfmcCooldowns.format(9_001L));
        assertEquals("1h 1s", TfmcCooldowns.format(3_601_000L));
    }

    @Test
    void absentStorageKeepsCooldownsInMemoryAndMissingImportsAreEmpty() {
        TfmcCooldowns absent = cooldowns();
        assertFalse(absent.exists());
        absent.load();
        assertEquals(0, absent.importConditionalEvents(dir.resolve("missing").toFile(), "global_booster", "booster"));
        TfmcCooldowns memory = new TfmcCooldowns(null, Set.of("booster"), () -> now);
        memory.load();
        memory.markUsed("booster", PLAYER);
        memory.save();
        assertFalse(memory.exists());
        assertEquals(DAY, memory.remaining("booster", PLAYER, DAY));
    }

    @Test
    void malformedSiblingRecordsDoNotHideAValidPersistentCooldown() throws Exception {
        Files.writeString(dir.resolve("tfmc-cooldowns.yml"), """
                scalar: invalid
                booster:
                  not-a-player-id: 123
                  %s: %d
                """.formatted(PLAYER, now));
        TfmcCooldowns loaded = cooldowns();
        loaded.load();
        assertEquals(DAY, loaded.remaining("booster", PLAYER, DAY));
        assertEquals(0, loaded.remaining("scalar", PLAYER, DAY));
        assertEquals(0, loaded.remaining("booster", PLAYER, 0));
    }

    @Test
    void failedSaveRetainsTheCooldownForARetry() throws Exception {
        Path path = Files.createDirectory(dir.resolve("tfmc-cooldowns.yml"));
        TfmcCooldowns cooldowns = cooldowns();
        cooldowns.markUsed("booster", PLAYER);
        assertTrue(Files.isDirectory(path));
        assertEquals(DAY, cooldowns.remaining("booster", PLAYER, DAY));
        Files.delete(path);
        cooldowns.save();
        TfmcCooldowns restarted = cooldowns();
        restarted.load();
        assertEquals(DAY, restarted.remaining("booster", PLAYER, DAY));
    }

    private TfmcCooldowns cooldowns() {
        File file = dir.resolve("tfmc-cooldowns.yml").toFile();
        return new TfmcCooldowns(file, Set.of("booster"), () -> now);
    }
}

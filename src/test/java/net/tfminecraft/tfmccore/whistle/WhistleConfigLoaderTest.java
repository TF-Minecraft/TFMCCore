package net.tfminecraft.tfmccore.whistle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhistleConfigLoaderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetConfig() throws IOException {
        // WhistleConfig is static/global, so every test starts from the same baseline.
        WhistleConfig.itemPath = "m.pets.animal_whistle";
        WhistleConfig.detectionRadius = 64.0;
        WhistleConfig.glowDuration = 5;
        WhistleConfig.cooldownSeconds = 3;
        WhistleConfig.whitelistedAnimals.clear();
        WhistleConfig.soundName = "ITEM_GOAT_HORN_SOUND_6";
        WhistleConfig.soundVolume = 4.0f;
        WhistleConfig.soundPitch = 2.0f;
        WhistleConfig.highlightedMessage = "&6%count% &aanimals nearby answer your whistle.";
        WhistleConfig.noAnimalsMessage = "&7No animals answer your whistle.";
        WhistleConfig.cooldownMessage = "&7You are still catching your breath. Try again in &6%seconds%s&7.";
    }

    @Test
    void loadsFullConfiguration() throws IOException {
        Path configPath = tempDir.resolve("animal-whistle-config.yml");
        Files.writeString(configPath, """
                items:
                  animal-whistle: "ia.tfmc.animal_whistle"
                settings:
                  detection-radius: 32.5
                  glow-duration: 8
                  cooldown: 10
                  whitelisted-animals:
                    - HORSE
                    - DONKEY
                    - MULE
                    - LLAMA
                    - TRADER_LLAMA
                sound:
                  type: "ITEM_GOAT_HORN_SOUND_6"
                  volume: 2.5
                  pitch: 1.5
                messages:
                  highlighted: "&6%count% &aanimals nearby answer your whistle."
                  no-animals: "&7No animals answer your whistle."
                  cooldown: "&7You are still catching your breath. Try again in &6%seconds%s&7."
                """);

        assertTrue(WhistleConfigLoader.load(configPath.toFile()));

        assertEquals("ia.tfmc.animal_whistle", WhistleConfig.itemPath);
        assertEquals(32.5, WhistleConfig.detectionRadius);
        assertEquals(8, WhistleConfig.glowDuration);
        assertEquals(10, WhistleConfig.cooldownSeconds);
        assertEquals(5, WhistleConfig.whitelistedAnimals.size());
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.HORSE));
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.DONKEY));
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.MULE));
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.LLAMA));
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.TRADER_LLAMA));
        assertEquals("ITEM_GOAT_HORN_SOUND_6", WhistleConfig.soundName);
        assertEquals(2.5f, WhistleConfig.soundVolume);
        assertEquals(1.5f, WhistleConfig.soundPitch);
        assertEquals("&6%count% &aanimals nearby answer your whistle.", WhistleConfig.highlightedMessage);
        assertEquals("&7No animals answer your whistle.", WhistleConfig.noAnimalsMessage);
        assertEquals("&7You are still catching your breath. Try again in &6%seconds%s&7.", WhistleConfig.cooldownMessage);
    }

    @Test
    void skipsUnknownEntityTypes() throws IOException {
        Path configPath = tempDir.resolve("animal-whistle-config.yml");
        Files.writeString(configPath, """
                settings:
                  whitelisted-animals:
                    - HORSE
                    - NOT_A_REAL_ANIMAL
                    - donkey
                """);

        assertTrue(WhistleConfigLoader.load(configPath.toFile()));

        assertEquals(2, WhistleConfig.whitelistedAnimals.size());
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.HORSE));
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.DONKEY));
    }

    @Test
    void emptyWhitelistFallsBackToHorse() throws IOException {
        Path configPath = tempDir.resolve("animal-whistle-config.yml");
        Files.writeString(configPath, """
                settings:
                  whitelisted-animals: []
                """);

        assertTrue(WhistleConfigLoader.load(configPath.toFile()));

        assertEquals(1, WhistleConfig.whitelistedAnimals.size());
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.HORSE));
    }

    @Test
    void missingKeysKeepDefaults() throws IOException {
        Path configPath = tempDir.resolve("animal-whistle-config.yml");
        Files.writeString(configPath, """
                settings:
                  glow-duration: 12
                """);

        assertTrue(WhistleConfigLoader.load(configPath.toFile()));

        assertEquals("m.pets.animal_whistle", WhistleConfig.itemPath);
        assertEquals(64.0, WhistleConfig.detectionRadius);
        assertEquals(12, WhistleConfig.glowDuration);
        assertEquals(3, WhistleConfig.cooldownSeconds);
        assertEquals("ITEM_GOAT_HORN_SOUND_6", WhistleConfig.soundName);
        assertEquals(4.0f, WhistleConfig.soundVolume);
        assertEquals(2.0f, WhistleConfig.soundPitch);
        assertEquals("&6%count% &aanimals nearby answer your whistle.", WhistleConfig.highlightedMessage);
        assertEquals("&7No animals answer your whistle.", WhistleConfig.noAnimalsMessage);
        assertEquals("&7You are still catching your breath. Try again in &6%seconds%s&7.", WhistleConfig.cooldownMessage);
        assertTrue(WhistleConfig.whitelistedAnimals.contains(EntityType.HORSE));
    }
}

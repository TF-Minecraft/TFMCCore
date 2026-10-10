package net.tfminecraft.tfmccore.stones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LorestoneConfigLoaderTest {

    @TempDir
    Path tempDir;

    private String lorestonePath;
    private String namestonePath;
    private List<String> blacklist;
    private int maxLength;
    private int promptTimeoutSeconds;
    private int maxLoreLines;
    private String promptLoreMessage;
    private String promptNameMessage;
    private String appliedLoreMessage;
    private String appliedNameMessage;
    private String cancelledMessage;
    private String timeoutMessage;
    private String itemMovedMessage;
    private String emptyMessage;
    private String tooLongMessage;
    private String tooManyLinesMessage;
    private String cannotApplyMessage;
    private String stackedMessage;
    private String expiredMessage;
    private String gaveMessage;
    private String clearedLoreMessage;
    private String noLoreMessage;
    private String pickColourMessage;
    private String invalidColourMessage;

    @BeforeEach
    void saveConfig() {
        // LorestoneConfig is static/global, so snapshot it and restore after each test.
        lorestonePath = LorestoneConfig.lorestonePath;
        namestonePath = LorestoneConfig.namestonePath;
        blacklist = new ArrayList<>(LorestoneConfig.blacklist);
        maxLength = LorestoneConfig.maxLength;
        promptTimeoutSeconds = LorestoneConfig.promptTimeoutSeconds;
        maxLoreLines = LorestoneConfig.maxLoreLines;
        promptLoreMessage = LorestoneConfig.promptLoreMessage;
        promptNameMessage = LorestoneConfig.promptNameMessage;
        appliedLoreMessage = LorestoneConfig.appliedLoreMessage;
        appliedNameMessage = LorestoneConfig.appliedNameMessage;
        cancelledMessage = LorestoneConfig.cancelledMessage;
        timeoutMessage = LorestoneConfig.timeoutMessage;
        itemMovedMessage = LorestoneConfig.itemMovedMessage;
        emptyMessage = LorestoneConfig.emptyMessage;
        tooLongMessage = LorestoneConfig.tooLongMessage;
        tooManyLinesMessage = LorestoneConfig.tooManyLinesMessage;
        cannotApplyMessage = LorestoneConfig.cannotApplyMessage;
        stackedMessage = LorestoneConfig.stackedMessage;
        expiredMessage = LorestoneConfig.expiredMessage;
        gaveMessage = LorestoneConfig.gaveMessage;
        clearedLoreMessage = LorestoneConfig.clearedLoreMessage;
        noLoreMessage = LorestoneConfig.noLoreMessage;
        pickColourMessage = LorestoneConfig.pickColourMessage;
        invalidColourMessage = LorestoneConfig.invalidColourMessage;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreConfig() {
        LorestoneConfig.lorestonePath = lorestonePath;
        LorestoneConfig.namestonePath = namestonePath;
        LorestoneConfig.blacklist = blacklist;
        LorestoneConfig.maxLength = maxLength;
        LorestoneConfig.promptTimeoutSeconds = promptTimeoutSeconds;
        LorestoneConfig.maxLoreLines = maxLoreLines;
        LorestoneConfig.promptLoreMessage = promptLoreMessage;
        LorestoneConfig.promptNameMessage = promptNameMessage;
        LorestoneConfig.appliedLoreMessage = appliedLoreMessage;
        LorestoneConfig.appliedNameMessage = appliedNameMessage;
        LorestoneConfig.cancelledMessage = cancelledMessage;
        LorestoneConfig.timeoutMessage = timeoutMessage;
        LorestoneConfig.itemMovedMessage = itemMovedMessage;
        LorestoneConfig.emptyMessage = emptyMessage;
        LorestoneConfig.tooLongMessage = tooLongMessage;
        LorestoneConfig.tooManyLinesMessage = tooManyLinesMessage;
        LorestoneConfig.cannotApplyMessage = cannotApplyMessage;
        LorestoneConfig.stackedMessage = stackedMessage;
        LorestoneConfig.expiredMessage = expiredMessage;
        LorestoneConfig.gaveMessage = gaveMessage;
        LorestoneConfig.clearedLoreMessage = clearedLoreMessage;
        LorestoneConfig.noLoreMessage = noLoreMessage;
        LorestoneConfig.pickColourMessage = pickColourMessage;
        LorestoneConfig.invalidColourMessage = invalidColourMessage;
    }

    @Test
    void loadsFullConfiguration() throws IOException {
        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                items:
                  lorestone: "ia.tfmc.lorestone"
                  namestone: "ia.tfmc.namestone"
                settings:
                  blacklist:
                    - "v.bedrock"
                    - "v.barrier"
                  max-length: 200
                  prompt-timeout-seconds: 30
                  max-lore-lines: 5
                messages:
                  prompt-lore: "&aLore?"
                  prompt-name: "&aName?"
                  applied-lore: "&aLore applied."
                  applied-name: "&aName applied."
                  cancelled: "&7Cancelled."
                  timeout: "&7Timed out."
                  item-moved: "&cMoved."
                  empty: "&cEmpty."
                  too-long: "&cToo long."
                  too-many-lines: "&cToo many lines."
                  cannot-apply: "&cCannot apply."
                  stacked: "&cStacked."
                  expired: "&7Expired."
                  gave: "&aGave."
                  cleared-lore: "&aCleared."
                  no-lore: "&cNo lore."
                  pick-colour: "&aColour?"
                  invalid-colour: "&cBad colour."
                """);

        assertTrue(LorestoneConfigLoader.load(configPath.toFile()));

        assertEquals("ia.tfmc.lorestone", LorestoneConfig.lorestonePath);
        assertEquals("ia.tfmc.namestone", LorestoneConfig.namestonePath);
        assertEquals(List.of("v.bedrock", "v.barrier"), LorestoneConfig.blacklist);
        assertEquals(200, LorestoneConfig.maxLength);
        assertEquals(30, LorestoneConfig.promptTimeoutSeconds);
        assertEquals(5, LorestoneConfig.maxLoreLines);
        assertEquals("&aLore?", LorestoneConfig.promptLoreMessage);
        assertEquals("&aName?", LorestoneConfig.promptNameMessage);
        assertEquals("&aLore applied.", LorestoneConfig.appliedLoreMessage);
        assertEquals("&aName applied.", LorestoneConfig.appliedNameMessage);
        assertEquals("&7Cancelled.", LorestoneConfig.cancelledMessage);
        assertEquals("&7Timed out.", LorestoneConfig.timeoutMessage);
        assertEquals("&cMoved.", LorestoneConfig.itemMovedMessage);
        assertEquals("&cEmpty.", LorestoneConfig.emptyMessage);
        assertEquals("&cToo long.", LorestoneConfig.tooLongMessage);
        assertEquals("&cToo many lines.", LorestoneConfig.tooManyLinesMessage);
        assertEquals("&cCannot apply.", LorestoneConfig.cannotApplyMessage);
        assertEquals("&cStacked.", LorestoneConfig.stackedMessage);
        assertEquals("&7Expired.", LorestoneConfig.expiredMessage);
        assertEquals("&aGave.", LorestoneConfig.gaveMessage);
        assertEquals("&aCleared.", LorestoneConfig.clearedLoreMessage);
        assertEquals("&cNo lore.", LorestoneConfig.noLoreMessage);
        assertEquals("&aColour?", LorestoneConfig.pickColourMessage);
        assertEquals("&cBad colour.", LorestoneConfig.invalidColourMessage);
    }

    @Test
    void missingKeysKeepPriorValues() throws IOException {
        LorestoneConfig.lorestonePath = "custom.lorestone.path";
        LorestoneConfig.blacklist = new ArrayList<>(List.of("v.custom"));
        LorestoneConfig.maxLength = 42;

        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                items:
                  namestone: "ia.tfmc.namestone"
                """);

        assertTrue(LorestoneConfigLoader.load(configPath.toFile()));

        // Untouched by the partial file, so the prior values must survive.
        assertEquals("custom.lorestone.path", LorestoneConfig.lorestonePath);
        assertEquals("ia.tfmc.namestone", LorestoneConfig.namestonePath);
        assertEquals(List.of("v.custom"), LorestoneConfig.blacklist);
        assertEquals(42, LorestoneConfig.maxLength);
    }

    @Test
    void missingBlacklistKeepsPriorList() throws IOException {
        LorestoneConfig.blacklist = new ArrayList<>(List.of("v.keepme"));

        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                settings:
                  max-length: 10
                """);

        assertTrue(LorestoneConfigLoader.load(configPath.toFile()));

        assertEquals(List.of("v.keepme"), LorestoneConfig.blacklist);
        assertEquals(10, LorestoneConfig.maxLength);
    }

    @Test
    void belowMinimumSettingsClampToOne() throws IOException {
        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                settings:
                  max-length: 0
                  prompt-timeout-seconds: 0
                  max-lore-lines: 0
                """);

        assertTrue(LorestoneConfigLoader.load(configPath.toFile()));

        assertEquals(1, LorestoneConfig.maxLength);
        assertEquals(1, LorestoneConfig.promptTimeoutSeconds);
        assertEquals(1, LorestoneConfig.maxLoreLines);
    }

    @Test
    void aboveMaximumSettingsClampToMax() throws IOException {
        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                settings:
                  max-length: 1000
                  prompt-timeout-seconds: 9999
                  max-lore-lines: 999
                """);

        assertTrue(LorestoneConfigLoader.load(configPath.toFile()));

        assertEquals(256, LorestoneConfig.maxLength);
        assertEquals(600, LorestoneConfig.promptTimeoutSeconds);
        assertEquals(64, LorestoneConfig.maxLoreLines);
    }

    @Test
    void missingFileReturnsFalseAndLeavesConfigUnchanged() {
        LorestoneConfig.lorestonePath = "unchanged.path";
        LorestoneConfig.maxLength = 77;

        Path missing = tempDir.resolve("does-not-exist.yml");

        assertFalse(LorestoneConfigLoader.load(missing.toFile()));

        assertEquals("unchanged.path", LorestoneConfig.lorestonePath);
        assertEquals(77, LorestoneConfig.maxLength);
    }

    @Test
    void failedLiveReloadReportsTheMissingFileAndKeepsTheCurrentSettings() {
        var plugin = org.mockito.Mockito.mock(net.tfminecraft.tfmccore.TFMCCore.class);
        var logger = org.mockito.Mockito.mock(java.util.logging.Logger.class);
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(logger);
        LorestoneConfig.maxLength = 81;
        try (var core = org.mockito.Mockito.mockStatic(net.tfminecraft.tfmccore.TFMCCore.class)) {
            core.when(net.tfminecraft.tfmccore.TFMCCore::getInstance).thenReturn(plugin);
            assertFalse(LorestoneConfigLoader.load(tempDir.resolve("missing.yml").toFile()));
        }
        assertEquals(81, LorestoneConfig.maxLength);
        org.mockito.Mockito.verify(logger).severe(org.mockito.ArgumentMatchers.contains("Failed to load lorestones-config.yml"));
    }

    @Test
    void malformedYamlReturnsFalseAndLeavesConfigUnchanged() throws IOException {
        LorestoneConfig.lorestonePath = "unchanged.path";
        LorestoneConfig.maxLength = 77;

        Path configPath = tempDir.resolve("lorestones-config.yml");
        Files.writeString(configPath, """
                items: [this is not
                  valid: yaml: :
                """);

        assertFalse(LorestoneConfigLoader.load(configPath.toFile()));

        assertEquals("unchanged.path", LorestoneConfig.lorestonePath);
        assertEquals(77, LorestoneConfig.maxLength);
    }
}

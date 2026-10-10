package net.tfminecraft.tfmccore.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import net.tfminecraft.tfmccore.TFMCCore;
import net.tfminecraft.tfmccore.stones.LorestoneConfig;
import net.tfminecraft.tfmccore.stones.StoneItems;
import net.tfminecraft.tfmccore.stones.StoneListener;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class CoreCommandsCoverageTest {
  private final TFMCCore plugin = mock(TFMCCore.class);
  private final Command command = mock(Command.class);
  private final CommandSender console = mock(CommandSender.class);
  private final Player player = mock(Player.class);
  private final Player target = mock(Player.class);
  private final PlayerInventory playerInventory = mock(PlayerInventory.class);
  private final PlayerInventory targetInventory = mock(PlayerInventory.class);
  private final ItemStack stone = mock(ItemStack.class);
  private final StoneItems items = spy(new StoneItems());
  private final AtomicInteger stoneAmount = new AtomicInteger(7);
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<TFMCCore> core;
  private MockedConstruction<StatsCommand> statsScope;
  private StatsCommand stats;
  private CoreCommands commands;
  private String previousGaveMessage;

  @BeforeEach
  void setUp() {
    previousGaveMessage = LorestoneConfig.gaveMessage;
    LorestoneConfig.gaveMessage = "&aGave %amount%x %stone% to %player%.";
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(() -> Bukkit.getPlayerExact("Target")).thenReturn(target);
    core = mockStatic(TFMCCore.class);
    core.when(TFMCCore::getInstance).thenReturn(plugin);
    core.when(TFMCCore::getStoneItems).thenReturn(items);
    statsScope = mockConstruction(StatsCommand.class);
    commands = new CoreCommands();
    stats = statsScope.constructed().getFirst();
    when(command.getName()).thenReturn("tcore");
    when(player.getName()).thenReturn("Player");
    when(target.getName()).thenReturn("Target");
    when(player.getInventory()).thenReturn(playerInventory);
    when(target.getInventory()).thenReturn(targetInventory);
    when(playerInventory.addItem(stone)).thenReturn(new HashMap<>());
    when(targetInventory.addItem(stone)).thenReturn(new HashMap<>());
    doReturn(stone).when(items).template(any(StoneItems.Kind.class));
    doAnswer(
            call -> {
              stoneAmount.set(call.getArgument(0));
              return null;
            })
        .when(stone)
        .setAmount(org.mockito.ArgumentMatchers.anyInt());
    when(stone.getAmount()).thenAnswer(call -> stoneAmount.get());
  }

  @AfterEach
  void tearDown() {
    if (statsScope != null) statsScope.close();
    if (core != null) core.close();
    if (bukkit != null) bukkit.close();
    LorestoneConfig.gaveMessage = previousGaveMessage;
  }

  @ParameterizedTest
  @ValueSource(strings = {"CONFIG", "STATIONS", "WHISTLE"})
  void reloadTargetsRemainCaseInsensitiveUnderTurkishLocale(String name) {
    Locale original = Locale.getDefault();
    when(console.hasPermission("tfmccore.reload")).thenReturn(true);
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      assertTrue(run(console, "reload", name));

      switch (name) {
        case "CONFIG" -> verify(plugin).reloadConfigFile();
        case "STATIONS" -> verify(plugin).reloadStations();
        case "WHISTLE" -> verify(plugin).reloadWhistleConfig();
        default -> throw new AssertionError(name);
      }
      verify(console, never()).sendMessage(contains("Usage:"));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void unrelatedRegisteredCommandsAreDeclinedWithoutEffects() {
    when(command.getName()).thenReturn("other");

    assertFalse(run(console, "reload"));

    verifyNoInteractions(console, plugin, stats);
  }

  @Test
  void registeredCommandNameIsCaseInsensitiveAndIndependentOfTheTypedLabel() {
    when(command.getName()).thenReturn("TCORE");

    assertTrue(commands.onCommand(console, command, "alias", new String[0]));

    verify(console).sendMessage("§e/tcore stats <category> [player]");
    verify(console, never()).sendMessage(contains("reload"));
    verify(console, never()).sendMessage(contains("stones"));
  }

  @Test
  void unknownArgumentsShowPermissionAppropriateUsage() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "unknown"));

    verify(console).sendMessage("§e/tcore stats <category> [player]");
    verify(console).sendMessage(contains("§e/tcore reload"));
    verify(console).sendMessage(contains("§e/tcore stones give"));
  }

  @Test
  void packWithoutTargetSendsToThePlayerWithoutRequiringAdmin() {
    assertTrue(run(player, "PACK"));

    verify(plugin).sendResourcePack(player);
    verifyNoMoreInteractions(plugin);
  }

  @Test
  void adminCanSendPackToAnOnlineTarget() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "pack", "Target"));

    verify(plugin).sendResourcePack(target);
  }

  @Test
  void ordinaryPlayerCannotSendAnotherPlayersPack() {
    assertTrue(run(player, "pack", "Target"));

    verify(player).sendMessage("Usage: /tcore pack (admins may specify a player)");
    verifyNoInteractions(plugin);
  }

  @Test
  void excessPackArgumentsDoNotSendAnything() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "pack", "Target", "extra"));

    verify(console).sendMessage("Usage: /tcore pack (admins may specify a player)");
    verifyNoInteractions(plugin);
  }

  @Test
  void consoleWithoutPackTargetGetsAnActionableMessage() {
    assertTrue(run(console, "pack"));

    verify(console).sendMessage("Specify an online player: /tcore pack <player>");
    verifyNoInteractions(plugin);
  }

  @Test
  void missingPackTargetDoesNotFallBackToTheSender() {
    when(player.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(player, "pack", "Offline"));

    verify(player).sendMessage("Specify an online player: /tcore pack <player>");
    verifyNoInteractions(plugin);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void statsArgumentsAndResultArePassedToTheStatsCommand(boolean result) {
    when(stats.handle(eq(player), any(String[].class))).thenReturn(result);

    assertEquals(result, run(player, "stats", "vehicles", "Target"));

    verify(stats).handle(player, new String[] {"vehicles", "Target"});
  }

  @Test
  void reloadRequiresEitherReloadOrAdminPermission() {
    assertTrue(run(player, "reload", "all"));

    verify(player).sendMessage("You do not have permission to reload TFMCCore.");
    verifyNoInteractions(plugin);
  }

  @Test
  void adminReloadWithoutTargetReloadsAllConfigurations() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);
    when(plugin.reloadAll()).thenReturn(true);

    assertTrue(run(console, "reload"));

    verify(plugin).reloadAll();
    verify(console).sendMessage("§a[TFMCCore] Reloaded all configs.");
  }

  static Stream<Arguments> reloadCases() {
    return Stream.of(
            Arguments.of("all", "all configs", (Function<TFMCCore, Boolean>) TFMCCore::reloadAll),
            Arguments.of(
                "config", "config", (Function<TFMCCore, Boolean>) TFMCCore::reloadConfigFile),
            Arguments.of("drops", "drops", (Function<TFMCCore, Boolean>) TFMCCore::reloadDrops),
            Arguments.of(
                "stations", "stations", (Function<TFMCCore, Boolean>) TFMCCore::reloadStations),
            Arguments.of(
                "stats", "stats", (Function<TFMCCore, Boolean>) TFMCCore::reloadStatsConfigs),
            Arguments.of(
                "whistle",
                "animal whistle config",
                (Function<TFMCCore, Boolean>) TFMCCore::reloadWhistleConfig),
            Arguments.of(
                "lorestones",
                "lorestones config",
                (Function<TFMCCore, Boolean>) TFMCCore::reloadStonesConfig),
            Arguments.of(
                "tfmc", "/tfmc config", (Function<TFMCCore, Boolean>) TFMCCore::reloadTfmcConfig))
        .flatMap(
            row ->
                Stream.of(true, false)
                    .map(result -> Arguments.of(row.get()[0], row.get()[1], row.get()[2], result)));
  }

  @ParameterizedTest
  @MethodSource("reloadCases")
  void reloadRoutesToTheRequestedConfigurationAndReportsItsResult(
      String targetName, String label, Function<TFMCCore, Boolean> reload, boolean result) {
    when(console.hasPermission("tfmccore.reload")).thenReturn(true);
    when(reload.apply(plugin)).thenReturn(result);

    assertTrue(run(console, "reload", targetName));

    reload.apply(verify(plugin));
    verifyNoMoreInteractions(plugin);
    verify(console)
        .sendMessage(
            result
                ? "§a[TFMCCore] Reloaded " + label + "."
                : "§c[TFMCCore] Reload failed for " + label + ". Check console.");
  }

  @Test
  void unknownReloadTargetDoesNotChangeAnyConfiguration() {
    when(console.hasPermission("tfmccore.reload")).thenReturn(true);

    assertTrue(run(console, "reload", "unknown"));

    verify(console)
        .sendMessage(
            "Usage: /tcore reload [all|config|drops|stations|stats|whistle|lorestones|tfmc]");
    verifyNoInteractions(plugin);
  }

  @Test
  void stoneGiftsRequireAdminPermission() {
    assertTrue(run(player, "stones", "give", "lorestone"));

    verify(player).sendMessage("You do not have permission to use this command.");
    verifyNoInteractions(items, playerInventory, targetInventory);
  }

  @ParameterizedTest
  @ValueSource(strings = {"colour", "COLOR"})
  void anyPlayerCanPickANamestoneColourWithoutAdmin(String subcommand) {
    StoneListener listener = mock(StoneListener.class);
    core.when(TFMCCore::getStoneListener).thenReturn(listener);

    assertTrue(run(player, "stones", subcommand, "gold"));

    verify(listener).chooseColour(player, "gold");
    verify(player, never()).sendMessage(org.mockito.ArgumentMatchers.anyString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void colourPicksFromConsoleOrWithoutStonesAreIgnored(boolean fromConsole) {
    StoneListener listener = mock(StoneListener.class);
    core.when(TFMCCore::getStoneListener).thenReturn(fromConsole ? listener : null);

    assertTrue(run(fromConsole ? console : player, "stones", "colour", "gold"));

    verifyNoInteractions(listener);
  }

  @Test
  void colourWithoutAChoiceFallsBackToTheAdminCheck() {
    assertTrue(run(player, "stones", "colour"));

    verify(player).sendMessage("You do not have permission to use this command.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"stones", "stones take", "stones give"})
  void incompleteOrUnknownStoneSubcommandsShowUsage(String arguments) {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, arguments.split(" ")));

    verify(console)
        .sendMessage("Usage: /tcore stones give <lorestone|namestone> [player] [amount]");
    verifyNoInteractions(items, playerInventory, targetInventory);
  }

  @Test
  void unknownStoneKindIsRejected() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "stones", "give", "unknown"));

    verify(console).sendMessage("Unknown stone: unknown");
    verifyNoInteractions(items);
  }

  @Test
  void consoleMustSpecifyAStoneRecipient() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "stones", "give", "lorestone"));

    verify(console)
        .sendMessage("Console must name a player: /tcore stones give <stone> <player> [amount]");
    verifyNoInteractions(items);
  }

  @Test
  void missingStoneRecipientDoesNotGiveToSomeoneElse() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "stones", "give", "lorestone", "Offline"));

    verify(console).sendMessage("Player not found: Offline");
    verifyNoInteractions(items, playerInventory, targetInventory);
  }

  @ParameterizedTest
  @CsvSource({"LORESTONE, LORE", "NAMESTONE, NAME"})
  void playersCanGiveThemselvesOneStoneByEitherSupportedKind(String name, StoneItems.Kind kind) {
    when(player.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(player, "stones", "GIVE", name));

    verify(items).template(kind);
    verify(playerInventory).addItem(stone);
    assertEquals(1, stone.getAmount());
    verify(player).sendMessage("§aGave 1x " + name.toLowerCase(Locale.ROOT) + " to Player.");
    verifyNoInteractions(targetInventory);
  }

  @ParameterizedTest
  @CsvSource({"-5, 1", "0, 1", "1, 1", "16, 16", "64, 64", "100, 64"})
  void explicitStoneQuantityUsesTheExistingOneToSixtyFourBounds(String requested, int expected) {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "stones", "give", "lorestone", "Target", requested));

    verify(targetInventory).addItem(stone);
    assertEquals(expected, stone.getAmount());
    verify(console).sendMessage("§aGave " + expected + "x lorestone to Target.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-number", "2147483648"})
  void invalidStoneQuantityDoesNotCreateOrGiveAnItem(String amount) {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);

    assertTrue(run(console, "stones", "give", "lorestone", "Target", amount));

    verify(console).sendMessage("Invalid amount: " + amount);
    verifyNoInteractions(items, targetInventory);
  }

  @Test
  void unavailableStoneServiceReportsItsStateWithoutGivingItems() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);
    core.when(TFMCCore::getStoneItems).thenReturn(null);

    assertTrue(run(console, "stones", "give", "lorestone", "Target"));

    verify(console).sendMessage("Lorestones are not initialised.");
    verifyNoInteractions(items, targetInventory);
  }

  @Test
  void unresolvedStoneTemplateReportsAnErrorWithoutGivingItems() {
    when(console.hasPermission("tfmccore.admin")).thenReturn(true);
    doReturn(null).when(items).template(StoneItems.Kind.LORE);

    assertTrue(run(console, "stones", "give", "lorestone", "Target"));

    verify(console).sendMessage("That stone is not configured or could not be resolved.");
    verifyNoInteractions(targetInventory);
  }

  private boolean run(CommandSender sender, String... args) {
    return commands.onCommand(sender, command, "tcore", args);
  }
}

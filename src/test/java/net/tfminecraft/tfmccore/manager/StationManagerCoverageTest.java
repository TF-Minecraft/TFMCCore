package net.tfminecraft.tfmccore.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.tfminecraft.tfmccore.loader.StationLoader;
import net.tfminecraft.tfmccore.reference.Station;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.BlockAPI;
import net.tfminecraft.tlibs.objects.api.subapi.BlockChecker;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class StationManagerCoverageTest {
  @TempDir Path directory;
  private final StationManager manager = new StationManager();
  private Player player;
  private Block block;
  private BlockChecker checker;
  private PlayerInteractEvent event;
  private ConsoleCommandSender console;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<TLibs> tlibs;

  @BeforeEach
  void setup() {
    StationLoader.clear();
    player = mock(Player.class);
    when(player.getName()).thenReturn("Alice");
    block = mock(Block.class);
    checker = mock(BlockChecker.class);
    when(checker.checkBlock(block, "v(crafting_table)")).thenReturn(true);
    BlockAPI api = mock(BlockAPI.class);
    when(api.getChecker()).thenReturn(checker);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getBlockAPI).thenReturn(api);
    console = mock(ConsoleCommandSender.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
    bukkit.when(() -> Bukkit.dispatchCommand(eq(console), anyString())).thenReturn(true);
    event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
    when(event.getClickedBlock()).thenReturn(block);
    when(event.getHand()).thenReturn(EquipmentSlot.HAND);
  }

  @AfterEach
  void cleanup() {
    bukkit.close();
    tlibs.close();
    StationLoader.clear();
  }

  @Test
  void sneakingSelectsTheLaterMatchingClickDefinitionForTheSameBlock() throws IOException {
    load(
        """
        stations:
          ordinary:
            block: v(crafting_table)
            click: right
          sneak:
            block: v(crafting_table)
            click: shift_right
        """);
    assertEquals(
        List.of("ordinary", "sneak"), StationLoader.get().stream().map(Station::getId).toList());
    when(player.isSneaking()).thenReturn(true);

    manager.openStationEvent(event);

    assertOpened("sneak");
  }

  @Test
  void ordinaryClickUsesTheFirstMatchAndOpensOnlyOneStation() throws IOException {
    load(
        """
        stations:
          first:
            block: v(crafting_table)
            click: right
          second:
            block: v(crafting_table)
            click: right
        """);

    manager.openStationEvent(event);

    assertOpened("first");
    verify(checker, times(1)).checkBlock(block, "v(crafting_table)");
  }

  @Test
  void unrelatedDefinitionsDoNotPreventALaterBlockMatch() throws IOException {
    load(
        """
        stations:
          unrelated:
            block: v(brewing_stand)
          matching:
            block: v(crafting_table)
        """);

    manager.openStationEvent(event);

    assertOpened("matching");
    verify(checker).checkBlock(block, "v(brewing_stand)");
    verify(checker).checkBlock(block, "v(crafting_table)");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void noMatchingClickModePreservesVanillaInteraction(boolean sneaking) throws IOException {
    load(
        "stations:\n  only:\n    block: v(crafting_table)\n    click: "
            + (sneaking ? "right" : "shift_right")
            + "\n");
    when(player.isSneaking()).thenReturn(sneaking);

    manager.openStationEvent(event);

    assertVanillaInteraction();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void noStationOrNoMatchingBlockPreservesVanillaInteraction(boolean empty) throws IOException {
    load(empty ? "stations: {}\n" : "stations:\n  different:\n    block: v(brewing_stand)\n");

    manager.openStationEvent(event);

    assertVanillaInteraction();
  }

  @Test
  void offhandDoesNotOpenAStationOrCheckDefinitions() {
    when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
    manager.openStationEvent(event);
    assertVanillaInteraction();
    verifyNoInteractions(checker);
  }

  @ParameterizedTest
  @EnumSource(value = Action.class, mode = EnumSource.Mode.EXCLUDE, names = "RIGHT_CLICK_BLOCK")
  void nonBlockRightClicksAreIgnored(Action action) {
    when(event.getAction()).thenReturn(action);
    manager.openStationEvent(event);
    assertVanillaInteraction();
    verifyNoInteractions(checker);
  }

  @Test
  void missingClickedBlockIsIgnored() {
    when(event.getClickedBlock()).thenReturn(null);
    manager.openStationEvent(event);
    assertVanillaInteraction();
    verifyNoInteractions(checker);
  }

  @Test
  void unspecifiedHandKeepsTheExistingRightClickCompatibility() throws IOException {
    load("stations:\n  compatible:\n    block: v(crafting_table)\n");
    when(event.getHand()).thenReturn(null);
    manager.openStationEvent(event);
    assertOpened("compatible");
  }

  private void load(String configuration) throws IOException {
    Path file = Files.writeString(directory.resolve("stations.yml"), configuration);
    assertTrue(new StationLoader().load(file.toFile()));
  }

  private void assertOpened(String station) {
    bukkit.verify(Bukkit::getConsoleSender);
    bukkit.verify(() -> Bukkit.dispatchCommand(console, "mi stations open " + station + " Alice"));
    bukkit.verifyNoMoreInteractions();
    verify(event, times(1)).setCancelled(true);
    verify(event, never()).setCancelled(false);
  }

  private void assertVanillaInteraction() {
    bukkit.verifyNoInteractions();
    verify(event, never()).setCancelled(anyBoolean());
    verify(event, never()).setUseInteractedBlock(any(Event.Result.class));
    verify(event, never()).setUseItemInHand(any(Event.Result.class));
  }
}

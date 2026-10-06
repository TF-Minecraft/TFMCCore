package net.tfminecraft.tfmccore.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.tfmccore.stats.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class StatsCommandCoverageTest {
  private final StatsCommand command = new StatsCommand();
  private final StatManager manager = mock(StatManager.class);
  private final Player player = mock(Player.class);
  private final UUID playerId = UUID.randomUUID();
  private final List<String> messages = new ArrayList<>();
  private MockedStatic<StatManager> managers;
  private MockedStatic<StatCategoryRegistry> registry;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    managers = mockStatic(StatManager.class);
    managers.when(StatManager::isInitialized).thenReturn(true);
    managers.when(StatManager::getInstance).thenReturn(manager);
    registry = mockStatic(StatCategoryRegistry.class);
    registry.when(StatCategoryRegistry::getCategoryIds).thenReturn(List.of("other", "vehicles"));
    StatCategory other = mock(StatCategory.class);
    when(other.getId()).thenReturn("other");
    StatCategory vehicles = mock(StatCategory.class);
    when(vehicles.getId()).thenReturn("vehicles");
    StatQuery query = mock(StatQuery.class);
    when(query.getLabel("z_stat")).thenReturn("Alpha");
    when(query.getLabel("a_stat")).thenReturn("Zulu");
    when(vehicles.getQuery()).thenReturn(query);
    registry.when(StatCategoryRegistry::getCategories).thenReturn(List.of(other, vehicles));
    bukkit = mockStatic(Bukkit.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.getName()).thenReturn("Alice");
    capture(player);
  }

  @AfterEach
  void close() {
    bukkit.close();
    registry.close();
    managers.close();
  }

  private void capture(CommandSender sender) {
    doAnswer(
            invocation -> {
              messages.add(invocation.getArgument(0));
              return null;
            })
        .when(sender)
        .sendMessage(anyString());
  }

  @Test
  void disabledStatsAndInvalidArgumentsExplainWithoutQueryingStorage() {
    managers.when(StatManager::isInitialized).thenReturn(false);
    assertTrue(command.handle(player, new String[] {"vehicles"}));
    assertEquals(List.of("Stats are not available."), messages);
    managers.when(StatManager::isInitialized).thenReturn(true);
    command.handle(player, new String[0]);
    command.handle(player, new String[] {"missing"});
    assertEquals(
        List.of(
            "Stats are not available.",
            "Usage: /tcore stats <category> [player]",
            "Unknown stat category: missing"),
        messages);
    verifyNoInteractions(manager);
  }

  @Test
  void ordinaryPlayersSeeOnlyTheirOwnSortedLabelledStats() {
    when(manager.getPlayerCategory(playerId, "vehicles"))
        .thenReturn(Map.of("a_stat", 8L, "z_stat", 3L));
    command.handle(player, new String[] {"VEHICLES"});
    assertEquals(List.of("§eAlice - Vehicles stats:", "  Alpha: 3", "  Zulu: 8"), messages);
    verify(manager, never()).getServerCategoryTotals(anyString());
  }

  @Test
  void emptyOwnStatsAndAdminServerTotalsHaveExplicitMessages() {
    when(player.hasPermission("tfmccore.admin")).thenReturn(true);
    command.handle(player, new String[] {"vehicles"});
    assertEquals(
        List.of(
            "§eAlice - Vehicles stats:",
            "No stats recorded for this category.",
            "§eServer totals:",
            "No stats recorded for this category."),
        messages);
  }

  @Test
  void inspectingOtherPlayersRequiresPermissionBeforeResolvingTheirIdentity() {
    command.handle(player, new String[] {"vehicles", "Bob"});
    assertEquals(List.of("You do not have permission to view other players' stats."), messages);
    bukkit.verifyNoInteractions();
    verifyNoInteractions(manager);
  }

  @Test
  void administratorCanInspectNamedAndUncachedOfflinePlayersWithServerTotals() {
    when(player.hasPermission("tfmccore.admin")).thenReturn(true);
    OfflinePlayer target = mock(OfflinePlayer.class);
    UUID targetId = UUID.randomUUID();
    when(target.getUniqueId()).thenReturn(targetId);
    when(target.getName()).thenReturn("Robert");
    bukkit.when(() -> Bukkit.getOfflinePlayer("Bob")).thenReturn(target);
    when(manager.getPlayerCategory(targetId, "vehicles")).thenReturn(Map.of("z_stat", 2L));
    when(manager.getServerCategoryTotals("vehicles"))
        .thenReturn(Map.of("a_stat", 5L, "z_stat", 9L));
    command.handle(player, new String[] {"vehicles", "Bob"});
    assertEquals("§eRobert - Vehicles stats:", messages.getFirst());
    assertEquals(List.of("§eServer totals:", "  Alpha: 9", "  Zulu: 5"), messages.subList(2, 5));
    messages.clear();
    when(target.getName()).thenReturn(null);
    command.handle(player, new String[] {"vehicles", "Bob"});
    assertEquals("§eBob - Vehicles stats:", messages.getFirst());
  }

  @Test
  void consoleTotalsAndMissingOptionalLabelProvidersUseReadableKeys() {
    CommandSender console = mock(CommandSender.class);
    capture(console);
    when(manager.getServerCategoryTotals("other")).thenReturn(Map.of("plain_key", 7L));
    command.handle(console, new String[] {"other"});
    assertEquals(List.of("§eServer totals:", "  Plain Key: 7"), messages);
    messages.clear();
    // The reporting boundary must remain useful if a label provider disappears after resolution.
    registry.when(StatCategoryRegistry::getCategories).thenReturn(List.of());
    command.handle(console, new String[] {"other"});
    assertEquals(List.of("§eServer totals:", "  Plain Key: 7"), messages);
  }
}

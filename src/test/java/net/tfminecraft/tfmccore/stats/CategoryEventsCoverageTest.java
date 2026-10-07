package net.tfminecraft.tfmccore.stats;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.lib.api.event.skill.SkillCastEvent;
import io.lumine.mythic.lib.skill.Skill;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import io.lumine.mythic.lib.skill.trigger.TriggerType;
import java.util.*;
import net.tfminecraft.advancedcrafting.lifecycle.*;
import net.tfminecraft.simplefactions.war.battle.events.BattleEndedEvent;
import net.tfminecraft.tfmccore.stats.categories.advancedcrafting.*;
import net.tfminecraft.tfmccore.stats.categories.factions.*;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.*;
import net.tfminecraft.tfmccore.stats.categories.skills.*;
import net.tfminecraft.tfmccore.stats.categories.vehicles.*;
import net.tfminecraft.vehicleframework.data.VehicleRemovePayload;
import net.tfminecraft.vehicleframework.enums.SeatType;
import net.tfminecraft.vehicleframework.enums.VehicleDeath;
import net.tfminecraft.vehicleframework.events.VehicleRemoveEvent;
import net.tfminecraft.vehicleframework.vehicles.ActiveVehicle;
import net.tfminecraft.vehicleframework.vehicles.seat.Seat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class CategoryEventsCoverageTest {
  private final UUID playerId = UUID.randomUUID();
  private final StatManager manager = mock(StatManager.class);
  private MockedStatic<StatManager> managers;

  @BeforeEach
  void setup() {
    managers = mockStatic(StatManager.class);
    managers.when(StatManager::isInitialized).thenReturn(true);
    managers.when(StatManager::getInstance).thenReturn(manager);
  }

  @AfterEach
  void close() {
    managers.close();
  }

  @Test
  void everyCategoryRegistersOnlyWhenStatsAreReadyAndUsesItsConfiguredLabels() {
    Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
    var pluginManager = plugin.getServer().getPluginManager();
    List<StatCategory> categories =
        List.of(
            new VehiclesStatCategory(new VehiclesStatConfig()),
            new RpCharactersStatCategory(new RpCharactersStatConfig()),
            new AdvancedCraftingStatCategory(new AdvancedCraftingStatConfig()),
            new SkillsStatCategory(new SkillsStatConfig()),
            new FactionsStatCategory(new FactionsStatConfig()));
    assertEquals(
        List.of("vehicles", "rpcharacters", "advancedcrafting", "skills", "factions"),
        categories.stream().map(StatCategory::getId).toList());
    managers.when(StatManager::isInitialized).thenReturn(false);
    categories.forEach(category -> category.register(plugin));
    verifyNoInteractions(pluginManager);
    managers.when(StatManager::isInitialized).thenReturn(true);
    for (StatCategory category : categories) {
      category.register(plugin);
      assertEquals("Example Key", category.getQuery().getLabel("example_key"));
    }
    verify(pluginManager, times(5)).registerEvents(any(Listener.class), eq(plugin));
  }

  @Test
  void craftingEventsCountOnlyKnownPlayersAndNormalizeOptionalCategoriesAndHits() {
    var listener = new AdvancedCraftingStatListener(new AdvancedCraftingStatMain());
    AlloyDiscoveredEvent discovered = mock(AlloyDiscoveredEvent.class);
    AlloyCraftedEvent alloy = mock(AlloyCraftedEvent.class);
    ItemCraftedEvent item = mock(ItemCraftedEvent.class);
    SmithingHitEvent hit = mock(SmithingHitEvent.class);
    Runnable events =
        () -> {
          listener.onAlloyDiscovered(discovered);
          listener.onAlloyCrafted(alloy);
          listener.onItemCrafted(item);
          listener.onSmithingHit(hit);
        };
    managers.when(StatManager::isInitialized).thenReturn(false);
    events.run();
    verifyNoInteractions(discovered, alloy, item, hit, manager);
    managers.when(StatManager::isInitialized).thenReturn(true);
    events.run();
    verifyNoInteractions(manager);
    when(discovered.getPlayerUuid()).thenReturn(playerId);
    when(alloy.getPlayerUuid()).thenReturn(playerId);
    when(item.getPlayerUuid()).thenReturn(playerId);
    when(hit.getPlayerUuid()).thenReturn(playerId);
    events.run();
    verify(manager).increment(playerId, "advancedcrafting", "alloys_discovered", 1L);
    verify(manager).increment(playerId, "advancedcrafting", "alloys_crafted", 1L);
    verify(manager).increment(playerId, "advancedcrafting", "items_crafted", 1L);
    when(item.getCategoryId()).thenReturn(" ");
    when(hit.getHitId()).thenReturn(" ");
    listener.onItemCrafted(item);
    listener.onSmithingHit(hit);
    when(item.getCategoryId()).thenReturn("MITHRIL");
    when(hit.getHitId()).thenReturn("FINISH");
    listener.onItemCrafted(item);
    listener.onSmithingHit(hit);
    verify(manager, times(3)).increment(playerId, "advancedcrafting", "items_crafted", 1L);
    verify(manager).increment(playerId, "advancedcrafting", "items_crafted_mithril", 1L);
    verify(manager).increment(playerId, "advancedcrafting", "hits_finish", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void completedBattlesCountEachNonNullParticipantOnlyWhenThereIsAWinner() {
    var listener = new FactionsStatListener(new FactionsStatMain());
    BattleEndedEvent event = mock(BattleEndedEvent.class);
    managers.when(StatManager::isInitialized).thenReturn(false);
    listener.onBattleEnded(event);
    verifyNoInteractions(event);
    managers.when(StatManager::isInitialized).thenReturn(true);
    listener.onBattleEnded(event);
    verifyNoInteractions(manager);
    when(event.hasWinner()).thenReturn(true);
    UUID other = UUID.randomUUID();
    when(event.getParticipantIds())
        .thenReturn(new LinkedHashSet<>(Arrays.asList(playerId, null, other)));
    listener.onBattleEnded(event);
    verify(manager).increment(playerId, "factions", "battles_joined", 1L);
    verify(manager).increment(other, "factions", "battles_joined", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void skillStatisticsKeepTheCastAndApiTriggerContractAndRequireAHandlerId() {
    var listener = new SkillsStatListener(new SkillsStatMain());
    SkillCastEvent event = mock(SkillCastEvent.class);
    managers.when(StatManager::isInitialized).thenReturn(false);
    listener.onSkillCast(event);
    verifyNoInteractions(event);
    managers.when(StatManager::isInitialized).thenReturn(true);
    listener.onSkillCast(event);
    Skill skill = mock(Skill.class);
    when(event.getCast()).thenReturn(skill);
    listener.onSkillCast(event);
    when(skill.getTrigger()).thenReturn(TriggerType.CAST);
    listener.onSkillCast(event);
    SkillHandler<?> handler = mock(SkillHandler.class);
    doReturn(handler).when(skill).getHandler();
    listener.onSkillCast(event);
    when(handler.getLowerCaseId()).thenReturn(" ");
    listener.onSkillCast(event);
    verifyNoInteractions(manager);
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(event.getPlayer()).thenReturn(player);
    when(handler.getLowerCaseId()).thenReturn("FIREBALL");
    listener.onSkillCast(event);
    when(skill.getTrigger()).thenReturn(TriggerType.API);
    listener.onSkillCast(event);
    verify(manager, times(2)).increment(playerId, "skills", "skill_fireball", 1L);
    verifyNoMoreInteractions(manager);
  }

  @Test
  void onlyAnOccupiedPlayerCaptainGetsTheConfiguredVehicleDeathStatistic() {
    VehiclesStatConfig config = mock(VehiclesStatConfig.class);
    var listener = new VehiclesStatListener(new VehiclesStatMain(config));
    VehicleRemoveEvent event = mock(VehicleRemoveEvent.class);
    managers.when(StatManager::isInitialized).thenReturn(false);
    listener.onVehicleRemove(event);
    verifyNoInteractions(event);
    managers.when(StatManager::isInitialized).thenReturn(true);
    listener.onVehicleRemove(event);
    ActiveVehicle vehicle = mock(ActiveVehicle.class, RETURNS_DEEP_STUBS);
    when(event.getVehicle()).thenReturn(vehicle);
    listener.onVehicleRemove(event);
    VehicleRemovePayload payload = mock(VehicleRemovePayload.class);
    when(event.getPayload()).thenReturn(payload);
    listener.onVehicleRemove(event);
    when(payload.isDeath()).thenReturn(true);
    listener.onVehicleRemove(event);
    when(payload.getDeathCause()).thenReturn(Optional.of(VehicleDeath.SINK));
    when(vehicle.getId()).thenReturn("ship");
    listener.onVehicleRemove(event);
    when(config.resolveStatKey("ship", VehicleDeath.SINK)).thenReturn(Optional.of("ships_sunk"));
    var seats = vehicle.getSeatHandler();
    when(vehicle.getSeatHandler()).thenReturn(null);
    listener.onVehicleRemove(event);
    when(vehicle.getSeatHandler()).thenReturn(seats);
    when(seats.getSeats()).thenReturn(List.of());
    listener.onVehicleRemove(event);
    Seat empty = mock(Seat.class);
    Seat passenger = mock(Seat.class);
    when(passenger.isOccupied()).thenReturn(true);
    when(passenger.getType())
        .thenReturn(
            Arrays.stream(SeatType.values())
                .filter(t -> t != SeatType.CAPTAIN)
                .findFirst()
                .orElseThrow());
    Seat npc = mock(Seat.class);
    when(npc.isOccupied()).thenReturn(true);
    when(npc.getType()).thenReturn(SeatType.CAPTAIN);
    when(npc.getEntity()).thenReturn(mock(Entity.class));
    when(seats.getSeats()).thenReturn(List.of(empty, passenger, npc));
    listener.onVehicleRemove(event);
    verifyNoInteractions(manager);
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    Seat captain = mock(Seat.class);
    when(captain.isOccupied()).thenReturn(true);
    when(captain.getType()).thenReturn(SeatType.CAPTAIN);
    when(captain.getEntity()).thenReturn(player);
    when(seats.getSeats()).thenReturn(List.of(empty, passenger, npc, captain));
    listener.onVehicleRemove(event);
    verify(manager).increment(playerId, "vehicles", "ships_sunk", 1L);
    verifyNoMoreInteractions(manager);
  }
}

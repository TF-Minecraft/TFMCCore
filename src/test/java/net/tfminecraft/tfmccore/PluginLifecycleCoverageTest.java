package net.tfminecraft.tfmccore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.lone.itemsadder.api.ItemsAdder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.LifecycleEventHandler;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.tfminecraft.tfmccore.commands.CoreCommands;
import net.tfminecraft.tfmccore.commands.CoreTabCompletion;
import net.tfminecraft.tfmccore.commands.SilentPermissionCommand;
import net.tfminecraft.tfmccore.loader.ConfigLoader;
import net.tfminecraft.tfmccore.loader.DropLoader;
import net.tfminecraft.tfmccore.loader.StationLoader;
import net.tfminecraft.tfmccore.manager.CoreManager;
import net.tfminecraft.tfmccore.manager.DropManager;
import net.tfminecraft.tfmccore.manager.StationManager;
import net.tfminecraft.tfmccore.resourcepack.MultipartPackService;
import net.tfminecraft.tfmccore.resourcepack.ResourcePackListener;
import net.tfminecraft.tfmccore.stats.StatCategory;
import net.tfminecraft.tfmccore.stats.StatCategoryRegistry;
import net.tfminecraft.tfmccore.stats.StatManager;
import net.tfminecraft.tfmccore.stats.StatsConfig;
import net.tfminecraft.tfmccore.stats.categories.advancedcrafting.AdvancedCraftingStatConfig;
import net.tfminecraft.tfmccore.stats.categories.factions.FactionsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatConfig;
import net.tfminecraft.tfmccore.stats.categories.skills.SkillsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.vehicles.VehiclesStatConfig;
import net.tfminecraft.tfmccore.stones.LorestoneConfigLoader;
import net.tfminecraft.tfmccore.stones.StoneItems;
import net.tfminecraft.tfmccore.stones.StoneListener;
import net.tfminecraft.tfmccore.tfmc.TfmcCommand;
import net.tfminecraft.tfmccore.tfmc.TfmcConfig;
import net.tfminecraft.tfmccore.tfmc.TfmcCooldowns;
import net.tfminecraft.tfmccore.whistle.WhistleConfigLoader;
import net.tfminecraft.tfmccore.whistle.WhistleListener;
import net.tfminecraft.tfmccore.xaero.XaeroFairPlayListener;
import net.tfminecraft.tlibs.database.SqliteProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class PluginLifecycleCoverageTest {
  private static final List<String> CONFIGS =
      List.of(
          "config.yml",
          "drops.yml",
          "stations.yml",
          "stats.yml",
          "vehiclestats.yml",
          "rpcharactersstats.yml",
          "advancedcraftingstats.yml",
          "skillsstats.yml",
          "factionsstats.yml",
          "animal-whistle-config.yml",
          "lorestones-config.yml",
          "tfmc.yml");
  @TempDir Path directory;

  @BeforeAll
  @SuppressWarnings({"rawtypes", "unchecked"})
  static void initializePaperLifecycleTypesWithoutARunningServer() throws Exception {
    Class providerType =
        Class.forName("io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventTypeProvider");
    Object provider = mock(providerType, RETURNS_DEEP_STUBS);
    var providerMethod = providerType.getDeclaredMethod("provider");
    providerMethod.setAccessible(true);
    try (var providers = mockStatic(providerType)) {
      providers.when(() -> providerMethod.invoke(null)).thenReturn(provider);
      assertNotNull(LifecycleEvents.COMMANDS);
    }
  }

  @Test
  void configuredPaperClassLoaderRunsTheRealPluginConstructor() throws Exception {
    Server server = mock(Server.class);
    Logger logger = mock(Logger.class);
    PluginDescriptionFile description =
        new PluginDescriptionFile("TFMCCore", "test", TFMCCore.class.getName());
    try (var bukkit = mockStatic(Bukkit.class);
        var services = mockStatic(net.kyori.adventure.util.Services.class, CALLS_REAL_METHODS);
        var stats = mockStatic(StatManager.class)) {
      bukkit
          .when(Bukkit::getUnsafe)
          .thenReturn(mock(org.bukkit.UnsafeValues.class, RETURNS_DEEP_STUBS));
      services
          .when(() -> net.kyori.adventure.util.Services.service(PluginLoader.class))
          .thenReturn(Optional.of(mock(PluginLoader.class)));
      TestPluginLoader loader = new TestPluginLoader(description, server, logger, directory);

      JavaPlugin plugin =
          (JavaPlugin) loader.loadClass(TFMCCore.class.getName()).getConstructor().newInstance();

      assertSame(plugin, loader.getPlugin());
      assertEquals("TFMCCore", plugin.getName());
      assertEquals(directory.resolve("data").toFile(), plugin.getDataFolder());
      assertSame(description, plugin.getPluginMeta());
      plugin.onDisable();
      stats.verify(StatManager::isInitialized);
    }
  }

  @Test
  void startupRegistersCommandsListenersAndTheActualLifecycleAndQuitCallbacks() throws Exception {
    try (Rig rig = new Rig()) {
      assertNull(TFMCCore.getStoneItems());
      rig.plugin.onDisable();

      rig.plugin.onEnable();

      assertSame(rig.plugin, TFMCCore.getInstance());
      StoneItems stones = TFMCCore.getStoneItems();
      assertNotNull(stones);
      assertSame(stones, rig.stoneItemsArgument);
      verify(rig.coreCommand).setExecutor(rig.coreExecutor);
      verify(rig.coreCommand).setTabCompleter(rig.tabCompletion);
      verify(rig.silentCommand).setExecutor(any(SilentPermissionCommand.class));
      verify(rig.silentCommand).setTabCompleter(any(SilentPermissionCommand.class));
      assertEquals(9, rig.listeners.size());
      assertTrue(rig.listeners.contains(rig.whistles.constructed().getFirst()));
      assertTrue(rig.listeners.contains(rig.stones.constructed().getFirst()));
      rig.xaero.verify(() -> XaeroFairPlayListener.send(rig.player));
      verify(rig.player).updateCommands();
      Commands registrar = mock(Commands.class);
      @SuppressWarnings("unchecked")
      ReloadableRegistrarEvent<Commands> event = mock(ReloadableRegistrarEvent.class);
      when(event.registrar()).thenReturn(registrar);
      rig.commandHandler.run(event);
      verify(registrar).register(rig.commandNode, "TFMC player commands");
      Runnable resultDelivery = mock(Runnable.class);
      rig.commandExecutor.execute(resultDelivery);
      verify(rig.scheduler).runTask(rig.plugin, resultDelivery);
      Listener quit =
          rig.listeners.stream()
              .filter(
                  listener -> listener.getClass().getName().equals(TFMCCore.class.getName() + "$1"))
              .findFirst()
              .orElseThrow();
      var onQuit = quit.getClass().getDeclaredMethod("onQuit", PlayerQuitEvent.class);
      onQuit.setAccessible(true);
      onQuit.invoke(
          quit,
          new PlayerQuitEvent(
              rig.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
      verify(rig.commands.constructed().getFirst()).onQuit(rig.player);

      rig.plugin.onDisable();

      verify(rig.commands.constructed().getFirst()).shutdown();
      verify(rig.stones.constructed().getFirst()).refundAll();
    }
  }

  @Test
  void configCreationCopiesEveryMissingBundledResourceAndPreservesExistingFiles() throws Exception {
    try (Rig rig = new Rig()) {
      Files.writeString(rig.folder.resolve("drops.yml"), "# local custom drops\n");

      rig.plugin.createConfigs();
      rig.plugin.createConfigs();

      assertEquals("# local custom drops\n", Files.readString(rig.folder.resolve("drops.yml")));
      verify(rig.plugin, never()).saveResource("drops.yml", false);
      for (String name : CONFIGS) {
        assertTrue(Files.size(rig.folder.resolve(name)) > 0, name);
        if (!name.equals("drops.yml")) verify(rig.plugin).saveResource(name, false);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"existing", "import", "empty-event", "missing-directory"})
  void startupLoadsOrImportsPersistentCooldownsWithoutResettingExistingPlayers(String scenario)
      throws Exception {
    try (Rig rig = new Rig()) {
      UUID id = UUID.randomUUID();
      long lastUse = System.currentTimeMillis() - 1000;
      Path cooldownFile = rig.folder.resolve("tfmc-cooldowns.yml");
      if (scenario.equals("existing"))
        Files.writeString(cooldownFile, "booster:\n  " + id + ": " + lastUse + "\n");
      if (scenario.equals("import") || scenario.equals("empty-event")) {
        Path players = rig.folder.getParent().resolve("ConditionalEvents/players");
        Files.createDirectories(players);
        Files.writeString(
            players.resolve(id + ".yml"),
            "events:\n  old_booster:\n    cooldown: " + lastUse + "\n");
      }
      if (scenario.equals("empty-event"))
        when(rig.tfmc.string("booster.import-conditionalevents-event")).thenReturn("");

      rig.plugin.onEnable();

      assertTrue(Files.isRegularFile(cooldownFile));
      if (scenario.equals("existing") || scenario.equals("import")) {
        assertTrue(rig.cooldowns.remaining("booster", id, 60_000) > 0);
        assertEquals(
            lastUse,
            YamlConfiguration.loadConfiguration(cooldownFile.toFile()).getLong("booster." + id));
      } else assertEquals(0, rig.cooldowns.remaining("booster", id, 60_000));
      if (scenario.equals("import"))
        verify(rig.logger).info("Imported 1 booster cooldowns from ConditionalEvents");
      else verify(rig.logger, never()).info(startsWith("Imported "));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"disabled", "unavailable", "enabled-no-integrations", "enabled-all-integrations"})
  void statsInitializeOnlyWhenEnabledAndSqliteIsAvailableAndRegisterInstalledCategories(
      String scenario) throws Exception {
    try (Rig rig = new Rig()) {
      when(rig.statsConfig.isEnabled()).thenReturn(!scenario.equals("disabled"));
      rig.sqlite.when(SqliteProvider::isAvailable).thenReturn(!scenario.equals("unavailable"));
      if (scenario.equals("enabled-all-integrations")) {
        for (String name :
            List.of(
                "VehicleFramework",
                "RPCharacters",
                "AdvancedCrafting",
                "MythicLib",
                "SimpleFactions")) {
          when(rig.manager.getPlugin(name)).thenReturn(mock(Plugin.class));
        }
      }

      rig.plugin.onEnable();

      if (scenario.startsWith("enabled")) {
        rig.stats.verify(() -> StatManager.init(rig.plugin, rig.statsConfig));
        rig.registry.verify(() -> StatCategoryRegistry.registerAll(rig.plugin));
        verify(rig.logger).info("stats enabled, db ready");
        if (scenario.endsWith("all-integrations")) {
          ArgumentCaptor<StatCategory> categories = ArgumentCaptor.forClass(StatCategory.class);
          rig.registry.verify(() -> StatCategoryRegistry.register(categories.capture()), times(5));
          assertEquals(
              Set.of("vehicles", "rpcharacters", "advancedcrafting", "skills", "factions"),
              Set.copyOf(categories.getAllValues().stream().map(StatCategory::getId).toList()));
        } else rig.registry.verify(() -> StatCategoryRegistry.register(any()), never());
        rig.stats.when(StatManager::isInitialized).thenReturn(true);
        assertSame(rig.statManager, TFMCCore.getStatManager());
        rig.plugin.onDisable();
        verify(rig.statManager).shutdown();
      } else {
        rig.stats.verify(() -> StatManager.init(any(), any()), never());
        if (scenario.equals("unavailable"))
          verify(rig.logger).warning("SQLite unavailable; stats disabled");
        else rig.sqlite.verifyNoInteractions();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "wrong-version",
        "single-pack",
        "permission-off",
        "locked",
        "lock-default",
        "bad-port",
        "zero-port",
        "multipart"
      })
  void itemsAdderVersionAndSettingsSelectTheSafeDeliveryPath(String scenario) throws Exception {
    try (Rig rig = new Rig()) {
      Plugin ia = mock(Plugin.class);
      Path iaDirectory = rig.folder.getParent().resolve("ItemsAdder");
      Files.createDirectories(iaDirectory);
      when(ia.getDataFolder()).thenReturn(iaDirectory.toFile());
      when(ia.getDescription())
          .thenReturn(
              new PluginDescriptionFile(
                  "ItemsAdder",
                  scenario.equals("wrong-version") ? "4.0.19" : "4.0.18",
                  "test.ItemsAdder"));
      when(rig.manager.getPlugin("ItemsAdder")).thenReturn(ia);
      when(rig.manager.isPluginEnabled("ItemsAdder")).thenReturn(!scenario.equals("absent"));
      int port = scenario.equals("bad-port") ? -1 : scenario.equals("zero-port") ? 0 : 9981;
      Files.writeString(
          rig.folder.resolve("config.yml"),
          "resource-pack:\n  multipart:\n    enabled: "
              + !scenario.equals("single-pack")
              + "\n    port: "
              + port
              + "\n    public-url: https://packs.example.test/\n");
      Files.writeString(
          iaDirectory.resolve("config.yml"),
          "resource-pack:\n  uuid: "
              + UUID.randomUUID()
              + "\n  allow_other_plugins_resourcepacks: "
              + !scenario.equals("permission-off")
              + "\n"
              + (scenario.equals("lock-default")
                  ? ""
                  : "  protect-player:\n    lock-player: " + scenario.equals("locked") + "\n"));
      try (var multipart =
          scenario.equals("multipart")
              ? mockConstruction(
                  MultipartPackService.class,
                  (service, context) -> {
                    assertSame(rig.plugin, context.arguments().get(0));
                    assertEquals(iaDirectory, context.arguments().get(1));
                    assertEquals(9981, ((InetSocketAddress) context.arguments().get(2)).getPort());
                    assertEquals("https://packs.example.test/", context.arguments().get(3));
                  })
              : null) {

        assertDoesNotThrow(rig.plugin::onEnable);
        rig.plugin.sendResourcePack(rig.player);

        if (scenario.equals("multipart")) {
          MultipartPackService service = multipart.constructed().getFirst();
          verify(rig.manager).registerEvents(service, rig.plugin);
          verify(service).send(rig.player);
          rig.plugin.onDisable();
          verify(service).close();
          rig.itemsAdder.verifyNoInteractions();
        } else if (scenario.equals("absent")) {
          verify(rig.player).sendMessage("TFMC resource pack is currently unavailable.");
          assertTrue(rig.listeners.stream().noneMatch(ResourcePackListener.class::isInstance));
          rig.itemsAdder.verifyNoInteractions();
        } else {
          rig.itemsAdder.verify(() -> ItemsAdder.applyResourcepack(rig.player));
          if (scenario.equals("wrong-version"))
            verify(rig.logger).warning(contains("requires verified ItemsAdder 4.0.18"));
          else assertTrue(rig.listeners.stream().anyMatch(ResourcePackListener.class::isInstance));
          if (scenario.equals("permission-off"))
            verify(rig.logger).warning(contains("allow_other_plugins_resourcepacks: true"));
          if (scenario.equals("locked") || scenario.equals("lock-default"))
            verify(rig.logger).warning(contains("lock-player: false"));
          if (scenario.endsWith("port"))
            verify(rig.logger)
                .warning(startsWith("Multipart delivery disabled; using ItemsAdder: "));
        }
      }
    }
  }

  @Test
  void reloadResultsCombineEveryLoaderAndRefreshOnlySuccessfulLiveConfiguration() throws Exception {
    try (Rig rig = new Rig()) {
      rig.plugin.onEnable();
      clearInvocations(
          rig.player,
          rig.configLoader,
          rig.drops,
          rig.stations,
          rig.tfmc,
          rig.whistles.constructed().getFirst());
      when(rig.configLoader.loadConfig(any(File.class))).thenReturn(false);
      when(rig.drops.load(any(File.class))).thenReturn(false);
      when(rig.stations.load(any(File.class))).thenReturn(false);
      when(rig.tfmc.load(any(File.class))).thenReturn(false);
      rig.whistleConfig.when(() -> WhistleConfigLoader.load(any(File.class))).thenReturn(false);
      rig.stoneConfig.when(() -> LorestoneConfigLoader.load(any(File.class))).thenReturn(false);

      assertFalse(rig.plugin.reloadAll());
      assertFalse(rig.plugin.reloadConfigFile());
      assertFalse(rig.plugin.reloadDrops());
      assertFalse(rig.plugin.reloadStations());

      verify(rig.configLoader, times(2)).loadConfig(rig.folder.resolve("config.yml").toFile());
      verify(rig.drops, times(2)).load(rig.folder.resolve("drops.yml").toFile());
      verify(rig.stations, times(2)).load(rig.folder.resolve("stations.yml").toFile());
      verify(rig.tfmc).load(rig.folder.resolve("tfmc.yml").toFile());
      verify(rig.player, never()).updateCommands();
      verify(rig.whistles.constructed().getFirst(), never()).invalidateSound();
      when(rig.configLoader.loadConfig(any(File.class))).thenReturn(true);
      when(rig.drops.load(any(File.class))).thenReturn(true);
      when(rig.stations.load(any(File.class))).thenReturn(true);
      when(rig.tfmc.load(any(File.class))).thenReturn(true);
      rig.whistleConfig.when(() -> WhistleConfigLoader.load(any(File.class))).thenReturn(true);
      rig.stoneConfig.when(() -> LorestoneConfigLoader.load(any(File.class))).thenReturn(true);

      assertTrue(rig.plugin.reloadAll());

      verify(rig.player).updateCommands();
      verify(rig.whistles.constructed().getFirst()).invalidateSound();
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private final class Rig implements AutoCloseable {
    final TFMCCore plugin = mock(TFMCCore.class, CALLS_REAL_METHODS);
    final TFMCCore previous = TFMCCore.getInstance();
    final Path folder = directory.resolve("plugins/TFMCCore");
    final Server server = mock(Server.class);
    final PluginManager manager = mock(PluginManager.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final Logger logger = mock(Logger.class);
    final Player player = mock(Player.class);
    final ConfigLoader configLoader = mock(ConfigLoader.class);
    final DropLoader drops = mock(DropLoader.class);
    final StationLoader stations = mock(StationLoader.class);
    final StatsConfig statsConfig = mock(StatsConfig.class);
    final TfmcConfig tfmc = mock(TfmcConfig.class);
    final StatManager statManager = mock(StatManager.class);
    final CoreCommands coreExecutor = new CoreCommands();
    final CoreTabCompletion tabCompletion = new CoreTabCompletion();
    final PluginCommand coreCommand = mock(PluginCommand.class);
    final PluginCommand silentCommand = mock(PluginCommand.class);
    final List<Listener> listeners = new ArrayList<>();
    final LiteralCommandNode<CommandSourceStack> commandNode =
        LiteralArgumentBuilder.<CommandSourceStack>literal("tfmc").build();
    final LifecycleEventManager<Plugin> lifecycle = mock(LifecycleEventManager.class);
    final MockedStatic<WhistleConfigLoader> whistleConfig;
    final MockedStatic<LorestoneConfigLoader> stoneConfig;
    final MockedStatic<StatManager> stats;
    final MockedStatic<StatCategoryRegistry> registry;
    final MockedStatic<SqliteProvider> sqlite;
    final MockedStatic<XaeroFairPlayListener> xaero;
    final MockedStatic<ItemsAdder> itemsAdder;
    final MockedConstruction<WhistleListener> whistles;
    final MockedConstruction<StoneListener> stones;
    final MockedConstruction<TfmcCommand> commands;
    LifecycleEventHandler<ReloadableRegistrarEvent<Commands>> commandHandler;
    StoneItems stoneItemsArgument;
    TfmcCooldowns cooldowns;
    Executor commandExecutor;

    Rig() throws Exception {
      setField(null, "plugin", null);
      Files.createDirectories(folder);
      doReturn(folder.toFile()).when(plugin).getDataFolder();
      doReturn(server).when(plugin).getServer();
      doReturn(logger).when(plugin).getLogger();
      doReturn(coreCommand).when(plugin).getCommand("tcore");
      doReturn(silentCommand).when(plugin).getCommand("silentpermission");
      doReturn(lifecycle).when(plugin).getLifecycleManager();
      doAnswer(
              call -> {
                String name = call.getArgument(0);
                try (var input = TFMCCore.class.getClassLoader().getResourceAsStream(name)) {
                  assertNotNull(input, "Bundled resource " + name);
                  Files.copy(input, folder.resolve(name));
                }
                return null;
              })
          .when(plugin)
          .saveResource(anyString(), eq(false));
      when(server.getPluginManager()).thenReturn(manager);
      when(server.getScheduler()).thenReturn(scheduler);
      doReturn(List.of(player)).when(server).getOnlinePlayers();
      when(player.isOnline()).thenReturn(true);
      when(configLoader.loadConfig(any())).thenReturn(true);
      when(drops.load(any())).thenReturn(true);
      when(stations.load(any())).thenReturn(true);
      when(statsConfig.loadChecked(any())).thenReturn(true);
      when(tfmc.load(any())).thenReturn(true);
      when(tfmc.string("booster.import-conditionalevents-event")).thenReturn("old_booster");
      doAnswer(
              call -> {
                listeners.add(call.getArgument(0));
                return null;
              })
          .when(manager)
          .registerEvents(any(), eq(plugin));
      doAnswer(
              call -> {
                commandHandler = call.getArgument(1);
                return null;
              })
          .when(lifecycle)
          .registerEventHandler(eq(LifecycleEvents.COMMANDS), any(LifecycleEventHandler.class));
      setField(plugin, "configLoader", configLoader);
      setField(plugin, "dropLoader", drops);
      setField(plugin, "stationLoader", stations);
      setField(plugin, "statsConfig", statsConfig);
      VehiclesStatConfig vehicles = mock(VehiclesStatConfig.class);
      when(vehicles.loadChecked(any())).thenReturn(true);
      setField(plugin, "vehiclesStatConfig", vehicles);
      RpCharactersStatConfig characters = mock(RpCharactersStatConfig.class);
      when(characters.loadChecked(any())).thenReturn(true);
      setField(plugin, "rpCharactersStatConfig", characters);
      AdvancedCraftingStatConfig crafting = mock(AdvancedCraftingStatConfig.class);
      when(crafting.loadChecked(any())).thenReturn(true);
      setField(plugin, "advancedCraftingStatConfig", crafting);
      SkillsStatConfig skills = mock(SkillsStatConfig.class);
      when(skills.loadChecked(any())).thenReturn(true);
      setField(plugin, "skillsStatConfig", skills);
      FactionsStatConfig factions = mock(FactionsStatConfig.class);
      when(factions.loadChecked(any())).thenReturn(true);
      setField(plugin, "factionsStatConfig", factions);
      setField(plugin, "tfmcConfig", tfmc);
      setField(plugin, "coreManager", mock(CoreManager.class));
      setField(plugin, "dropManager", mock(DropManager.class));
      setField(plugin, "stationManager", mock(StationManager.class));
      setField(plugin, "commands", coreExecutor);
      setField(plugin, "tabCompletion", tabCompletion);
      whistleConfig = mockStatic(WhistleConfigLoader.class);
      whistleConfig.when(() -> WhistleConfigLoader.load(any(File.class))).thenReturn(true);
      stoneConfig = mockStatic(LorestoneConfigLoader.class);
      stoneConfig.when(() -> LorestoneConfigLoader.load(any(File.class))).thenReturn(true);
      stats = mockStatic(StatManager.class);
      stats.when(StatManager::getInstance).thenReturn(statManager);
      registry = mockStatic(StatCategoryRegistry.class);
      sqlite = mockStatic(SqliteProvider.class);
      sqlite.when(SqliteProvider::isAvailable).thenReturn(true);
      xaero = mockStatic(XaeroFairPlayListener.class);
      itemsAdder = mockStatic(ItemsAdder.class);
      whistles = mockConstruction(WhistleListener.class);
      stones =
          mockConstruction(
              StoneListener.class,
              (listener, context) ->
                  stoneItemsArgument = (StoneItems) context.arguments().getFirst());
      commands =
          mockConstruction(
              TfmcCommand.class,
              (command, context) -> {
                assertSame(tfmc, context.arguments().get(0));
                cooldowns = (TfmcCooldowns) context.arguments().get(1);
                commandExecutor = (Executor) context.arguments().get(5);
                when(command.build()).thenReturn(commandNode);
              });
    }

    @Override
    public void close() throws Exception {
      commands.close();
      stones.close();
      whistles.close();
      itemsAdder.close();
      xaero.close();
      sqlite.close();
      registry.close();
      stats.close();
      stoneConfig.close();
      whistleConfig.close();
      setField(null, "plugin", previous);
    }
  }

  private static void setField(TFMCCore target, String name, Object value) throws Exception {
    Field field = TFMCCore.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  /** Loads unchanged plugin bytes through Paper's real JavaPlugin initialization contract. */
  private static final class TestPluginLoader extends ClassLoader
      implements ConfiguredPluginClassLoader {
    private final PluginDescriptionFile description;
    private final Server server;
    private final Logger logger;
    private final Path directory;
    private JavaPlugin plugin;

    TestPluginLoader(
        PluginDescriptionFile description, Server server, Logger logger, Path directory) {
      super(TFMCCore.class.getClassLoader());
      this.description = description;
      this.server = server;
      this.logger = logger;
      this.directory = directory;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(TFMCCore.class.getName())) return super.loadClass(name, resolve);
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          if (input == null) throw new ClassNotFoundException(name);
          byte[] bytes = input.readAllBytes();
          loaded = defineClass(name, bytes, 0, bytes.length, TFMCCore.class.getProtectionDomain());
        } catch (IOException error) {
          throw new ClassNotFoundException(name, error);
        }
      }
      if (resolve) resolveClass(loaded);
      return loaded;
    }

    @Override
    public PluginMeta getConfiguration() {
      return description;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean global, boolean libraries)
        throws ClassNotFoundException {
      return loadClass(name, resolve);
    }

    @Override
    public void init(JavaPlugin value) {
      plugin = value;
      value.init(
          server,
          description,
          directory.resolve("data").toFile(),
          directory.resolve("plugin.jar").toFile(),
          this,
          description,
          logger);
    }

    @Override
    public JavaPlugin getPlugin() {
      return plugin;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
      return null;
    }

    @Override
    public void close() {}
  }
}

package net.tfminecraft.tfmccore;

import java.io.File;
import java.time.Clock;
import java.util.Set;
import java.util.Random;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import net.tfminecraft.tlibs.database.SqliteProvider;
import net.tfminecraft.tfmccore.books.BookGlintHider;
import net.tfminecraft.tfmccore.commands.CoreCommands;
import net.tfminecraft.tfmccore.commands.CoreTabCompletion;
import net.tfminecraft.tfmccore.commands.SilentPermissionCommand;
import net.tfminecraft.tfmccore.golem.GolemListener;
import net.tfminecraft.tfmccore.loader.ConfigLoader;
import net.tfminecraft.tfmccore.loader.DropLoader;
import net.tfminecraft.tfmccore.loader.StationLoader;
import net.tfminecraft.tfmccore.manager.CoreManager;
import net.tfminecraft.tfmccore.manager.DropManager;
import net.tfminecraft.tfmccore.manager.PlacedLogTracker;
import net.tfminecraft.tfmccore.manager.StationManager;
import net.tfminecraft.tfmccore.stats.StatCategoryRegistry;
import net.tfminecraft.tfmccore.stats.StatManager;
import net.tfminecraft.tfmccore.stats.StatsConfig;
import net.tfminecraft.tfmccore.stats.categories.advancedcrafting.AdvancedCraftingStatCategory;
import net.tfminecraft.tfmccore.stats.categories.advancedcrafting.AdvancedCraftingStatConfig;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatCategory;
import net.tfminecraft.tfmccore.stats.categories.rpcharacters.RpCharactersStatConfig;
import net.tfminecraft.tfmccore.stats.categories.factions.FactionsStatCategory;
import net.tfminecraft.tfmccore.stats.categories.factions.FactionsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.skills.SkillsStatCategory;
import net.tfminecraft.tfmccore.stats.categories.skills.SkillsStatConfig;
import net.tfminecraft.tfmccore.stats.categories.vehicles.VehiclesStatCategory;
import net.tfminecraft.tfmccore.stats.categories.vehicles.VehiclesStatConfig;
import net.tfminecraft.tfmccore.stones.LorestoneConfigLoader;
import net.tfminecraft.tfmccore.stones.StoneItems;
import net.tfminecraft.tfmccore.stones.StoneListener;
import net.tfminecraft.tfmccore.tfmc.BukkitTfmcActions;
import net.tfminecraft.tfmccore.tfmc.TfmcCommand;
import net.tfminecraft.tfmccore.tfmc.TfmcConfig;
import net.tfminecraft.tfmccore.tfmc.TfmcCooldowns;
import net.tfminecraft.tfmccore.whistle.WhistleConfigLoader;
import net.tfminecraft.tfmccore.whistle.WhistleListener;
import net.tfminecraft.tfmccore.xaero.XaeroFairPlayListener;

public class TFMCCore extends JavaPlugin{
    private static TFMCCore plugin;

    private final ConfigLoader configLoader = new ConfigLoader();
    private final DropLoader dropLoader = new DropLoader();
    private final StationLoader stationLoader = new StationLoader();
    private final StatsConfig statsConfig = new StatsConfig();
    private final VehiclesStatConfig vehiclesStatConfig = new VehiclesStatConfig();
    private final RpCharactersStatConfig rpCharactersStatConfig = new RpCharactersStatConfig();
    private final AdvancedCraftingStatConfig advancedCraftingStatConfig = new AdvancedCraftingStatConfig();
    private final SkillsStatConfig skillsStatConfig = new SkillsStatConfig();
    private final FactionsStatConfig factionsStatConfig = new FactionsStatConfig();
    private final TfmcConfig tfmcConfig = new TfmcConfig();
    private TfmcCommand tfmcCommand;

    private final CoreManager coreManager = new CoreManager();
    private final StationManager stationManager = new StationManager();
    private final DropManager dropManager = new DropManager();

    private final CoreCommands commands = new CoreCommands();
    private final CoreTabCompletion tabCompletion = new CoreTabCompletion();
    private WhistleListener whistleListener;
    private StoneListener stoneListener;
    private StoneItems stoneItems;
    private net.tfminecraft.tfmccore.resourcepack.MultipartPackService multipartPacks;

    @Override
    public void onEnable() {
        plugin = this;
        createConfigs();
        loadConfigs();
        initWhistle();
        initStones();
        initStats();
        registerListeners();
        if (getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
            try {
                // The event precedes ItemsAdder's modern-atlas finalizer. Revalidate
                // its ordering before enabling this transformation on a new version.
                if (!getServer().getPluginManager().getPlugin("ItemsAdder").getDescription().getVersion().equals("4.0.18")) {
                    throw new IllegalStateException("Overlay compaction requires verified ItemsAdder 4.0.18");
                }
                Class.forName("dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent")
                        .getMethod("getEntries");
                getServer().getPluginManager().registerEvents(
                        new net.tfminecraft.tfmccore.resourcepack.ResourcePackListener(getLogger()), this);
                var multipart = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new File(getDataFolder(), "config.yml"));
                if (multipart.getBoolean("resource-pack.multipart.enabled", false)) {
                    var itemsAdder = getServer().getPluginManager().getPlugin("ItemsAdder");
                    var iaConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                            new File(itemsAdder.getDataFolder(), "config.yml"));
                    if (!iaConfig.getBoolean("resource-pack.allow_other_plugins_resourcepacks", false)) {
                        throw new IllegalStateException("Multipart delivery requires ItemsAdder allow_other_plugins_resourcepacks: true");
                    }
                    // IA 4.0.18 locks once per ACCEPTED callback but unlocks only
                    // once per player. Multiple packs leave equipment hidden even
                    // after success (and duplicate entries can survive reconnects).
                    if (iaConfig.getBoolean("resource-pack.protect-player.lock-player", true)) {
                        throw new IllegalStateException("Multipart delivery requires ItemsAdder resource-pack.protect-player.lock-player: false; restart to clear existing equipment locks");
                    }
                    try {
                        multipartPacks = new net.tfminecraft.tfmccore.resourcepack.MultipartPackService(this,
                                itemsAdder.getDataFolder().toPath(), new java.net.InetSocketAddress(multipart.getInt("resource-pack.multipart.port", 9981)),
                                multipart.getString("resource-pack.multipart.public-url", ""));
                        getServer().getPluginManager().registerEvents(multipartPacks, this);
                    } catch (java.io.IOException | IllegalArgumentException error) {
                        getLogger().warning("Multipart delivery disabled; using ItemsAdder: " + error.getMessage());
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | IllegalStateException unavailable) {
                getLogger().warning("ItemsAdder integration unavailable: " + unavailable.getMessage());
            }
        }
        getCommand(commands.cmd1).setExecutor(commands);
        getCommand(commands.cmd1).setTabCompleter(tabCompletion);
        SilentPermissionCommand silentPermission = new SilentPermissionCommand();
        getCommand("silentpermission").setExecutor(silentPermission);
        getCommand("silentpermission").setTabCompleter(silentPermission);
        registerTfmcCommand();
    }

    private void registerTfmcCommand() {
        TfmcCooldowns cooldowns = new TfmcCooldowns(new File(getDataFolder(), "tfmc-cooldowns.yml"),
                Set.of(TfmcCommand.BOOSTER_COOLDOWN), System::currentTimeMillis);
        if (cooldowns.exists()) {
            cooldowns.load();
        } else {
            // First start after the move from ConditionalEvents: keep running booster cooldowns
            String event = tfmcConfig.string("booster.import-conditionalevents-event");
            File players = new File(getDataFolder().getParentFile(), "ConditionalEvents/players");
            if (!event.isEmpty() && players.isDirectory()) {
                int imported = cooldowns.importConditionalEvents(players, event, TfmcCommand.BOOSTER_COOLDOWN);
                getLogger().info("Imported " + imported + " booster cooldowns from ConditionalEvents");
            }
            cooldowns.save();
        }
        TfmcCommand tfmc = tfmcCommand = new TfmcCommand(tfmcConfig, cooldowns, new BukkitTfmcActions(this),
                Clock.systemDefaultZone(), new Random(), task -> getServer().getScheduler().runTask(this, task));
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(tfmc.build(), "TFMC player commands"));
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                tfmc.onQuit(event.getPlayer());
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (tfmcCommand != null) tfmcCommand.shutdown();
        if (multipartPacks != null) multipartPacks.close();
        if (stoneListener != null) {
            stoneListener.refundAll();
        }
        if (StatManager.isInitialized()) {
            StatManager.getInstance().shutdown();
        }
    }

    public void sendResourcePack(org.bukkit.entity.Player player) {
        if (multipartPacks != null) multipartPacks.send(player);
        else if (getServer().getPluginManager().isPluginEnabled("ItemsAdder"))
            dev.lone.itemsadder.api.ItemsAdder.applyResourcepack(player);
        else player.sendMessage("TFMC resource pack is currently unavailable.");
    }

    public static TFMCCore getInstance() {
        return plugin;
    }

    public static StatManager getStatManager() {
        return StatManager.getInstance();
    }

    public boolean loadConfigs() {
        boolean ok = true;
        ok &= configLoader.loadConfig(new File(getDataFolder(), "config.yml"));
        ok &= dropLoader.load(new File(getDataFolder(), "drops.yml"));
        ok &= stationLoader.load(new File(getDataFolder(), "stations.yml"));
        ok &= reloadWhistleConfig();
        ok &= reloadStonesConfig();
        ok &= reloadStatsConfigs();
        ok &= reloadTfmcConfig();
        return ok;
    }

    public boolean reloadTfmcConfig() {
        boolean ok = tfmcConfig.load(new File(getDataFolder(), "tfmc.yml"));
        if (ok) {
            // Permissions are read live; resend the tree so clients see the change
            getServer().getOnlinePlayers().forEach(org.bukkit.entity.Player::updateCommands);
        }
        return ok;
    }

    public boolean reloadAll() {
        return loadConfigs();
    }

    public boolean reloadConfigFile() {
        return configLoader.loadConfig(new File(getDataFolder(), "config.yml"));
    }

    public boolean reloadDrops() {
        return dropLoader.load(new File(getDataFolder(), "drops.yml"));
    }

    public boolean reloadStations() {
        return stationLoader.load(new File(getDataFolder(), "stations.yml"));
    }

    public boolean reloadWhistleConfig() {
        boolean ok = WhistleConfigLoader.load(new File(getDataFolder(), "animal-whistle-config.yml"));
        if (ok && whistleListener != null) {
            whistleListener.invalidateSound();
        }
        return ok;
    }

    private void initWhistle() {
        whistleListener = new WhistleListener();
        getServer().getPluginManager().registerEvents(whistleListener, this);
    }

    public boolean reloadStonesConfig() {
        return LorestoneConfigLoader.load(new File(getDataFolder(), "lorestones-config.yml"));
    }

    private void initStones() {
        stoneItems = new StoneItems();
        stoneListener = new StoneListener(stoneItems);
        getServer().getPluginManager().registerEvents(stoneListener, this);
    }

    public static StoneItems getStoneItems() {
        return plugin == null ? null : plugin.stoneItems;
    }

    public static StoneListener getStoneListener() {
        return plugin == null ? null : plugin.stoneListener;
    }

    public boolean reloadStatsConfigs() {
        boolean ok = statsConfig.loadChecked(new File(getDataFolder(), "stats.yml"));
        ok &= vehiclesStatConfig.loadChecked(new File(getDataFolder(), "vehiclestats.yml"));
        ok &= rpCharactersStatConfig.loadChecked(new File(getDataFolder(), "rpcharactersstats.yml"));
        ok &= advancedCraftingStatConfig.loadChecked(new File(getDataFolder(), "advancedcraftingstats.yml"));
        ok &= skillsStatConfig.loadChecked(new File(getDataFolder(), "skillsstats.yml"));
        ok &= factionsStatConfig.loadChecked(new File(getDataFolder(), "factionsstats.yml"));
        return ok;
    }

    private void initStats() {
        if (!statsConfig.isEnabled()) {
            return;
        }
        if (!SqliteProvider.isAvailable()) {
            getLogger().warning("SQLite unavailable; stats disabled");
            return;
        }

        StatManager.init(this, statsConfig);
        if (getServer().getPluginManager().getPlugin("VehicleFramework") != null) {
            StatCategoryRegistry.register(new VehiclesStatCategory(vehiclesStatConfig));
        }
        if (getServer().getPluginManager().getPlugin("RPCharacters") != null) {
            StatCategoryRegistry.register(new RpCharactersStatCategory(rpCharactersStatConfig));
        }
        if (getServer().getPluginManager().getPlugin("AdvancedCrafting") != null) {
            StatCategoryRegistry.register(new AdvancedCraftingStatCategory(advancedCraftingStatConfig));
        }
        if (getServer().getPluginManager().getPlugin("MythicLib") != null) {
            StatCategoryRegistry.register(new SkillsStatCategory(skillsStatConfig));
        }
        if (getServer().getPluginManager().getPlugin("SimpleFactions") != null) {
            StatCategoryRegistry.register(new FactionsStatCategory(factionsStatConfig));
        }
        StatCategoryRegistry.registerAll(this);
        getLogger().info("stats enabled, db ready");
    }

    public void registerListeners() {
        getServer().getPluginManager().registerEvents(dropManager, this);
        getServer().getPluginManager().registerEvents(new PlacedLogTracker(), this);
        getServer().getPluginManager().registerEvents(stationManager, this);
        getServer().getPluginManager().registerEvents(coreManager, this);
        getServer().getPluginManager().registerEvents(new GolemListener(), this);
        getServer().getPluginManager().registerEvents(new XaeroFairPlayListener(this), this);
        // Covers a plugin reload; on a normal start nobody is online yet
        getServer().getOnlinePlayers().forEach(XaeroFairPlayListener::send);
        BookGlintHider.register(this);
    }

    public void createConfigs() {
        String[] files = {
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
                "tfmc.yml"
        };

        for (String s : files) {
            File newConfigFile = new File(getDataFolder(), s);
            if (!newConfigFile.exists()) {
                newConfigFile.getParentFile().mkdirs();
                saveResource(s, false);
            }
        }
    }
}

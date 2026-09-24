package net.tfminecraft.tfmccore;

import java.io.File;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.tlibs.database.SqliteProvider;
import net.tfminecraft.tfmccore.commands.CoreCommands;
import net.tfminecraft.tfmccore.commands.CoreTabCompletion;
import net.tfminecraft.tfmccore.commands.SilentPermissionCommand;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.tfmccore.focus.FocusConfig;
import net.tfminecraft.tfmccore.focus.FocusService;
import net.tfminecraft.tfmccore.golem.GolemListener;
import net.tfminecraft.tfmccore.itemscan.ItemScanService;
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
import net.tfminecraft.tfmccore.whistle.WhistleConfigLoader;
import net.tfminecraft.tfmccore.whistle.WhistleListener;

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

    private final CoreManager coreManager = new CoreManager();
    private final StationManager stationManager = new StationManager();
    private final DropManager dropManager = new DropManager();

    private final CoreCommands commands = new CoreCommands();
    private final CoreTabCompletion tabCompletion = new CoreTabCompletion();
    private WhistleListener whistleListener;
    private StoneListener stoneListener;
    private StoneItems stoneItems;

    @Override
    public void onEnable() {
        plugin = this;
        createConfigs();
        loadConfigs();
        initFocus();
        initWhistle();
        initStones();
        initStats();
        registerListeners();
        getCommand(commands.cmd1).setExecutor(commands);
        getCommand(commands.cmd1).setTabCompleter(tabCompletion);
        SilentPermissionCommand silentPermission = new SilentPermissionCommand();
        getCommand("silentpermission").setExecutor(silentPermission);
        getCommand("silentpermission").setTabCompleter(silentPermission);
    }

    @Override
    public void onDisable() {
        if (stoneListener != null) {
            stoneListener.refundAll();
        }
        if (StatManager.isInitialized()) {
            StatManager.getInstance().shutdown();
        }
        if (ItemScanService.get() != null) {
            ItemScanService.get().stop();
        }
    }

    public static TFMCCore getInstance() {
        return plugin;
    }

    public static StatManager getStatManager() {
        return StatManager.getInstance();
    }

    /** @deprecated Use RPCharacters.getFocusService() directly. */
    @Deprecated
    public static FocusService getFocusService() {
        if (plugin == null || !plugin.getServer().getPluginManager().isPluginEnabled("RPCharacters")) {
            return null;
        }
        try {
            return FocusService.current();
        } catch (NoSuchMethodError | NoClassDefFoundError ex) {
            // The old provider may also lack the types in the compatibility adapter's signature.
            return null;
        }
    }

    public boolean loadConfigs() {
        boolean ok = true;
        ok &= configLoader.loadConfig(new File(getDataFolder(), "config.yml"));
        ok &= dropLoader.load(new File(getDataFolder(), "drops.yml"));
        ok &= stationLoader.load(new File(getDataFolder(), "stations.yml"));
        ok &= reloadFocusConfig();
        ok &= reloadWhistleConfig();
        ok &= reloadLettersConfig();
        ok &= reloadStonesConfig();
        ok &= reloadStatsConfigs();
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

    public boolean reloadFocusConfig() {
        var owner = getServer().getPluginManager().getPlugin("RPCharacters");
        if (!(owner instanceof RPCharacters characters) || !owner.isEnabled()) return false;
        try {
            boolean ok = characters.reloadFocusConfig();
            if (ok) FocusConfig.refresh();
            return ok;
        } catch (NoSuchMethodError | NoClassDefFoundError ex) {
            getLogger().warning("Character focus requires RPCharacters 2.1.0 or newer.");
            return false;
        }
    }

    public boolean reloadWhistleConfig() {
        boolean ok = WhistleConfigLoader.load(new File(getDataFolder(), "animal-whistle-config.yml"));
        if (ok && whistleListener != null) {
            whistleListener.invalidateSound();
        }
        return ok;
    }

    /** Keep the old admin command without making Core depend on the letters implementation. */
    public boolean reloadLettersConfig() {
        var owner = getServer().getPluginManager().getPlugin("BirdMessenger");
        if (owner == null || !owner.isEnabled()) {
            getLogger().warning("Letters are now owned by BirdMessenger 1.1.0 or newer.");
            return false;
        }
        try {
            return Boolean.TRUE.equals(owner.getClass().getMethod("reloadLettersConfig").invoke(owner));
        } catch (ReflectiveOperationException ex) {
            getLogger().warning("Could not reload BirdMessenger letters: " + ex);
            return false;
        }
    }

    private void initFocus() {
        if (getFocusService() == null) {
            getLogger().warning("Character focus is unavailable; install RPCharacters 2.1.0 or newer "
                    + "and check its focus startup log.");
        }
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

    public boolean reloadStatsConfigs() {
        statsConfig.load(new File(getDataFolder(), "stats.yml"));
        vehiclesStatConfig.load(new File(getDataFolder(), "vehiclestats.yml"));
        rpCharactersStatConfig.load(new File(getDataFolder(), "rpcharactersstats.yml"));
        advancedCraftingStatConfig.load(new File(getDataFolder(), "advancedcraftingstats.yml"));
        skillsStatConfig.load(new File(getDataFolder(), "skillsstats.yml"));
        factionsStatConfig.load(new File(getDataFolder(), "factionsstats.yml"));
        return true;
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
                "lorestones-config.yml"
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

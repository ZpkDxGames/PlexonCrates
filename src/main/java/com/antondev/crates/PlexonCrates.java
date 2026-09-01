package com.antondev.crates;

import com.antondev.crates.command.CratesAdminCommand;
import com.antondev.crates.command.CratesCommand;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Messages;
import com.antondev.crates.config.PluginSettings;
import com.antondev.crates.config.Text;
import com.antondev.crates.gui.MenuService;
import com.antondev.crates.listener.CrateListener;
import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.DisplayService;
import com.antondev.crates.service.KeyService;
import com.antondev.crates.service.LocationStore;
import com.antondev.crates.service.OpeningLog;
import com.antondev.crates.service.OpeningService;
import com.antondev.crates.service.StatsStore;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class PlexonCrates extends JavaPlugin {
    private PluginSettings settings;
    private Messages messages;
    private MenuConfig menusConfig;
    private CrateRegistry crates;
    private LocationStore locations;
    private KeyService keys;
    private StatsStore statistics;
    private DisplayService displays;
    private MenuService menus;
    private OpeningLog openingLog;
    private OpeningService openings;
    private BukkitTask statisticsTask;

    @Override
    public void onEnable() {
        try {
            saveBundledFiles();
            settings = PluginSettings.load(file("config.yml"));
            messages = Messages.load(file("messages.yml"));
            menusConfig = MenuConfig.load(file("menus.yml"));
            CrateRegistry.Snapshot crateSnapshot = CrateRegistry.load(getDataFolder().toPath().resolve("crates"));
            crates = new CrateRegistry(getDataFolder().toPath().resolve("crates"), crateSnapshot);
            LocationStore.Snapshot locationSnapshot = LocationStore.load(getDataFolder().toPath().resolve("locations.yml"), crates);
            locations = new LocationStore(getDataFolder().toPath().resolve("locations.yml"), locationSnapshot);
            KeyService.Snapshot keySnapshot = KeyService.load(file(settings.fallbackFile()));
            keys = new KeyService(this, keySnapshot);
            statistics = new StatsStore(getDataFolder().toPath().resolve("statistics.yml"));
            openingLog = new OpeningLog(this);
            displays = new DisplayService(this);
            menus = new MenuService(this);
            openings = new OpeningService(this, openingLog);

            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(new CrateListener(this), this);
            registerCommands();
            scheduleStatistics();
            displays.refresh();
            getLogger().info("PlexonCrates " + getPluginMeta().getVersion() + " by Tonim (ZpkDxGames) enabled: "
                    + crates.all().size() + " crates, " + crates.rewardCount() + " rewards, " + locations.all().size()
                    + " linked blocks. Key source: " + keys.sourceLabel() + ".");
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "PlexonCrates could not start. Existing files were not reset.", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (statisticsTask != null) statisticsTask.cancel();
        if (menus != null) menus.closeAll();
        if (displays != null) displays.stop();
        if (openings != null) openings.clear();
        if (statistics != null) {
            try { statistics.save(); }
            catch (Exception error) { getLogger().log(Level.SEVERE, "Final crate statistics save failed", error); }
        }
        if (openingLog != null) openingLog.close();
    }

    public boolean reloadFor(CommandSender sender) {
        try {
            PluginSettings nextSettings = PluginSettings.load(file("config.yml"));
            Messages nextMessages = Messages.load(file("messages.yml"));
            MenuConfig nextMenus = MenuConfig.load(file("menus.yml"));
            CrateRegistry.Snapshot nextCrates = CrateRegistry.load(getDataFolder().toPath().resolve("crates"));
            CrateRegistry validationRegistry = new CrateRegistry(getDataFolder().toPath().resolve("crates"), nextCrates);
            LocationStore.Snapshot nextLocations = LocationStore.load(getDataFolder().toPath().resolve("locations.yml"), validationRegistry);
            KeyService.Snapshot nextKeys = KeyService.load(new File(getDataFolder(), nextSettings.fallbackFile()));

            settings = nextSettings;
            messages = nextMessages;
            menusConfig = nextMenus;
            crates.apply(nextCrates);
            locations.apply(nextLocations);
            keys.apply(nextKeys);
            menus.closeAll();
            scheduleStatistics();
            displays.refresh();
            messages.send(sender, "reloaded");
            return true;
        } catch (Exception error) {
            configError(sender, error);
            return false;
        }
    }

    public void configError(CommandSender sender, Exception error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (messages != null) messages.send(sender, "config-error", Text.value("error", reason));
        getLogger().log(Level.WARNING, "Configuration change rejected: " + reason, error);
    }

    private void saveBundledFiles() throws Exception {
        saveDefaultConfig();
        Files.createDirectories(getDataFolder().toPath().resolve("crates"));
        for (String resource : List.of("messages.yml", "menus.yml", "keys.yml",
                "crates/basic.yml", "crates/rare.yml", "crates/epic.yml", "crates/legendary.yml")) {
            if (!new File(getDataFolder(), resource).exists()) saveResource(resource, false);
        }
    }

    private void registerCommands() {
        CratesCommand player = new CratesCommand(this);
        var cratesCommand = Objects.requireNonNull(getCommand("crates"));
        cratesCommand.setExecutor(player);
        cratesCommand.setTabCompleter(player);
        CratesAdminCommand admin = new CratesAdminCommand(this);
        var adminCommand = Objects.requireNonNull(getCommand("cratesadmin"));
        adminCommand.setExecutor(admin);
        adminCommand.setTabCompleter(admin);
    }

    private void scheduleStatistics() {
        if (statisticsTask != null) statisticsTask.cancel();
        long ticks = settings.statisticsSaveSeconds() * 20L;
        statisticsTask = getServer().getScheduler().runTaskTimer(this, () -> {
            try { statistics.save(); }
            catch (Exception error) { getLogger().log(Level.WARNING, "Could not save crate statistics", error); }
        }, ticks, ticks);
    }

    private File file(String name) {
        return new File(getDataFolder(), name);
    }

    public PluginSettings settings() { return settings; }
    public Messages messages() { return messages; }
    public MenuConfig menusConfig() { return menusConfig; }
    public CrateRegistry crates() { return crates; }
    public LocationStore locations() { return locations; }
    public KeyService keys() { return keys; }
    public StatsStore statistics() { return statistics; }
    public DisplayService displays() { return displays; }
    public MenuService menus() { return menus; }
    public OpeningService openings() { return openings; }
}

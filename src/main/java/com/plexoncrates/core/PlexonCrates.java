package com.plexoncrates.core;

import com.plexoncrates.command.CrateCommand;
import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.listener.BlockProtectionListener;
import com.plexoncrates.listener.InventoryListener;
import com.plexoncrates.listener.PlayerInteractListener;
import com.plexoncrates.listener.PlayerLifecycleListener;
import com.plexoncrates.manager.AnimationManager;
import com.plexoncrates.manager.CrateManager;
import com.plexoncrates.manager.GUIManager;
import com.plexoncrates.manager.KeyManager;
import com.plexoncrates.manager.OpeningManager;
import com.plexoncrates.manager.RewardExecutor;
import com.plexoncrates.migration.PhoenixMigrationService;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlexonCrates extends JavaPlugin {
    private ConfigManager configManager;
    private AsyncExecutor asyncExecutor;
    private DatabaseManager database;
    private CrateManager crates;
    private GUIManager gui;
    private KeyManager keys;
    private RewardExecutor rewardExecutor;
    private AnimationManager animations;
    private OpeningManager openings;
    private PhoenixMigrationService phoenixMigration;

    @Override
    public void onEnable() {
        try {
            configManager = new ConfigManager(this);
            configManager.initialize();

            asyncExecutor = new AsyncExecutor(configManager.databasePoolSize() + 1);
            database = new DatabaseManager(this, configManager, asyncExecutor.executor());

            crates = new CrateManager(this, asyncExecutor.executor());
            crates.load();

            gui = new GUIManager(this, configManager, crates, database);
            keys = new KeyManager(configManager, database);
            rewardExecutor = new RewardExecutor(this, configManager, database);
            animations = new AnimationManager(this, configManager, crates, gui, rewardExecutor);
            openings = new OpeningManager(this, configManager, keys, animations);
            phoenixMigration = new PhoenixMigrationService(this, crates, database, asyncExecutor.executor());

            registerCommand();
            registerListeners();
            animations.startIdleEffects();

            database.ready().whenComplete((ignored, error) -> {
                if (error != null) getLogger().log(Level.SEVERE, "Database initialization failed", error);
            });

            getLogger().info("PlexonCrates " + getDescription().getVersion()
                    + " enabled with " + crates.all().size() + " crate(s).");
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "PlexonCrates failed to enable", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (animations != null) animations.shutdown();
        if (asyncExecutor != null) asyncExecutor.close();
    }

    public void reloadPlugin() {
        configManager.reload();
        crates.load();
        animations.startIdleEffects();
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("crates"), "crates command missing from plugin.yml");
        CrateCommand executor = new CrateCommand(this, configManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        PluginManager manager = getServer().getPluginManager();
        manager.registerEvents(new InventoryListener(gui), this);
        manager.registerEvents(new PlayerInteractListener(this, configManager, crates), this);
        manager.registerEvents(new BlockProtectionListener(this, crates), this);
        manager.registerEvents(new PlayerLifecycleListener(this), this);
    }

    public ConfigManager settings() { return configManager; }
    public DatabaseManager database() { return database; }
    public CrateManager crates() { return crates; }
    public GUIManager gui() { return gui; }
    public KeyManager keys() { return keys; }
    public AnimationManager animations() { return animations; }
    public OpeningManager openings() { return openings; }
    public PhoenixMigrationService phoenixMigration() { return phoenixMigration; }
}

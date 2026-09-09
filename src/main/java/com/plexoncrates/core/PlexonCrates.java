package com.plexoncrates.core;

import com.plexoncrates.command.CrateCommand;
import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.database.DatabaseCompatibility;
import com.plexoncrates.database.DatabaseManager;
import com.plexoncrates.database.DatabaseSchemaVersion;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Main plugin lifecycle for PlexonCrates 4.5.0. */
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
    private CompletableFuture<Void> bootstrapTask;
    private volatile boolean runtimeReady;
    private volatile Throwable healthFailure;

    @Override
    public void onEnable() {
        try {
            configManager = new ConfigManager(this);
            configManager.initialize();

            asyncExecutor = new AsyncExecutor(configManager.databasePoolSize() + 1);
            registerBootstrapCommand();

            // Database inspection, backup, and legacy-table repair can be expensive. Never execute
            // VACUUM/SQLite compatibility work on the Paper server thread during plugin enable.
            getLogger().info("Starting asynchronous PlexonCrates database preflight...");
            bootstrapTask = CompletableFuture.runAsync(() -> {
                try {
                    DatabaseCompatibility.prepare(this, configManager);
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            }, asyncExecutor.executor());

            bootstrapTask.whenComplete((ignored, error) -> {
                Throwable preflightFailure = unwrap(error);
                if (!isEnabled()) return;
                try {
                    getServer().getScheduler().runTask(this, () -> finishEnable(preflightFailure));
                } catch (Throwable schedulingFailure) {
                    getLogger().log(Level.SEVERE, "Could not schedule PlexonCrates runtime initialization", schedulingFailure);
                }
            });
        } catch (Throwable error) {
            failEnable("bootstrap", error);
        }
    }

    private void finishEnable(Throwable preflightFailure) {
        if (!isEnabled() || runtimeReady) return;
        try {
            if (preflightFailure != null) {
                healthFailure = preflightFailure;
                getLogger().log(Level.SEVERE,
                        "SQLite compatibility preflight failed. PlexonCrates will start in degraded mode; "
                                + "database-backed operations will remain unavailable unless SQLite initializes cleanly.",
                        preflightFailure);
            }

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

            try {
                animations.startIdleEffects();
            } catch (Throwable effectFailure) {
                healthFailure = effectFailure;
                getLogger().log(Level.SEVERE,
                        "Idle effects could not start. Core crate commands and administration remain available.",
                        effectFailure);
            }

            observeDatabaseHealth();
            runtimeReady = true;
            getLogger().info("PlexonCrates " + getPluginMeta().getVersion()
                    + " runtime initialized with " + crates.all().size() + " crate(s)."
                    + (preflightFailure == null ? "" : " Database preflight is DEGRADED."));
        } catch (Throwable error) {
            failEnable("runtime initialization", error);
        }
    }

    private void observeDatabaseHealth() {
        database.ready().whenComplete((ignored, error) -> {
            if (error != null) {
                healthFailure = unwrap(error);
                getLogger().log(Level.SEVERE,
                        "PlexonCrates SQLite backend is unavailable. GUI definitions remain loaded, but "
                                + "virtual keys, claims, statistics and history are disabled until the database is repaired.",
                        unwrap(error));
                return;
            }
            try {
                DatabaseSchemaVersion.markCurrent(
                        getDataFolder().toPath().resolve(configManager.databaseFile()).normalize(),
                        getPluginMeta().getVersion(),
                        configManager.busyTimeoutMillis());
                getLogger().info("SQLite schema version " + DatabaseSchemaVersion.CURRENT_VERSION + " is active.");
            } catch (Exception schemaError) {
                healthFailure = schemaError;
                getLogger().log(Level.SEVERE, "Could not persist SQLite schema version metadata", schemaError);
            }
        });
    }

    @Override
    public void onDisable() {
        runtimeReady = false;
        if (animations != null) {
            try {
                animations.shutdown();
            } catch (Throwable error) {
                getLogger().log(Level.SEVERE, "Animation shutdown failed", error);
            }
        }

        if (crates != null) {
            try {
                crates.pendingSaves().get(3L, TimeUnit.SECONDS);
            } catch (Exception error) {
                getLogger().log(Level.WARNING, "Timed out while flushing crate-definition saves", error);
            }
        }

        if (asyncExecutor != null) asyncExecutor.close();
    }

    public void reloadPlugin() {
        if (!runtimeReady) throw new IllegalStateException("PlexonCrates is still initializing");
        configManager.reload();
        crates.load();
        try {
            animations.startIdleEffects();
        } catch (Throwable error) {
            healthFailure = error;
            getLogger().log(Level.SEVERE, "Idle effects failed after reload", error);
        }
    }

    private void registerBootstrapCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("crates"), "crates command missing from plugin.yml");
        command.setExecutor((sender, ignoredCommand, ignoredLabel, ignoredArgs) -> {
            sender.sendMessage("§8[§6PlexonCrates§8] §ePlexonCrates is initializing its data layer. Try again shortly.");
            return true;
        });
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("crates"), "crates command missing from plugin.yml");
        CrateCommand executor = new CrateCommand(this, configManager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        PluginManager manager = getServer().getPluginManager();
        manager.registerEvents(new InventoryListener(this, gui), this);
        manager.registerEvents(new PlayerInteractListener(this, configManager, crates), this);
        manager.registerEvents(new BlockProtectionListener(this, crates), this);
        manager.registerEvents(new PlayerLifecycleListener(this), this);
    }

    private void failEnable(String phase, Throwable error) {
        healthFailure = unwrap(error);
        getLogger().log(Level.SEVERE, "PlexonCrates failed during " + phase, unwrap(error));
        try {
            getServer().getPluginManager().disablePlugin(this);
        } catch (Throwable disableFailure) {
            getLogger().log(Level.SEVERE, "PlexonCrates could not disable cleanly after startup failure", disableFailure);
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) return null;
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public boolean runtimeReady() { return runtimeReady; }
    public boolean databaseReady() { return database != null && database.isReady(); }
    public Throwable healthFailure() { return healthFailure; }
    public ConfigManager settings() { return configManager; }
    public DatabaseManager database() { return database; }
    public CrateManager crates() { return crates; }
    public GUIManager gui() { return gui; }
    public KeyManager keys() { return keys; }
    public AnimationManager animations() { return animations; }
    public OpeningManager openings() { return openings; }
    public PhoenixMigrationService phoenixMigration() { return phoenixMigration; }
}

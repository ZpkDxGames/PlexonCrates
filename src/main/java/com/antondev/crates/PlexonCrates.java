package com.antondev.crates;

import com.antondev.crates.command.CratesAdminCommand;
import com.antondev.crates.api.PlexonCratesApi;
import com.antondev.crates.api.PlexonCratesApiImpl;
import com.antondev.crates.command.CratesCommand;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Messages;
import com.antondev.crates.config.PluginSettings;
import com.antondev.crates.config.Text;
import com.antondev.crates.gui.GuiSessionService;
import com.antondev.crates.gui.MenuService;
import com.antondev.crates.gui.EditSessionService;
import com.antondev.crates.gui.AdminMenuService;
import com.antondev.crates.listener.CrateListener;
import com.antondev.crates.database.DatabaseService;
import com.antondev.crates.database.DefinitionRepository;
import com.antondev.crates.database.LegacyMigration;
import com.antondev.crates.service.CrateRegistry;
import com.antondev.crates.service.DefinitionPublisher;
import com.antondev.crates.service.DisplayService;
import com.antondev.crates.service.DraftSessionService;
import com.antondev.crates.service.KeyService;
import com.antondev.crates.service.LocationStore;
import com.antondev.crates.service.OpeningLog;
import com.antondev.crates.service.OpeningService;
import com.antondev.crates.service.StatsStore;
import com.antondev.crates.service.WandService;
import com.antondev.crates.service.RewardStateService;
import com.antondev.crates.service.RuntimeRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

public class PlexonCrates extends JavaPlugin {
    private PluginSettings settings;
    private DatabaseService database;
    private DefinitionRepository definitionRepository;
    private LegacyMigration.Result migration;
    private Messages messages;
    private MenuConfig menusConfig;
    private CrateRegistry crates;
    private RuntimeRegistry runtime;
    private LocationStore locations;
    private KeyService keys;
    private StatsStore statistics;
    private RewardStateService rewardStates;
    private DraftSessionService draftSessions;
    private DefinitionPublisher definitionPublisher;
    private DisplayService displays;
    private GuiSessionService guiSessions;
    private MenuService menus;
    private EditSessionService editSessions;
    private AdminMenuService adminMenus;
    private OpeningLog openingLog;
    private OpeningService openings;
    private WandService wand;
    private final Map<String, Long> definitionRevisions = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        try {
            saveBundledFiles();
            YamlConfiguration bootstrap = YamlConfiguration.loadConfiguration(file("config.yml"));
            String databaseFile = bootstrap.getString("database.file", "data/plexoncrates.db");
            int maximumQueuedWrites = bootstrap.getInt("database.maximum-queued-writes", 4096);
            database = new DatabaseService(getLogger(), safeDataPath(databaseFile), maximumQueuedWrites);
            migration = LegacyMigration.migrate(getDataFolder().toPath(), database);
            settings = PluginSettings.load(file("config.yml"));
            messages = Messages.load(file("messages.yml"));
            menusConfig = MenuConfig.load(file("menus.yml"));
            definitionRepository = new DefinitionRepository(database);
            DatabaseService.PublishedSnapshot canonical = definitionRepository.loadPublished().join();
            definitionRevisions.clear();
            canonical.definitions().forEach(definition ->
                    definitionRevisions.put(definition.crateId().toLowerCase(Locale.ROOT), definition.publishedRevision()));
            List<com.antondev.crates.domain.draft.DefinitionDraft> durableDrafts = definitionRepository.loadDrafts().join();
            if (canonical.definitions().isEmpty()) {
                CrateRegistry.Snapshot crateSnapshot = CrateRegistry.load(getDataFolder().toPath().resolve("crates"));
                crates = new CrateRegistry(getDataFolder().toPath().resolve("crates"), crateSnapshot);
            } else {
                crates = CrateRegistry.fromPublished(getDataFolder().toPath().resolve("crates"), canonical.definitions(), durableDrafts);
            }
            LocationStore.Snapshot locationSnapshot = LocationStore.fromDatabase(database.loadLocations(), crates);
            locations = new LocationStore(database, getLogger(), locationSnapshot);
            KeyService.Snapshot keySnapshot = KeyService.load(file(settings.fallbackFile()));
            keys = new KeyService(this, database, file(settings.fallbackFile()).toPath(), keySnapshot,
                    database.loadKeyTemplateCache());
            runtime = new RuntimeRegistry(DefinitionPublisher.bootstrap(definitionRepository, crates, keys));
            if (canonical.definitions().isEmpty()) {
                runtime.all().forEach(crate -> recordDefinitionRevision(crate.id(), runtime.crateRevision(crate.id())));
            }
            if (canonical.definitions().isEmpty() && !durableDrafts.isEmpty()) {
                crates.apply(CrateRegistry.withDurableDrafts(getDataFolder().toPath().resolve("crates"),
                        crates.snapshot(), durableDrafts));
            }
            statistics = new StatsStore(database.loadStatistics());
            rewardStates = new RewardStateService(database.loadRewardStates());
            openingLog = new OpeningLog(this);
            displays = new DisplayService(this);
            draftSessions = new DraftSessionService(database, this::draftStateChanged);
            definitionPublisher = new DefinitionPublisher(this, definitionRepository, crates, keys, runtime,
                    draftSessions);
            guiSessions = new GuiSessionService();
            editSessions = new EditSessionService(this);
            adminMenus = new AdminMenuService(this);
            menus = new MenuService(this);
            openings = new OpeningService(this, openingLog);
            wand = new WandService(this);
            getServer().getServicesManager().register(PlexonCratesApi.class, new PlexonCratesApiImpl(this), this,
                    ServicePriority.Normal);

            getServer().getPluginManager().registerEvents(menus, this);
            getServer().getPluginManager().registerEvents(editSessions, this);
            getServer().getPluginManager().registerEvents(new CrateListener(this), this);
            getServer().getPluginManager().registerEvents(wand, this);
            registerCommands();
            displays.refresh();
            int unresolvedJournals = database.pendingJournalCount();
            if (unresolvedJournals > 0) {
                getLogger().warning("Found " + unresolvedJournals + " unresolved opening journal entr"
                        + (unresolvedJournals == 1 ? "y" : "ies")
                        + ". Recovery policy is MANUAL_REVIEW; inspect /pcrates diagnose before changing data.");
            }
            getLogger().info("PlexonCrates " + getPluginMeta().getVersion() + " by Tonim (ZpkDxGames) enabled: "
                    + runtime.all().size() + " published crates, " + runtime.rewardCount() + " rewards, "
                    + locations.all().size()
                    + " linked blocks. Key source: " + keys.sourceLabel() + "."
                    + (migration.migrated() ? " Migrated 1.0 data into " + migration.backupDirectory() + "." : ""));
        } catch (Exception | LinkageError error) {
            getLogger().log(Level.SEVERE, "PlexonCrates could not start. Existing files were not reset.", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (editSessions != null) editSessions.stop();
        if (draftSessions != null) {
            try {
                draftSessions.awaitIdle().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception error) {
                getLogger().log(Level.WARNING, "Not every queued draft revision finished before shutdown", error);
            }
            draftSessions.clear();
        }
        if (guiSessions != null) guiSessions.clear();
        getServer().getServicesManager().unregisterAll(this);
        if (displays != null) displays.stop();
        if (openings != null) openings.clear();
        if (openingLog != null) openingLog.close();
        if (database != null) database.close();
    }

    public boolean reloadFor(CommandSender sender) {
        try {
            PluginSettings nextSettings = PluginSettings.load(file("config.yml"));
            Messages nextMessages = Messages.load(file("messages.yml"));
            MenuConfig nextMenus = MenuConfig.load(file("menus.yml"));
            DatabaseService.PublishedSnapshot canonical = definitionRepository.loadPublished().join();
            List<com.antondev.crates.domain.draft.DefinitionDraft> durableDrafts = definitionRepository.loadDrafts().join();
            CrateRegistry validationRegistry;
            if (canonical.definitions().isEmpty()) {
                Path crateDirectory = getDataFolder().toPath().resolve("crates");
                validationRegistry = new CrateRegistry(crateDirectory,
                        CrateRegistry.withDurableDrafts(crateDirectory,
                                CrateRegistry.load(crateDirectory), durableDrafts));
            } else {
                validationRegistry = CrateRegistry.fromPublished(getDataFolder().toPath().resolve("crates"),
                        canonical.definitions(), durableDrafts);
            }
            CrateRegistry.Snapshot nextCrates = validationRegistry.snapshot();
            for (LocationStore.Link link : locations.all()) {
                if (validationRegistry.find(link.crateId()).isEmpty()) {
                    throw new IllegalArgumentException("Linked location references a crate missing from the reload: " + link.crateId());
                }
            }
            KeyService.Snapshot nextKeys = KeyService.load(new File(getDataFolder(), nextSettings.fallbackFile()));
            if (!nextSettings.databaseFile().equals(settings.databaseFile())) {
                throw new IllegalArgumentException("database.file cannot be changed by reload; restart the server after moving data safely");
            }

            PluginSettings previousSettings = settings;
            Messages previousMessages = messages;
            MenuConfig previousMenus = menusConfig;
            CrateRegistry.Snapshot previousCrates = crates.snapshot();
            KeyService.Snapshot previousKeys = keys.snapshot();
            try {
                settings = nextSettings;
                messages = nextMessages;
                menusConfig = nextMenus;
                crates.apply(nextCrates);
                keys.apply(nextKeys);
                for (var crate : crates.ordered()) {
                    List<String> issues = crates.publishingIssues(crate.id(), keys);
                    if (!issues.isEmpty()) throw new IllegalArgumentException("crates/" + crate.id()
                            + ".yml cannot remain published: " + String.join(" ", issues));
                }
                menus.closeAll();
                displays.refresh();
                if (!canonical.definitions().isEmpty()) {
                    definitionRevisions.clear();
                    canonical.definitions().forEach(definition ->
                            definitionRevisions.put(definition.crateId().toLowerCase(Locale.ROOT),
                                    definition.publishedRevision()));
                }
            } catch (Exception error) {
                settings = previousSettings;
                messages = previousMessages;
                menusConfig = previousMenus;
                crates.apply(previousCrates);
                keys.apply(previousKeys);
                try { displays.refresh(); }
                catch (RuntimeException refreshError) { error.addSuppressed(refreshError); }
                throw error;
            }
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

    public boolean validateFor(CommandSender sender) {
        try {
            PluginSettings.load(file("config.yml"));
            Messages.load(file("messages.yml"));
            MenuConfig.load(file("menus.yml"));
            CrateRegistry.Snapshot nextCrates = CrateRegistry.load(getDataFolder().toPath().resolve("crates"));
            KeyService.Snapshot nextKeys = KeyService.load(file(settings.fallbackFile()));
            for (var crate : nextCrates.crates().values()) {
                for (String keyId : crate.acceptedKeyIds()) {
                    if (!nextKeys.definitions().containsKey(keyId)) {
                        throw new IllegalArgumentException("crates/" + crate.id() + ".yml references missing key " + keyId);
                    }
                }
            }
            for (LocationStore.Link link : locations.all()) {
                if (!nextCrates.crates().containsKey(link.crateId())) {
                    throw new IllegalArgumentException("Database location references missing crate " + link.crateId());
                }
            }
            messages.send(sender, "validation-passed", Text.value("crates", nextCrates.crates().size()),
                    Text.value("keys", nextKeys.definitions().size()),
                    Text.value("rewards", nextCrates.crates().values().stream().mapToInt(crate -> crate.rewards().size()).sum()));
            return true;
        } catch (Exception error) {
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            messages.send(sender, "validation-failed", Text.value("error", reason));
            getLogger().log(Level.WARNING, "Validation failed: " + reason, error);
            return false;
        }
    }

    public void backupFor(CommandSender sender) {
        if (!sender.hasPermission("plexoncrates.admin.backup")) {
            messages.send(sender, "no-permission");
            return;
        }
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        java.nio.file.Path destination = getDataFolder().toPath().resolve("backups").resolve("manual-" + timestamp);
        messages.send(sender, "backup-started");
        String senderName = sender.getName();
        database.createBackup(getDataFolder().toPath(), destination).whenComplete((ignored, error) -> {
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                CommandSender current = senderName.equalsIgnoreCase("CONSOLE") ? getServer().getConsoleSender()
                        : getServer().getPlayerExact(senderName);
                if (current == null) return;
                if (error == null) messages.send(current, "backup-created", Text.value("path", destination.getFileName()));
                else {
                    messages.send(current, "backup-failed", Text.value("error",
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                    getLogger().log(Level.SEVERE, "Backup failed", error);
                }
            });
        });
    }

    public void diagnoseFor(CommandSender sender) {
        if (!sender.hasPermission("plexoncrates.admin.diagnose")) {
            messages.send(sender, "no-permission");
            return;
        }
        long onlineLocations = locations.all().stream().filter(link -> link.position().loadedWorld() != null).count();
        long drafts = crates.all().stream().filter(crate -> crate.state() == com.antondev.crates.domain.crate.CrateState.DRAFT).count();
        int pendingJournals;
        try { pendingJournals = database.pendingJournalCount(); }
        catch (Exception error) { pendingJournals = -1; }
        sender.sendMessage(Text.parse("<gradient:#CAD5E5:#FFFFFF><bold>PlexonCrates Diagnostics</bold></gradient>"));
        sender.sendMessage(Text.parse("<gray>Plugin:</gray> <white>" + getPluginMeta().getVersion() + "</white> <dark_gray>•</dark_gray> <gray>Paper API:</gray> <white>26.2</white> <dark_gray>•</dark_gray> <gray>Java:</gray> <white>" + Runtime.version().feature() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Crates:</gray> <white>" + crates.all().size() + "</white> <dark_gray>(" + drafts + " drafts)</dark_gray> <dark_gray>•</dark_gray> <gray>Rewards:</gray> <white>" + crates.rewardCount() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Keys:</gray> <white>" + keys.definitions().size() + "</white> <dark_gray>•</dark_gray> <gray>Provider:</gray> <white>" + keys.providerStatus() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Key detail:</gray> <white>" + keys.providerDiagnostic() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Unresolved:</gray> <white>" + keys.unresolved().size() + "</white> <dark_gray>•</dark_gray> <gray>Collisions:</gray> <white>" + keys.collisions().size() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Locations:</gray> <white>" + locations.all().size() + "</white> <dark_gray>(" + onlineLocations + " online)</dark_gray>"));
        sender.sendMessage(Text.parse("<gray>Database schema:</gray> <white>" + DatabaseService.SCHEMA_VERSION + "</white> <dark_gray>•</dark_gray> <gray>Queue:</gray> <white>" + database.queuedWrites() + "</white> <dark_gray>•</dark_gray> <gray>Pending journals:</gray> <white>" + pendingJournals + "</white>"));
        sender.sendMessage(Text.parse("<gray>Runtime snapshot:</gray> <white>" + runtime.snapshot().revision()
                + "</white> <dark_gray>•</dark_gray> <gray>Published crates:</gray> <white>"
                + runtime.all().size() + "</white>"));
        sender.sendMessage(Text.parse("<gray>Active opening/edit sessions:</gray> <white>"
                + (openings.pendingCount() + draftSessions.activeSessions()) + "</white>"));
    }

    private void draftStateChanged(UUID actorId, String crateId, DraftSessionService.View view) {
        if (!isEnabled()) return;
        getServer().getScheduler().runTask(this, () -> {
            if (!isEnabled()) return;
            Player player = getServer().getPlayer(actorId);
            if (player == null || menus == null) return;
            menus.refreshDraftState(player, crateId);
            if (view.state() == DraftSessionService.State.SAVE_FAILED) {
                messages.send(player, "draft-save-failed", Text.value("error",
                        view.failure().isBlank() ? "unknown database error" : view.failure()));
            }
        });
    }

    private void saveBundledFiles() throws Exception {
        saveDefaultConfig();
        Files.createDirectories(getDataFolder().toPath().resolve("crates"));
        Files.createDirectories(getDataFolder().toPath().resolve("data"));
        Files.createDirectories(getDataFolder().toPath().resolve("backups"));
        Files.createDirectories(getDataFolder().toPath().resolve("imports"));
        Files.createDirectories(getDataFolder().toPath().resolve("exports"));
        Files.createDirectories(getDataFolder().toPath().resolve("logs"));
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

    private File file(String name) {
        return new File(getDataFolder(), name);
    }

    private java.nio.file.Path safeDataPath(String relative) {
        if (relative == null || !relative.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.db") || relative.contains("..")) {
            throw new IllegalArgumentException("database.file must be a safe relative data/*.db path");
        }
        java.nio.file.Path root = getDataFolder().toPath().toAbsolutePath().normalize();
        java.nio.file.Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("database.file leaves the plugin data directory");
        return resolved;
    }

    public PluginSettings settings() { return settings; }
    public DatabaseService database() { return database; }
    public DefinitionRepository definitionRepository() { return definitionRepository; }
    public LegacyMigration.Result migration() { return migration; }
    public Messages messages() { return messages; }
    public MenuConfig menusConfig() { return menusConfig; }
    public CrateRegistry crates() { return crates; }
    public RuntimeRegistry runtime() { return runtime; }
    public LocationStore locations() { return locations; }
    public KeyService keys() { return keys; }
    public StatsStore statistics() { return statistics; }
    public RewardStateService rewardStates() { return rewardStates; }
    public DraftSessionService draftSessions() { return draftSessions; }
    public DefinitionPublisher definitionPublisher() { return definitionPublisher; }
    public DisplayService displays() { return displays; }
    public GuiSessionService guiSessions() { return guiSessions; }
    public MenuService menus() { return menus; }
    public EditSessionService editSessions() { return editSessions; }
    public AdminMenuService adminMenus() { return adminMenus; }
    public OpeningService openings() { return openings; }
    public WandService wand() { return wand; }

    /** Returns the durable definition revision, including inactive archived/disabled crates. */
    public long definitionRevision(String crateId) {
        String id = crateId == null ? "" : crateId.trim().toLowerCase(Locale.ROOT);
        Long revision = definitionRevisions.get(id);
        if (revision != null) return revision;
        return runtime == null ? 0L : runtime.crateRevision(id);
    }

    public void recordDefinitionRevision(String crateId, long revision) {
        if (crateId == null || revision < 0) return;
        definitionRevisions.put(crateId.trim().toLowerCase(Locale.ROOT), revision);
    }

    public void forgetDefinitionRevision(String crateId) {
        if (crateId != null) definitionRevisions.remove(crateId.trim().toLowerCase(Locale.ROOT));
    }
}

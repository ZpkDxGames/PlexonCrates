from pathlib import Path

MAIN = Path('src/main/java/com/antondev/crates/PlexonCrates.java')
ADMIN = Path('src/main/java/com/antondev/crates/gui/AdminMenuService.java')
MENU = Path('src/main/java/com/antondev/crates/gui/MenuService.java')

text = MAIN.read_text()
start = text.index('    /** Synchronous compatibility path used by startup/integration callers. Live admin surfaces use requestReload(). */')
end = text.index('    private static Exception completionException(', start)
replacement = '''    /** Synchronous compatibility path used by startup/integration callers. Live admin surfaces use requestReload(). */
    public boolean reloadFor(CommandSender sender) {
        try {
            DatabaseService.PublishedSnapshot canonical = definitionRepository.loadPublished().join();
            List<com.antondev.crates.domain.draft.DefinitionDraft> durableDrafts = definitionRepository.loadDrafts().join();
            List<DatabaseService.StoredKeyDefinition> keyRows = definitionRepository.loadKeys().join();
            ReloadPreparation prepared = prepareReload(canonical, durableDrafts, keyRows,
                    List.copyOf(locations.all()));
            return applyReload(sender, prepared);
        } catch (Exception error) {
            configError(sender, error);
            return false;
        }
    }

    /** Starts a live reload and returns to the caller immediately. */
    public void requestReload(CommandSender sender) {
        requestReload(sender, null);
    }

    /**
     * Live reload entry point. Every database/file read and YAML parse is completed away from
     * the server thread. Only the validated immutable state swap and Bukkit-facing reconciliation
     * return to the primary thread. The optional callback runs only after a successful reload.
     */
    public void requestReload(CommandSender sender, Runnable onSuccess) {
        if (!isEnabled()) return;
        List<LocationStore.Link> locationSnapshot = List.copyOf(locations.all());
        sender.sendMessage(Text.parse("<gray>Reloading PlexonCrates configuration…</gray>"));
        var publishedFuture = definitionRepository.loadPublished();
        var draftsFuture = definitionRepository.loadDrafts();
        var keyRowsFuture = definitionRepository.loadKeys();
        CompletableFuture.allOf(publishedFuture, draftsFuture, keyRowsFuture).whenComplete((ignored, databaseFailure) -> {
            if (!isEnabled()) return;
            if (databaseFailure != null) {
                getServer().getScheduler().runTask(this, () -> {
                    if (isEnabled()) configError(sender, completionException(databaseFailure));
                });
                return;
            }
            DatabaseService.PublishedSnapshot canonical = publishedFuture.getNow(null);
            List<com.antondev.crates.domain.draft.DefinitionDraft> durableDrafts = draftsFuture.getNow(List.of());
            List<DatabaseService.StoredKeyDefinition> keyRows = keyRowsFuture.getNow(List.of());
            io.submit(() -> prepareReload(canonical, durableDrafts, keyRows, locationSnapshot))
                    .whenComplete((prepared, preparationFailure) -> {
                        if (!isEnabled()) return;
                        getServer().getScheduler().runTask(this, () -> {
                            if (!isEnabled()) return;
                            if (preparationFailure != null) {
                                configError(sender, completionException(preparationFailure));
                                return;
                            }
                            try {
                                if (applyReload(sender, prepared) && onSuccess != null && isEnabled()) onSuccess.run();
                            } catch (Exception error) {
                                configError(sender, error);
                            }
                        });
                    });
        });
    }

    private ReloadPreparation prepareReload(
            DatabaseService.PublishedSnapshot canonical,
            List<com.antondev.crates.domain.draft.DefinitionDraft> durableDrafts,
            List<DatabaseService.StoredKeyDefinition> keyRows,
            List<LocationStore.Link> locationSnapshot) throws Exception {
        if (canonical == null) throw new IllegalStateException("Published definition snapshot was unavailable");
        PluginSettings nextSettings = PluginSettings.load(file("config.yml"));
        Messages nextMessages = Messages.load(file("messages.yml"));
        MenuConfig nextMenus = MenuConfig.load(file("menus.yml"));
        Path crateDirectory = getDataFolder().toPath().resolve("crates");
        CrateRegistry validationRegistry;
        if (canonical.definitions().isEmpty()) {
            validationRegistry = new CrateRegistry(crateDirectory,
                    CrateRegistry.withDurableDrafts(crateDirectory,
                            CrateRegistry.load(crateDirectory), durableDrafts));
        } else {
            validationRegistry = CrateRegistry.fromPublished(crateDirectory, canonical.definitions(), durableDrafts);
        }
        CrateRegistry.Snapshot nextCrates = validationRegistry.snapshot();
        for (LocationStore.Link link : locationSnapshot) {
            if (validationRegistry.find(link.crateId()).isEmpty()) {
                throw new IllegalArgumentException(
                        "Linked location references a crate missing from the reload: " + link.crateId());
            }
        }
        KeyService.CanonicalSnapshot canonicalKeys = KeyService.fromDatabase(keyRows, getLogger());
        KeyService.Snapshot nextKeys = loadKeySnapshot(nextSettings.fallbackFile(), canonicalKeys);
        Map<String, ItemStack> nextKeyCache = mergeKeyCaches(canonicalKeys);
        return new ReloadPreparation(nextSettings, nextMessages, nextMenus, canonical,
                validationRegistry, nextCrates, nextKeys, nextKeyCache);
    }

    private boolean applyReload(CommandSender sender, ReloadPreparation prepared) throws Exception {
        PluginSettings nextSettings = prepared.settings();
        Messages nextMessages = prepared.messages();
        MenuConfig nextMenus = prepared.menus();
        DatabaseService.PublishedSnapshot canonical = prepared.canonical();
        CrateRegistry validationRegistry = prepared.validationRegistry();
        CrateRegistry.Snapshot nextCrates = prepared.crates();
        KeyService.Snapshot nextKeys = prepared.keys();
        Map<String, ItemStack> nextKeyCache = prepared.keyCache();
        if (!nextSettings.databaseFile().equals(settings.databaseFile())) {
            throw new IllegalArgumentException(
                    "database.file cannot be changed by reload; restart the server after moving data safely");
        }

        PluginSettings previousSettings = settings;
        Messages previousMessages = messages;
        MenuConfig previousMenus = menusConfig;
        CrateRegistry.Snapshot previousCrates = crates.snapshot();
        KeyService.Snapshot previousKeys = keys.snapshot();
        Map<String, ItemStack> previousKeyCache = keys.lastKnownGoodSnapshot();
        try {
            settings = nextSettings;
            messages = nextMessages;
            menusConfig = nextMenus;
            crates.apply(nextCrates);
            keys.apply(nextKeys, nextKeyCache);
            for (var crate : validationRegistry.ordered()) {
                List<String> issues = validationRegistry.publishingIssues(crate.id(), keys);
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
            keys.apply(previousKeys, previousKeyCache);
            try { displays.refresh(); }
            catch (RuntimeException refreshError) { error.addSuppressed(refreshError); }
            throw error;
        }
        if (coreBridge != null) {
            coreBridge.registerStarting();
            updateCoreHealthAsync();
        }
        messages.send(sender, "reloaded");
        return true;
    }

    private record ReloadPreparation(
            PluginSettings settings,
            Messages messages,
            MenuConfig menus,
            DatabaseService.PublishedSnapshot canonical,
            CrateRegistry validationRegistry,
            CrateRegistry.Snapshot crates,
            KeyService.Snapshot keys,
            Map<String, ItemStack> keyCache) {
        private ReloadPreparation {
            keyCache = Map.copyOf(keyCache);
        }
    }

'''
text = text[:start] + replacement + text[end:]
MAIN.write_text(text)

admin = ADMIN.read_text()
old = '            case "reload" -> { plugin.reloadFor(player); if (plugin.isEnabled()) openSystem(player); }\n'
new = '            case "reload" -> plugin.requestReload(player, () -> { if (player.isOnline()) openSystem(player); });\n'
if old not in admin:
    raise SystemExit('AdminMenuService reload action changed unexpectedly')
ADMIN.write_text(admin.replace(old, new, 1))

menu = MENU.read_text()
old = '''        if (slot == menus.slot("admin.reload")) {
            plugin.reloadFor(player);
            if (plugin.isEnabled()) openAdmin(player);
            return;
        }
'''
new = '''        if (slot == menus.slot("admin.reload")) {
            plugin.requestReload(player, () -> { if (player.isOnline()) openAdmin(player); });
            return;
        }
'''
if old not in menu:
    raise SystemExit('MenuService legacy admin reload block changed unexpectedly')
MENU.write_text(menu.replace(old, new, 1))

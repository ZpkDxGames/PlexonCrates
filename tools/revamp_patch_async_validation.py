from pathlib import Path

MAIN = Path('src/main/java/com/antondev/crates/PlexonCrates.java')
ADMIN = Path('src/main/java/com/antondev/crates/gui/AdminMenuService.java')
COMMAND = Path('src/main/java/com/antondev/crates/command/CratesAdminCommand.java')

text = MAIN.read_text()
start = text.index('    public boolean validateFor(CommandSender sender) {')
end = text.index('    public void backupFor(CommandSender sender) {', start)
replacement = '''    /** Synchronous compatibility path retained for integration callers. Live admin surfaces use requestValidation(). */
    public boolean validateFor(CommandSender sender) {
        try {
            ValidationSnapshot result = validateConfiguration(List.copyOf(locations.all()));
            renderValidationSuccess(sender, result);
            return true;
        } catch (Exception error) {
            renderValidationFailure(sender, error);
            return false;
        }
    }

    /** Runs configuration/crate/key validation on the bounded plugin I/O pool. */
    public void requestValidation(CommandSender sender) {
        if (!isEnabled()) return;
        List<LocationStore.Link> locationSnapshot = List.copyOf(locations.all());
        sender.sendMessage(Text.parse("<gray>Validating PlexonCrates configuration…</gray>"));
        io.submit(() -> validateConfiguration(locationSnapshot)).whenComplete((result, failure) -> {
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) return;
                if (failure == null) renderValidationSuccess(sender, result);
                else renderValidationFailure(sender, completionException(failure));
            });
        });
    }

    private ValidationSnapshot validateConfiguration(List<LocationStore.Link> locationSnapshot) throws Exception {
        PluginSettings candidateSettings = PluginSettings.load(file("config.yml"));
        Messages.load(file("messages.yml"));
        MenuConfig.load(file("menus.yml"));
        CrateRegistry.Snapshot candidateCrates = CrateRegistry.load(getDataFolder().toPath().resolve("crates"));
        KeyService.Snapshot candidateKeys = KeyService.load(file(candidateSettings.fallbackFile()));
        for (var crate : candidateCrates.crates().values()) {
            for (String keyId : crate.acceptedKeyIds()) {
                if (!candidateKeys.definitions().containsKey(keyId)) {
                    throw new IllegalArgumentException("crates/" + crate.id() + ".yml references missing key " + keyId);
                }
            }
        }
        for (LocationStore.Link link : locationSnapshot) {
            if (!candidateCrates.crates().containsKey(link.crateId())) {
                throw new IllegalArgumentException("Database location references missing crate " + link.crateId());
            }
        }
        int rewards = candidateCrates.crates().values().stream().mapToInt(crate -> crate.rewards().size()).sum();
        return new ValidationSnapshot(candidateCrates.crates().size(), candidateKeys.definitions().size(), rewards);
    }

    private void renderValidationSuccess(CommandSender sender, ValidationSnapshot result) {
        messages.send(sender, "validation-passed", Text.value("crates", result.crates()),
                Text.value("keys", result.keys()), Text.value("rewards", result.rewards()));
    }

    private void renderValidationFailure(CommandSender sender, Exception error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        messages.send(sender, "validation-failed", Text.value("error", reason));
        getLogger().log(Level.WARNING, "Validation failed: " + reason, error);
    }

    private record ValidationSnapshot(int crates, int keys, int rewards) {}

'''
text = text[:start] + replacement + text[end:]
MAIN.write_text(text)

admin = ADMIN.read_text()
old = '            case "validate" -> plugin.validateFor(player);\n'
new = '            case "validate" -> plugin.requestValidation(player);\n'
if old not in admin:
    raise SystemExit('AdminMenuService validation action changed unexpectedly')
ADMIN.write_text(admin.replace(old, new, 1))

command = COMMAND.read_text()
old = '                case "validate" -> plugin.validateFor(sender);\n'
new = '                case "validate" -> plugin.requestValidation(sender);\n'
if old not in command:
    raise SystemExit('CratesAdminCommand validation action changed unexpectedly')
COMMAND.write_text(command.replace(old, new, 1))

package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LiveValidationIoBoundaryArchitectureTest {
    private static final Path MAIN = Path.of("src/main/java/com/antondev/crates/PlexonCrates.java");
    private static final Path ADMIN_GUI = Path.of("src/main/java/com/antondev/crates/gui/AdminMenuService.java");
    private static final Path ADMIN_COMMAND = Path.of("src/main/java/com/antondev/crates/command/CratesAdminCommand.java");

    @Test
    void liveValidationRunsFilesystemParsingOnBoundedIoAndRendersOnPrimary() throws Exception {
        String source = Files.readString(MAIN);
        String request = section(source, "public void requestValidation(CommandSender sender)",
                "private ValidationSnapshot validateConfiguration(");
        assertTrue(request.contains("io.submit(() -> validateConfiguration(locationSnapshot))"));
        assertTrue(request.contains("getServer().getScheduler().runTask(this"));
        assertFalse(request.contains("PluginSettings.load("));
        assertFalse(request.contains("Messages.load("));
        assertFalse(request.contains("MenuConfig.load("));
        assertFalse(request.contains("CrateRegistry.load("));
        assertFalse(request.contains("KeyService.load("));
    }

    @Test
    void validationWorkerOwnsAllConfigCrateAndKeyFileReads() throws Exception {
        String source = Files.readString(MAIN);
        String worker = section(source, "private ValidationSnapshot validateConfiguration(",
                "private void renderValidationSuccess(");
        assertTrue(worker.contains("PluginSettings.load(file(\"config.yml\"))"));
        assertTrue(worker.contains("Messages.load(file(\"messages.yml\"))"));
        assertTrue(worker.contains("MenuConfig.load(file(\"menus.yml\"))"));
        assertTrue(worker.contains("CrateRegistry.load(getDataFolder().toPath().resolve(\"crates\"))"));
        assertTrue(worker.contains("KeyService.load(file(candidateSettings.fallbackFile()))"));
        assertFalse(worker.contains("getServer().getScheduler().runTask"));
    }

    @Test
    void liveAdminValidationSurfacesNeverUseSynchronousCompatibilityMethod() throws Exception {
        String adminGui = Files.readString(ADMIN_GUI);
        String command = Files.readString(ADMIN_COMMAND);
        assertTrue(adminGui.contains("case \"validate\" -> plugin.requestValidation(player)"));
        assertTrue(command.contains("case \"validate\" -> plugin.requestValidation(sender)"));
        assertFalse(adminGui.contains("plugin.validateFor(player)"));
        assertFalse(command.contains("plugin.validateFor(sender)"));
    }

    @Test
    void linkedLocationStateIsSnapshottedBeforeWorkerValidation() throws Exception {
        String source = Files.readString(MAIN);
        String request = section(source, "public void requestValidation(CommandSender sender)",
                "private ValidationSnapshot validateConfiguration(");
        assertTrue(request.contains("List<LocationStore.Link> locationSnapshot = List.copyOf(locations.all())"));
        String worker = section(source, "private ValidationSnapshot validateConfiguration(",
                "private void renderValidationSuccess(");
        assertTrue(worker.contains("for (LocationStore.Link link : locationSnapshot)"));
        assertFalse(worker.contains("locations.all()"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) throw new AssertionError("Could not isolate source section " + start);
        return source.substring(from, to);
    }
}

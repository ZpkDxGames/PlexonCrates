package com.antondev.crates.service;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.entity.Player;

public final class OpeningLog implements AutoCloseable {
    private final PlexonCrates plugin;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PlexonCrates-LogWriter");
        thread.setDaemon(true);
        return thread;
    });

    public OpeningLog(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, Crate crate, CrateReward reward) {
        String plain = player.getName() + " (" + player.getUniqueId() + ") opened " + crate.id() + " and received " + reward.id();
        if (plugin.settings().consoleLogging()) plugin.getLogger().info(plain);
        if (!plugin.settings().fileLogging()) return;
        String format = plugin.settings().logDateFormat();
        writer.submit(() -> {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
                Path directory = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(directory);
                Path file = directory.resolve("openings-" + LocalDate.now() + ".log");
                String line = "[" + LocalDateTime.now().format(formatter) + "] " + plain + System.lineSeparator();
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | IllegalArgumentException error) {
                plugin.getLogger().log(Level.WARNING, "Could not write the crate opening log", error);
            }
        });
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException error) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

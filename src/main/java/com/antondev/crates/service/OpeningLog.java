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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.entity.Player;

public final class OpeningLog implements AutoCloseable {
    private static final int MAXIMUM_QUEUED_WRITES = 1024;
    private static final long REJECTION_WARNING_INTERVAL_MILLIS = 60_000L;

    private final PlexonCrates plugin;
    private final AtomicLong nextRejectionWarning = new AtomicLong();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAXIMUM_QUEUED_WRITES),
            runnable -> {
                Thread thread = new Thread(runnable, "PlexonCrates-LogWriter");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    public OpeningLog(PlexonCrates plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, Crate crate, CrateReward reward) {
        String plain = player.getName() + " (" + player.getUniqueId() + ") opened " + crate.id()
                + " and received " + reward.id();
        if (plugin.settings().consoleLogging()) plugin.getLogger().info(plain);
        if (!plugin.settings().fileLogging()) return;
        String format = plugin.settings().logDateFormat();
        try {
            writer.execute(() -> write(format, plain));
        } catch (RejectedExecutionException rejected) {
            warnRejectedWrite();
        }
    }

    private void write(String format, String plain) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            Path directory = plugin.getDataFolder().toPath().resolve("logs");
            Files.createDirectories(directory);
            Path file = directory.resolve("openings-" + LocalDate.now() + ".log");
            String line = "[" + LocalDateTime.now().format(formatter) + "] " + plain + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | IllegalArgumentException error) {
            plugin.getLogger().log(Level.WARNING, "Could not write the crate opening log", error);
        }
    }

    private void warnRejectedWrite() {
        long now = System.currentTimeMillis();
        long next = nextRejectionWarning.get();
        if (now < next || !nextRejectionWarning.compareAndSet(next,
                now + REJECTION_WARNING_INTERVAL_MILLIS)) return;
        plugin.getLogger().warning("Opening log queue is full; dropping file-log entries until the writer catches up.");
    }

    int queuedWrites() {
        return writer.getQueue().size();
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

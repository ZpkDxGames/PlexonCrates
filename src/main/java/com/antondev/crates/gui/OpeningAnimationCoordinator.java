package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.model.CrateReward;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns one shared roulette task for every currently visible opening animation.
 * The coordinator is main-thread confined and tears its task down as soon as
 * the active set becomes empty.
 */
public final class OpeningAnimationCoordinator {
    private final PlexonCrates plugin;
    private final Map<UUID, Animation> active = new LinkedHashMap<>();
    private BukkitTask task;
    private long taskPeriod;

    public OpeningAnimationCoordinator(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void start(Player player, MenuHolder holder, Inventory inventory, List<Integer> rail,
                      List<CrateReward> visuals, CrateReward selected, int centerSlot, Runnable completed) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(visuals, "visuals");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(completed, "completed");
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin,
                    () -> start(player, holder, inventory, rail, visuals, selected, centerSlot, completed));
            return;
        }
        long period = Math.max(1L, plugin.settings().animationPeriod());
        int steps = Math.max(1, plugin.settings().animationDuration() / (int) period);
        active.entrySet().removeIf(entry -> entry.getValue().player().getUniqueId().equals(player.getUniqueId()));
        active.put(holder.sessionId(), new Animation(player, holder, inventory, List.copyOf(rail),
                List.copyOf(visuals), selected, centerSlot, completed, steps));
        if (task == null || task.isCancelled()) {
            taskPeriod = period;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, taskPeriod, taskPeriod);
        }
    }

    public int activeCount() {
        return active.size();
    }

    public boolean running() {
        return task != null && !task.isCancelled();
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        taskPeriod = 0L;
        active.clear();
    }

    private void tick() {
        if (!plugin.isEnabled()) {
            stop();
            return;
        }
        Iterator<Map.Entry<UUID, Animation>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Animation animation = iterator.next().getValue();
            int step = ++animation.step;
            Player player = animation.player();
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() == animation.holder()) {
                List<Integer> rail = animation.rail();
                for (int index = 0; index < rail.size() - 1; index++) {
                    animation.inventory().setItem(rail.get(index),
                            animation.inventory().getItem(rail.get(index + 1)));
                }
                if (!rail.isEmpty()) {
                    animation.inventory().setItem(rail.getLast(), randomDisplay(animation.visuals()));
                }
                if (step % 3 == 0) {
                    float pitch = Math.min(2.0f, 0.65f + step / (float) animation.steps());
                    player.playSound(player.getLocation(), plugin.settings().openingSound(), 0.35f, pitch);
                }
            }
            if (step < animation.steps()) continue;
            iterator.remove();
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() == animation.holder()) {
                animation.inventory().setItem(animation.centerSlot(), animation.selected().displayCopy());
            }
            try {
                animation.completed().run();
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Opening animation completion callback failed", error);
            }
        }
        if (active.isEmpty() && task != null) {
            task.cancel();
            task = null;
            taskPeriod = 0L;
        }
    }

    private static ItemStack randomDisplay(List<CrateReward> rewards) {
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).displayCopy();
    }

    private static final class Animation {
        private final Player player;
        private final MenuHolder holder;
        private final Inventory inventory;
        private final List<Integer> rail;
        private final List<CrateReward> visuals;
        private final CrateReward selected;
        private final int centerSlot;
        private final Runnable completed;
        private final int steps;
        private int step;

        private Animation(Player player, MenuHolder holder, Inventory inventory, List<Integer> rail,
                          List<CrateReward> visuals, CrateReward selected, int centerSlot,
                          Runnable completed, int steps) {
            this.player = player;
            this.holder = holder;
            this.inventory = inventory;
            this.rail = rail;
            this.visuals = visuals;
            this.selected = selected;
            this.centerSlot = centerSlot;
            this.completed = completed;
            this.steps = steps;
        }

        private Player player() { return player; }
        private MenuHolder holder() { return holder; }
        private Inventory inventory() { return inventory; }
        private List<Integer> rail() { return rail; }
        private List<CrateReward> visuals() { return visuals; }
        private CrateReward selected() { return selected; }
        private int centerSlot() { return centerSlot; }
        private Runnable completed() { return completed; }
        private int steps() { return steps; }
    }
}

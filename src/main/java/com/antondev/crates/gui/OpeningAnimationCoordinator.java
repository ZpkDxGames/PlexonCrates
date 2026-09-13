package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.animation.OpeningAnimationProfile;
import com.antondev.crates.animation.OpeningAnimationStage;
import com.antondev.crates.animation.OpeningAnimationStyle;
import com.antondev.crates.model.CrateReward;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns one shared task for every currently visible opening animation.
 * The coordinator is main-thread confined and tears its task down as soon as
 * the active set becomes empty. Profile-driven effects remain presentation only.
 */
public final class OpeningAnimationCoordinator {
    private static final int GLOBAL_PARTICLE_BUDGET_PER_TICK = 512;

    private final PlexonCrates plugin;
    private final Map<UUID, Animation> active = new LinkedHashMap<>();
    private BukkitTask task;
    private long taskPeriod;

    public OpeningAnimationCoordinator(PlexonCrates plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Accepted legacy roulette entry point. */
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
        int steps = Math.max(1, plugin.settings().animationDuration());
        replace(player, new Animation(player, holder, inventory, List.copyOf(rail),
                List.copyOf(visuals), selected, centerSlot, completed, steps, period, null));
        ensureTask();
    }

    /**
     * 6.0 profile-driven entry point. Timings are measured in server ticks and
     * all styles share this coordinator's single scheduler.
     */
    public void start(Player player, MenuHolder holder, Inventory inventory, List<Integer> rail,
                      List<CrateReward> visuals, CrateReward selected, int centerSlot,
                      OpeningAnimationProfile profile, Runnable completed) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(rail, "rail");
        Objects.requireNonNull(visuals, "visuals");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(completed, "completed");
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin,
                    () -> start(player, holder, inventory, rail, visuals, selected, centerSlot, profile, completed));
            return;
        }
        if (!profile.animated()) {
            inventory.setItem(centerSlot, selected.displayCopy());
            try {
                completed.run();
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Opening animation completion callback failed", error);
            }
            return;
        }
        replace(player, new Animation(player, holder, inventory, List.copyOf(rail),
                List.copyOf(visuals), selected, centerSlot, completed, profile.totalTicks(), 1L, profile));
        ensureTask();
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

    private void replace(Player player, Animation animation) {
        active.entrySet().removeIf(entry -> entry.getValue().player().getUniqueId().equals(player.getUniqueId()));
        active.put(animation.holder().sessionId(), animation);
    }

    private void ensureTask() {
        if (task != null && !task.isCancelled()) return;
        // One tick allows profiles to express exact stage timing. Legacy roulette
        // retains its configured visual cadence inside Animation.cadence().
        taskPeriod = 1L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, taskPeriod, taskPeriod);
    }

    private void tick() {
        if (!plugin.isEnabled()) {
            stop();
            return;
        }
        int remainingParticleBudget = GLOBAL_PARTICLE_BUDGET_PER_TICK;
        Iterator<Map.Entry<UUID, Animation>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Animation animation = iterator.next().getValue();
            Player player = animation.player();
            boolean visible = player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() == animation.holder();
            if (!visible) {
                iterator.remove();
                complete(animation);
                continue;
            }

            int step = ++animation.step;
            if (animation.profile() != null) {
                remainingParticleBudget -= profileTick(animation, step, Math.max(0, remainingParticleBudget));
                if (step < animation.steps()) continue;
                iterator.remove();
                reveal(animation);
                complete(animation);
                continue;
            }

            if (step % animation.cadence() != 0 && step < animation.steps()) continue;
            List<Integer> rail = animation.rail();
            shiftForward(animation, rail);
            int visualStep = Math.max(1, (int) Math.ceil(step / (double) animation.cadence()));
            int visualSteps = Math.max(1, (int) Math.ceil(animation.steps() / (double) animation.cadence()));
            if (visualStep % 3 == 0) {
                float pitch = Math.min(2.0f, 0.65f + visualStep / (float) visualSteps);
                player.playSound(player.getLocation(), plugin.settings().openingSound(), 0.35f, pitch);
            }
            if (step < animation.steps()) continue;

            iterator.remove();
            reveal(animation);
            complete(animation);
        }
        if (active.isEmpty() && task != null) {
            task.cancel();
            task = null;
            taskPeriod = 0L;
        }
    }

    private int profileTick(Animation animation, int step, int globalParticleBudget) {
        OpeningAnimationProfile profile = animation.profile();
        OpeningAnimationStage stage = stageAt(profile, step - 1);
        if (stage != animation.lastStage) {
            animation.lastStage = stage;
            playProfileSound(animation, stage);
        }

        int revealTick = profile.ticks(OpeningAnimationStage.START)
                + profile.ticks(OpeningAnimationStage.CHARGE)
                + profile.ticks(OpeningAnimationStage.SELECTION) + 1;
        if (!animation.revealed && step >= revealTick) reveal(animation);

        if (stage == OpeningAnimationStage.SELECTION) {
            switch (profile.style()) {
                case ROULETTE -> shiftForward(animation, animation.rail());
                case SPIN -> {
                    if ((step & 1) == 0) shiftForward(animation, animation.rail());
                    else shiftBackward(animation, animation.rail());
                }
                case CASCADE -> cascade(animation, step);
                case CHARGE_REVEAL, SPIRAL_BURST, ORB_REVEAL, FIREWORK_STYLE, INSTANT -> { }
            }
            if (step % 4 == 0) playProfileSound(animation, stage);
        }
        return spawnProfileParticles(animation, stage, step, globalParticleBudget);
    }

    private int spawnProfileParticles(Animation animation, OpeningAnimationStage stage, int step,
                                      int globalParticleBudget) {
        OpeningAnimationProfile profile = animation.profile();
        if (profile.particleBudgetPerTick() <= 0 || globalParticleBudget <= 0) return 0;
        int desired = desiredParticles(profile.style(), stage);
        int budget = Math.min(globalParticleBudget, Math.min(profile.particleBudgetPerTick(), desired));
        if (budget <= 0) return 0;

        Player owner = animation.player();
        double rangeSquared = profile.receiverRange() * profile.receiverRange();
        List<Player> receivers = new ArrayList<>();
        for (Player candidate : owner.getWorld().getPlayers()) {
            if (!candidate.isOnline()) continue;
            if (candidate.getLocation().distanceSquared(owner.getLocation()) <= rangeSquared) receivers.add(candidate);
        }
        if (receivers.isEmpty()) return 0;
        int perReceiver = Math.max(1, budget / receivers.size());
        int used = 0;
        for (Player receiver : receivers) {
            int allowance = Math.min(perReceiver, budget - used);
            for (int index = 0; index < allowance; index++) {
                Location point = particlePoint(owner.getLocation(), profile.style(), stage, step, index, allowance);
                try {
                    receiver.spawnParticle(profile.particle(), point, 1, 0.0, 0.0, 0.0, 0.0);
                    used++;
                } catch (RuntimeException ignored) {
                    // Data-bearing or feature-gated particle types fail closed as presentation only.
                    return used;
                }
            }
            if (used >= budget) break;
        }
        return used;
    }

    private static Location particlePoint(Location origin, OpeningAnimationStyle style,
                                          OpeningAnimationStage stage, int step, int index, int count) {
        Location center = origin.clone().add(0.0, 1.15, 0.0);
        double angle = (step * 0.42) + (Math.PI * 2.0 * index / Math.max(1, count));
        double progress = stage == OpeningAnimationStage.CHARGE ? Math.min(1.0, step / 30.0) : 1.0;
        return switch (style) {
            case SPIRAL_BURST -> center.add(Math.cos(angle) * (0.25 + progress * 0.8),
                    ((step + index) % 20) / 20.0 * 1.4 - 0.35,
                    Math.sin(angle) * (0.25 + progress * 0.8));
            case ORB_REVEAL -> {
                double vertical = Math.sin(angle * 0.7) * 0.8;
                double radius = Math.max(0.2, Math.cos(angle * 0.7)) * 0.85;
                yield center.add(Math.cos(angle) * radius, vertical, Math.sin(angle) * radius);
            }
            case CASCADE -> center.add(Math.cos(angle) * 0.55,
                    1.1 - ((step + index) % 18) / 9.0,
                    Math.sin(angle) * 0.55);
            case FIREWORK_STYLE -> center.add(
                    ThreadLocalRandom.current().nextDouble(-1.0, 1.0),
                    ThreadLocalRandom.current().nextDouble(0.0, 1.8),
                    ThreadLocalRandom.current().nextDouble(-1.0, 1.0));
            case CHARGE_REVEAL -> center.add(Math.cos(angle) * 0.45,
                    ((step + index) % 10) / 20.0,
                    Math.sin(angle) * 0.45);
            case ROULETTE, SPIN -> center.add(Math.cos(angle) * 0.65, 0.0, Math.sin(angle) * 0.65);
            case INSTANT -> center;
        };
    }

    private static int desiredParticles(OpeningAnimationStyle style, OpeningAnimationStage stage) {
        if (stage == OpeningAnimationStage.FINISH || style == OpeningAnimationStyle.INSTANT) return 0;
        int base = switch (style) {
            case ROULETTE -> 4;
            case SPIN -> 6;
            case CHARGE_REVEAL -> 8;
            case SPIRAL_BURST -> 12;
            case ORB_REVEAL -> 10;
            case CASCADE -> 8;
            case FIREWORK_STYLE -> 16;
            case INSTANT -> 0;
        };
        if (stage == OpeningAnimationStage.REVEAL || stage == OpeningAnimationStage.CELEBRATION) {
            return Math.min(32, base * 2);
        }
        return base;
    }

    private void playProfileSound(Animation animation, OpeningAnimationStage stage) {
        OpeningAnimationProfile profile = animation.profile();
        if (profile.soundVolume() <= 0.0f) return;
        float pitch = profile.soundPitch();
        if (stage == OpeningAnimationStage.SELECTION) pitch = Math.min(2.0f, pitch + 0.15f);
        else if (stage == OpeningAnimationStage.REVEAL) pitch = Math.min(2.0f, pitch + 0.3f);
        try {
            animation.player().playSound(animation.player().getLocation(), profile.sound(), profile.soundVolume(), pitch);
        } catch (RuntimeException ignored) {
            // Feature-gated sounds are cosmetic and must never fail an opening.
        }
    }

    private static OpeningAnimationStage stageAt(OpeningAnimationProfile profile, int elapsed) {
        int cursor = 0;
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
            cursor += profile.ticks(stage);
            if (elapsed < cursor) return stage;
        }
        return OpeningAnimationStage.FINISH;
    }

    private static void shiftForward(Animation animation, List<Integer> rail) {
        for (int index = 0; index < rail.size() - 1; index++) {
            animation.inventory().setItem(rail.get(index), animation.inventory().getItem(rail.get(index + 1)));
        }
        if (!rail.isEmpty()) animation.inventory().setItem(rail.getLast(), randomDisplay(animation.visuals()));
    }

    private static void shiftBackward(Animation animation, List<Integer> rail) {
        for (int index = rail.size() - 1; index > 0; index--) {
            animation.inventory().setItem(rail.get(index), animation.inventory().getItem(rail.get(index - 1)));
        }
        if (!rail.isEmpty()) animation.inventory().setItem(rail.getFirst(), randomDisplay(animation.visuals()));
    }

    private static void cascade(Animation animation, int step) {
        List<Integer> rail = animation.rail();
        if (rail.isEmpty()) return;
        int index = Math.floorMod(step, rail.size());
        animation.inventory().setItem(rail.get(index), randomDisplay(animation.visuals()));
    }

    private static void reveal(Animation animation) {
        if (animation.revealed) return;
        animation.revealed = true;
        animation.inventory().setItem(animation.centerSlot(), animation.selected().displayCopy());
    }

    private void complete(Animation animation) {
        try {
            animation.completed().run();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Opening animation completion callback failed", error);
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
        private final long cadence;
        private final OpeningAnimationProfile profile;
        private int step;
        private boolean revealed;
        private OpeningAnimationStage lastStage;

        private Animation(Player player, MenuHolder holder, Inventory inventory, List<Integer> rail,
                          List<CrateReward> visuals, CrateReward selected, int centerSlot,
                          Runnable completed, int steps, long cadence, OpeningAnimationProfile profile) {
            this.player = player;
            this.holder = holder;
            this.inventory = inventory;
            this.rail = rail;
            this.visuals = visuals;
            this.selected = selected;
            this.centerSlot = centerSlot;
            this.completed = completed;
            this.steps = steps;
            this.cadence = Math.max(1L, cadence);
            this.profile = profile;
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
        private long cadence() { return cadence; }
        private OpeningAnimationProfile profile() { return profile; }
    }
}

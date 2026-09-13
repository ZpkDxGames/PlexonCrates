package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.animation.OpeningAnimationProfile;
import com.antondev.crates.animation.OpeningAnimationStyle;
import com.antondev.crates.config.MenuConfig;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Shared presentation-only renderer for 6.0 opening profiles.
 *
 * <p>This service never selects a reward, consumes a key, writes a journal,
 * updates pity/limits/statistics, or grants anything. Callers must supply the
 * already-authoritative selected reward and a completion callback.</p>
 */
public final class OpeningProfilePresentationService {
    private static final Map<PlexonCrates, OpeningProfilePresentationService> SHARED = new WeakHashMap<>();

    public static synchronized OpeningProfilePresentationService shared(PlexonCrates plugin) {
        return SHARED.computeIfAbsent(Objects.requireNonNull(plugin, "plugin"),
                OpeningProfilePresentationService::new);
    }

    private final PlexonCrates plugin;
    private final OpeningAnimationCoordinator coordinator;

    private OpeningProfilePresentationService(PlexonCrates plugin) {
        this.plugin = plugin;
        this.coordinator = new OpeningAnimationCoordinator(plugin);
    }

    /**
     * Presents one already-selected reward using a non-INSTANT 6.0 profile.
     * Completion is invoked exactly once by the shared coordinator, including
     * when the viewer closes the animation early.
     */
    public void present(Player player, Crate crate, CrateReward selected,
                        OpeningAnimationProfile profile, Runnable completed) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(crate, "crate");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(completed, "completed");
        if (profile.style() == OpeningAnimationStyle.INSTANT) {
            throw new IllegalArgumentException("INSTANT presentation has no animation surface");
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin,
                    () -> present(player, crate, selected, profile, completed));
            return;
        }

        MenuConfig menus = plugin.menusConfig();
        MenuHolder holder = new MenuHolder(MenuHolder.Kind.OPENING, crate.id(), selected.id(), 0, false,
                plugin.runtime().crateRevision(crate.id()));
        Inventory inventory = Bukkit.createInventory(holder, menus.size("opening"),
                menus.title("opening", Text.component("crate", crate.displayName())));
        holder.attach(inventory);
        fill(inventory);

        inventory.setItem(menus.slot("opening.marker-top-slot"), menus.item("opening.marker"));
        inventory.setItem(menus.slot("opening.marker-bottom-slot"), menus.item("opening.marker"));
        List<Integer> rail = menus.slots("opening.rail-slots");
        List<CrateReward> visuals = crate.orderedRewards().stream()
                .filter(reward -> reward.eligible(player))
                .toList();
        if (visuals.isEmpty()) visuals = List.of(selected);

        if (rollingStyle(profile.style())) {
            for (int slot : rail) inventory.setItem(slot, randomDisplay(visuals));
        } else {
            inventory.setItem(menus.slot("opening.center-slot"),
                    new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        }

        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory() == inventory) {
            plugin.guiSessions().activate(player.getUniqueId(), holder);
        }
        coordinator.start(player, holder, inventory, rail, visuals, selected,
                menus.slot("opening.center-slot"), profile, completed);
    }

    public int activeCount() {
        return coordinator.activeCount();
    }

    public void stop() {
        coordinator.stop();
    }

    private static boolean rollingStyle(OpeningAnimationStyle style) {
        return style == OpeningAnimationStyle.ROULETTE
                || style == OpeningAnimationStyle.SPIN
                || style == OpeningAnimationStyle.CASCADE;
    }

    private static ItemStack randomDisplay(List<CrateReward> rewards) {
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())).displayCopy();
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Component.empty()));
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }
}

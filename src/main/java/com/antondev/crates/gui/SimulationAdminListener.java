package com.antondev.crates.gui;

import com.antondev.crates.PlexonCratesPremium;
import com.antondev.crates.config.Text;
import com.antondev.crates.model.Crate;
import com.antondev.crates.model.CrateReward;
import com.antondev.crates.service.CrateSimulationService;
import com.antondev.crates.service.CrateSimulationService.Mode;
import com.antondev.crates.service.CrateSimulationService.Outcome;
import com.antondev.crates.service.CrateSimulationService.Probability;
import com.antondev.crates.service.CrateSimulationService.Report;
import com.antondev.crates.service.CrateSimulationService.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * Additive 5.0 admin simulation surface. It deliberately does not bind an
 * action into the mature AdminMenuService router; the existing router sees an
 * unbound editor slot and safely ignores it while this listener handles the
 * marked item independently.
 */
public final class SimulationAdminListener implements Listener {
    private static final int[] EDITOR_SLOTS = {43, 44};
    private static final int[] REPORT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int REPORT_PAGE_SIZE = REPORT_SLOTS.length;

    private final PlexonCratesPremium plugin;
    private final CrateSimulationService simulations;
    private final NamespacedKey editorMarker;

    public SimulationAdminListener(PlexonCratesPremium plugin, CrateSimulationService simulations) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.simulations = Objects.requireNonNull(simulations, "simulations");
        this.editorMarker = new NamespacedKey(plugin, "phase2_simulation");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void decorateEditor(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!player.hasPermission("plexoncrates.admin.simulate")) return;
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)
                || holder.kind() != MenuHolder.Kind.EDITOR) return;
        for (int slot : EDITOR_SLOTS) {
            ItemStack current = event.getInventory().getItem(slot);
            if (current != null && !current.getType().isAir()) continue;
            event.getInventory().setItem(slot, editorButton());
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof MenuHolder holder && holder.kind() == MenuHolder.Kind.EDITOR) {
            if (event.getClickedInventory() != top || !marked(event.getCurrentItem())) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)
                    || !player.hasPermission("plexoncrates.admin.simulate")) return;
            openHub(player, holder.crateId(), Mode.CONFIGURED);
            return;
        }
        if (!(top.getHolder() instanceof SimulationHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!holder.playerId.equals(player.getUniqueId())) return;
        if (event.getClickedInventory() != top) return;
        handleSimulationClick(player, holder, event.getRawSlot());
    }

    private void handleSimulationClick(Player player, SimulationHolder holder, int slot) {
        if (slot == 22 && holder.view == View.HUB) {
            backToEditor(player, holder.crateId);
            return;
        }
        if (holder.view == View.HUB) {
            if (slot == 10) {
                runDrySelection(player, holder.snapshot);
            } else if (slot == 12) {
                runSimulation(player, holder.snapshot, 1_000);
            } else if (slot == 13) {
                runSimulation(player, holder.snapshot, CrateSimulationService.DEFAULT_SAMPLES);
            } else if (slot == 14) {
                runSimulation(player, holder.snapshot, CrateSimulationService.MAX_SAMPLES);
            } else if (slot == 16) {
                Mode next = holder.snapshot.mode() == Mode.CONFIGURED ? Mode.PLAYER_CONTEXT : Mode.CONFIGURED;
                openHub(player, holder.crateId, next);
            }
            return;
        }
        if (holder.view == View.DRY) {
            if (slot == 22) openHub(player, holder.crateId, holder.snapshot.mode());
            return;
        }
        if (holder.view == View.REPORT && holder.report != null) {
            if (slot == 45 && holder.page > 0) openReport(player, holder.report, holder.page - 1);
            else if (slot == 49) openHub(player, holder.crateId, holder.snapshot.mode());
            else if (slot == 53 && (holder.page + 1) * REPORT_PAGE_SIZE < holder.report.outcomes().size()) {
                openReport(player, holder.report, holder.page + 1);
            }
        }
    }

    private void openHub(Player player, String crateId, Mode mode) {
        Crate crate = plugin.crates().find(crateId).orElse(null);
        if (crate == null) {
            player.sendActionBar(Text.parse("<red>That crate no longer exists.</red>"));
            player.closeInventory();
            return;
        }
        Snapshot snapshot = snapshot(player, crate, mode);
        SimulationHolder holder = new SimulationHolder(player, crateId, View.HUB, snapshot, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Text.parse("<gradient:#8CDFFF:#D8F6FF><bold>CRATE TEST LAB</bold></gradient> <dark_gray>•</dark_gray> <white>" + crate.id() + "</white>"));
        holder.attach(inventory);
        fill(inventory);

        List<Component> readinessLore = new ArrayList<>();
        readinessLore.add(line("Enabled rewards", Integer.toString(snapshot.enabledRewardCount())));
        readinessLore.add(line("Disabled rewards", Integer.toString(snapshot.disabledRewardCount())));
        readinessLore.add(line("Configured total", percent(snapshot.configuredBasisPointTotal())));
        readinessLore.add(line("Eligible source total", percent(snapshot.eligibleBasisPointTotal())));
        readinessLore.add(Component.empty());
        if (snapshot.publicationReady()) {
            readinessLore.add(Component.text("Publication checks pass.", NamedTextColor.GREEN));
        } else {
            readinessLore.add(Component.text("Publication issues: " + snapshot.publicationIssues().size(), NamedTextColor.YELLOW));
            snapshot.publicationIssues().stream().limit(4)
                    .forEach(issue -> readinessLore.add(Component.text("• " + issue, NamedTextColor.GRAY)));
            if (snapshot.publicationIssues().size() > 4) {
                readinessLore.add(Component.text("• …and " + (snapshot.publicationIssues().size() - 4) + " more", NamedTextColor.DARK_GRAY));
            }
        }
        inventory.setItem(4, item(snapshot.publicationReady() ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE,
                snapshot.publicationReady() ? "<green><bold>Publication Ready</bold></green>"
                        : "<yellow><bold>Readiness Warnings</bold></yellow>", readinessLore));

        inventory.setItem(10, item(Material.TARGET, "<aqua><bold>Dry Selection</bold></aqua>", List.of(
                Component.text("One deterministic analytical selection.", NamedTextColor.GRAY),
                Component.text("Consumes no key and grants nothing.", NamedTextColor.GREEN))));
        inventory.setItem(12, sampleButton(1_000));
        inventory.setItem(13, sampleButton(CrateSimulationService.DEFAULT_SAMPLES));
        inventory.setItem(14, sampleButton(CrateSimulationService.MAX_SAMPLES));
        inventory.setItem(16, item(Material.COMPARATOR, "<light_purple><bold>Simulation Mode</bold></light_purple>", List.of(
                line("Current", modeLabel(mode)),
                Component.text(snapshot.contextNote(), NamedTextColor.GRAY),
                Component.text("Click to switch mode.", NamedTextColor.DARK_GRAY))));
        inventory.setItem(18, item(Material.CLOCK, "<white>Async Analysis Runtime</white>", List.of(
                line("Active", Integer.toString(simulations.activeRequests())),
                line("Queued", Integer.toString(simulations.queuedRequests())),
                Component.text("One bounded worker • no per-player task", NamedTextColor.DARK_GRAY))));
        inventory.setItem(22, item(Material.ARROW, "<gray>Back to Crate Editor</gray>", List.of()));
        player.openInventory(inventory);
    }

    private void runDrySelection(Player player, Snapshot snapshot) {
        if (!current(player, snapshot)) {
            stale(player, snapshot.crateId(), snapshot.mode());
            return;
        }
        try {
            long seed = stableSeed(snapshot, 1);
            String selected = simulations.dryRun(snapshot, seed);
            openDryResult(player, snapshot, selected, seed);
        } catch (RuntimeException error) {
            player.sendActionBar(Text.parse("<red>Dry run unavailable:</red> <gray>" + safe(error.getMessage()) + "</gray>"));
        }
    }

    private void openDryResult(Player player, Snapshot snapshot, String selectedId, long seed) {
        if (!current(player, snapshot)) {
            stale(player, snapshot.crateId(), snapshot.mode());
            return;
        }
        Crate crate = plugin.crates().find(snapshot.crateId()).orElse(null);
        if (crate == null) return;
        SimulationHolder holder = new SimulationHolder(player, crate.id(), View.DRY, snapshot, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Text.parse("<aqua><bold>NON-GRANTING DRY RUN</bold></aqua>"));
        holder.attach(inventory);
        fill(inventory);
        CrateReward reward = crate.rewards().get(selectedId);
        ItemStack display = reward == null ? new ItemStack(Material.CHEST) : reward.displayCopy();
        appendLore(display, List.of(
                Component.empty(),
                line("Selected reward", selectedId),
                line("Mode", modeLabel(snapshot.mode())),
                line("Seed", Long.toUnsignedString(seed)),
                Component.empty(),
                Component.text("ANALYTICAL ONLY — nothing was granted.", NamedTextColor.GREEN)));
        inventory.setItem(13, display);
        inventory.setItem(4, item(Material.BOOK, "<white>Dry-Run Contract</white>", List.of(
                Component.text("No keys consumed", NamedTextColor.GRAY),
                Component.text("No commands/economy/XP/items executed", NamedTextColor.GRAY),
                Component.text("No journal, claim, milestone or reroll mutation", NamedTextColor.GRAY))));
        inventory.setItem(22, item(Material.ARROW, "<gray>Back to Test Lab</gray>", List.of()));
        player.openInventory(inventory);
    }

    private void runSimulation(Player player, Snapshot snapshot, int samples) {
        if (!current(player, snapshot)) {
            stale(player, snapshot.crateId(), snapshot.mode());
            return;
        }
        long seed = stableSeed(snapshot, samples);
        player.sendActionBar(Text.parse("<aqua>Running " + samples + " analytical rolls off-thread…</aqua>"));
        simulations.simulateAsync(snapshot, samples, seed).whenComplete((report, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (error != null) {
                    player.sendActionBar(Text.parse("<red>Simulation failed:</red> <gray>" + safe(rootMessage(error)) + "</gray>"));
                    openHub(player, snapshot.crateId(), snapshot.mode());
                    return;
                }
                if (!current(player, report.snapshot())) {
                    stale(player, snapshot.crateId(), snapshot.mode());
                    return;
                }
                openReport(player, report, 0);
            });
        });
    }

    private void openReport(Player player, Report report, int page) {
        if (!current(player, report.snapshot())) {
            stale(player, report.snapshot().crateId(), report.snapshot().mode());
            return;
        }
        Crate crate = plugin.crates().find(report.snapshot().crateId()).orElse(null);
        if (crate == null) return;
        int pages = Math.max(1, (report.outcomes().size() + REPORT_PAGE_SIZE - 1) / REPORT_PAGE_SIZE);
        int boundedPage = Math.max(0, Math.min(page, pages - 1));
        SimulationHolder holder = new SimulationHolder(player, crate.id(), View.REPORT,
                report.snapshot(), report, boundedPage);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Text.parse("<gradient:#8CDFFF:#D8F6FF><bold>SIMULATION REPORT</bold></gradient> <dark_gray>•</dark_gray> <gray>"
                        + (boundedPage + 1) + "/" + pages + "</gray>"));
        holder.attach(inventory);
        fill(inventory);
        int start = boundedPage * REPORT_PAGE_SIZE;
        for (int index = 0; index < REPORT_PAGE_SIZE && start + index < report.outcomes().size(); index++) {
            Outcome outcome = report.outcomes().get(start + index);
            CrateReward reward = crate.rewards().get(outcome.id());
            ItemStack display = reward == null ? new ItemStack(Material.PAPER) : reward.displayCopy();
            appendLore(display, List.of(
                    Component.empty(),
                    line("Base chance", format(outcome.baseBasisPoints() / 100.0) + "%"),
                    line("Expected effective", format(outcome.expectedBasisPoints() / 100.0) + "%"),
                    line("Observed", format(outcome.observedPercent()) + "%"),
                    line("Absolute deviation", format(outcome.deviationPercentagePoints()) + " pp"),
                    line("Observed hits", Integer.toString(outcome.observedCount()))));
            inventory.setItem(REPORT_SLOTS[index], display);
        }
        inventory.setItem(4, item(Material.FILLED_MAP, "<white><bold>Expected vs Observed</bold></white>", List.of(
                line("Rolls", Integer.toString(report.samples())),
                line("Seed", Long.toUnsignedString(report.seed())),
                line("Mode", modeLabel(report.snapshot().mode())),
                line("Dry selection", report.drySelection()),
                line("Publication", report.snapshot().publicationReady() ? "ready" : "issues: " + report.snapshot().publicationIssues().size()),
                Component.text("Δ is absolute percentage-point deviation.", NamedTextColor.DARK_GRAY),
                Component.text("Simulation is deterministic and non-granting.", NamedTextColor.GREEN))));
        if (boundedPage > 0) inventory.setItem(45, item(Material.ARROW, "<gray>Previous</gray>", List.of()));
        inventory.setItem(49, item(Material.OAK_DOOR, "<gray>Back to Test Lab</gray>", List.of()));
        if ((boundedPage + 1) * REPORT_PAGE_SIZE < report.outcomes().size()) {
            inventory.setItem(53, item(Material.ARROW, "<gray>Next</gray>", List.of()));
        }
        player.openInventory(inventory);
    }

    private Snapshot snapshot(Player player, Crate crate, Mode mode) {
        long revision = currentRevision(player, crate.id());
        List<Probability> probabilities = crate.orderedRewards().stream().map(reward -> new Probability(
                reward.id(), reward.chanceBasisPoints(), reward.enabled(), reward.eligible(player))).toList();
        List<String> issues = plugin.crates().publishingIssues(crate.id(), plugin.keys());
        String note = mode == Mode.CONFIGURED
                ? "Enabled configured rewards; permission/date eligibility is not applied."
                : "Player permission/date eligibility snapshot; dynamic limits and alternative fallbacks are not simulated.";
        return new Snapshot(crate.id(), revision, mode, probabilities, issues, note);
    }

    private boolean current(Player player, Snapshot snapshot) {
        return CrateSimulationService.isCurrent(snapshot, currentRevision(player, snapshot.crateId()));
    }

    private long currentRevision(Player player, String crateId) {
        return plugin.draftSessions().view(player.getUniqueId(), crateId)
                .map(view -> view.revision())
                .orElseGet(() -> plugin.definitionRevision(crateId));
    }

    private void stale(Player player, String crateId, Mode mode) {
        player.sendActionBar(Text.parse("<yellow>Simulation result discarded: the crate revision changed.</yellow>"));
        openHub(player, crateId, mode);
    }

    private void backToEditor(Player player, String crateId) {
        plugin.crates().find(crateId).ifPresentOrElse(
                crate -> plugin.adminMenus().openCrateEditor(player, crate),
                player::closeInventory);
    }

    private ItemStack editorButton() {
        ItemStack item = item(Material.SPYGLASS, "<gradient:#72D9FF:#C8F3FF><bold>Test & Simulate</bold></gradient>", List.of(
                Component.text("Dry-run reward selection without granting.", NamedTextColor.GRAY),
                Component.text("Run bounded expected-vs-observed simulations.", NamedTextColor.GRAY),
                Component.text("No key, reward, journal or player state is mutated.", NamedTextColor.GREEN),
                Component.text("Click to open the Test Lab.", NamedTextColor.DARK_GRAY)));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(editorMarker, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean marked(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(editorMarker, PersistentDataType.BYTE);
    }

    private static ItemStack sampleButton(int samples) {
        return item(Material.EXPERIENCE_BOTTLE, "<aqua>Simulate " + samples + " rolls</aqua>", List.of(
                Component.text("Deterministic Monte Carlo analysis.", NamedTextColor.GRAY),
                Component.text("Runs on the bounded analysis worker.", NamedTextColor.GRAY),
                Component.text("No gameplay state is changed.", NamedTextColor.GREEN)));
    }

    private static ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.parse(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static void appendLore(ItemStack item, List<Component> extra) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.addAll(extra.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static String modeLabel(Mode mode) {
        return mode == Mode.CONFIGURED ? "CONFIGURED" : "PLAYER CONTEXT";
    }

    private static String percent(int basisPoints) {
        return format(basisPoints / 100.0) + "%";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static long stableSeed(Snapshot snapshot, int samples) {
        long hash = 0xcbf29ce484222325L;
        String value = snapshot.crateId() + ":" + snapshot.revision() + ":" + snapshot.mode() + ":" + samples;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        return value.replace('<', '[').replace('>', ']');
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private enum View { HUB, DRY, REPORT }

    private static final class SimulationHolder implements InventoryHolder {
        private final java.util.UUID playerId;
        private final String crateId;
        private final View view;
        private final Snapshot snapshot;
        private final Report report;
        private final int page;
        private Inventory inventory;

        private SimulationHolder(Player player, String crateId, View view, Snapshot snapshot, Report report, int page) {
            this.playerId = player.getUniqueId();
            this.crateId = crateId;
            this.view = view;
            this.snapshot = snapshot;
            this.report = report;
            this.page = page;
        }

        private void attach(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) throw new IllegalStateException("Simulation inventory is not attached");
            return inventory;
        }
    }
}

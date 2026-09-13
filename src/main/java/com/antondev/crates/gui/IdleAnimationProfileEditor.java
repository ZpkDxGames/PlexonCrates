package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.animation.IdleAnimationMath;
import com.antondev.crates.animation.IdleAnimationProfile;
import com.antondev.crates.animation.IdleAnimationProfileStore;
import com.antondev.crates.animation.IdleAnimationProfiles;
import com.antondev.crates.animation.IdleAnimationStyle;
import com.antondev.crates.config.Text;
import com.antondev.crates.service.CrateSimulationService.Mode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/** Non-listener editor for named physical-crate idle animation profiles. */
public final class IdleAnimationProfileEditor {
    private static final List<Integer> PROFILE_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    private static final List<Particle> SAFE_PARTICLES = List.of(
            Particle.END_ROD, Particle.CRIT, Particle.PORTAL, Particle.HAPPY_VILLAGER,
            Particle.SOUL_FIRE_FLAME, Particle.ELECTRIC_SPARK, Particle.TOTEM_OF_UNDYING);

    @FunctionalInterface
    public interface ReturnHandler {
        void open(Player player, String crateId, Mode mode);
    }

    private final PlexonCrates plugin;
    private final IdleAnimationProfileStore store;
    private final ReturnHandler returnHandler;

    public IdleAnimationProfileEditor(PlexonCrates plugin, IdleAnimationProfileStore store,
                                      ReturnHandler returnHandler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.returnHandler = Objects.requireNonNull(returnHandler, "returnHandler");
    }

    public void open(Player player, String crateId, Mode mode) {
        openList(player, crateId, mode, 0);
    }

    public boolean routeClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IdleProfileHolder holder)) return false;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.playerId.equals(player.getUniqueId())
                || event.getClickedInventory() != event.getView().getTopInventory()) return true;
        int slot = event.getRawSlot();
        boolean right = event.isRightClick();
        boolean shift = event.isShiftClick();
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = Bukkit.getPlayer(playerId);
            if (target == null || !target.isOnline() || !current(target, holder)) return;
            if (holder.view == View.LIST) listClick(target, holder, slot);
            else detailClick(target, holder, slot, right, shift);
        });
        return true;
    }

    public boolean routeDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IdleProfileHolder)) return false;
        event.setCancelled(true);
        return true;
    }

    private void openList(Player player, String crateId, Mode mode, int requestedPage) {
        IdleAnimationProfiles.Snapshot snapshot = store.snapshot();
        List<String> ids = snapshot.profiles().keySet().stream().sorted().toList();
        int pages = Math.max(1, (ids.size() + PROFILE_SLOTS.size() - 1) / PROFILE_SLOTS.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        IdleProfileHolder holder = new IdleProfileHolder(player.getUniqueId(), crateId, mode, View.LIST, "", page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Text.parse("<gradient:#8CDFFF:#D8F6FF><bold>IDLE PROFILES</bold></gradient> <dark_gray>•</dark_gray> <gray>"
                        + (page + 1) + "/" + pages + "</gray>"));
        holder.attach(inventory);
        fill(inventory);

        String assigned = snapshot.crateAssignments().get(crateId);
        String effective = assigned == null ? snapshot.globalProfileId() : assigned;
        IdleAnimationProfile resolved = store.resolve(crateId, plugin.settings().idleParticleProfile());
        inventory.setItem(4, item(Material.END_ROD, "<white><bold>Idle Assignment</bold></white>", List.of(
                line("Crate", crateId),
                line("Effective profile", effective),
                line("Style", resolved.style()),
                line("Assignment", assigned == null ? "inherits global" : "crate override"),
                line("Global profile", snapshot.globalProfileId()),
                Component.empty(),
                Component.text("legacy preserves particles.* from config.yml.", NamedTextColor.GRAY),
                Component.text("Idle effects are cosmetic and share one bounded scheduler.", NamedTextColor.GREEN))));

        int start = page * PROFILE_SLOTS.size();
        for (int index = 0; index < PROFILE_SLOTS.size() && start + index < ids.size(); index++) {
            String id = ids.get(start + index);
            IdleAnimationProfile profile = snapshot.profiles().get(id);
            boolean isEffective = id.equals(effective);
            List<Component> lore = new ArrayList<>();
            lore.add(line("Style", profile.style()));
            lore.add(line("Particle", profile.particle()));
            lore.add(line("Range", format(profile.receiverRange()) + " blocks"));
            lore.add(line("Points", profile.points()));
            if (isEffective) lore.add(Component.text("Effective for this crate", NamedTextColor.GREEN));
            if (id.equals(snapshot.globalProfileId())) {
                lore.add(Component.text("Global profile", NamedTextColor.AQUA));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Click to edit.", NamedTextColor.AQUA));
            int slot = PROFILE_SLOTS.get(index);
            inventory.setItem(slot, item(isEffective ? Material.ENCHANTED_BOOK : Material.BOOK,
                    "<white>" + id + "</white>", lore));
            holder.actions.add(new SlotAction(slot, "edit", id));
        }

        if (page > 0) action(holder, inventory, 45, "previous", "", Material.ARROW, "<gray>Previous</gray>");
        action(holder, inventory, 48, "clone-effective", effective, Material.SLIME_BALL,
                "<green>Clone Effective Profile</green>");
        action(holder, inventory, 49, "back", "", Material.OAK_DOOR, "<gray>Back to Test Lab</gray>");
        action(holder, inventory, 50, "inherit", "", Material.COMPARATOR, "<yellow>Inherit Global</yellow>");
        action(holder, inventory, 51, "legacy-global", "", Material.RECOVERY_COMPASS,
                "<yellow>Preserve Legacy Globally</yellow>");
        action(holder, inventory, 52, "legacy-crate", "", Material.CLOCK,
                "<yellow>Use Legacy for This Crate</yellow>");
        if ((page + 1) * PROFILE_SLOTS.size() < ids.size()) {
            action(holder, inventory, 53, "next", "", Material.ARROW, "<gray>Next</gray>");
        }
        player.openInventory(inventory);
    }

    private void openDetail(Player player, String crateId, Mode mode, String profileId) {
        IdleAnimationProfile profile = store.snapshot().profiles().get(profileId);
        if (profile == null) {
            openList(player, crateId, mode, 0);
            return;
        }
        IdleProfileHolder holder = new IdleProfileHolder(player.getUniqueId(), crateId, mode, View.DETAIL, profileId, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Text.parse("<gradient:#8CDFFF:#D8F6FF><bold>IDLE PROFILE</bold></gradient> <dark_gray>•</dark_gray> <white>"
                        + profileId + "</white>"));
        holder.attach(inventory);
        fill(inventory);

        inventory.setItem(4, item(Material.NETHER_STAR, "<white><bold>" + profileId + "</bold></white>", List.of(
                line("Style", profile.style()), line("Particle", profile.particle()),
                line("Radius", format(profile.radius())), line("Height", format(profile.height())),
                line("Points", profile.points()), line("Particles / point", profile.particlesPerPoint()),
                line("Receiver range", format(profile.receiverRange())),
                Component.empty(),
                Component.text("Left increases • right decreases • shift uses a larger step.", NamedTextColor.DARK_GRAY))));

        IdleAnimationStyle[] styles = IdleAnimationStyle.values();
        for (int index = 0; index < styles.length; index++) {
            IdleAnimationStyle style = styles[index];
            int slot = 9 + index;
            inventory.setItem(slot, item(style == profile.style() ? Material.LIME_DYE : Material.GRAY_DYE,
                    (style == profile.style() ? "<green>" : "<gray>") + human(style.name())
                            + (style == profile.style() ? "</green>" : "</gray>"),
                    List.of(Component.text(style == profile.style() ? "Selected" : "Click to select", NamedTextColor.GRAY))));
            holder.actions.add(new SlotAction(slot, "style", style.name()));
        }

        control(holder, inventory, 19, "particle", Material.END_ROD, "<light_purple>Particle</light_purple>",
                line("Current", profile.particle()), "Click to cycle safe presets.");
        control(holder, inventory, 20, "radius", Material.COMPASS, "<aqua>Radius</aqua>",
                line("Current", format(profile.radius())), "Left +0.10 • Right -0.10 • Shift step 0.50");
        control(holder, inventory, 21, "height", Material.SCAFFOLDING, "<aqua>Height</aqua>",
                line("Current", format(profile.height())), "Left +0.10 • Right -0.10 • Shift step 0.50");
        control(holder, inventory, 22, "points", Material.GLOWSTONE_DUST, "<yellow>Geometry Points</yellow>",
                line("Current", profile.points()), "Left +1 • Right -1 • Shift step 8");
        control(holder, inventory, 23, "rotation", Material.CLOCK, "<yellow>Rotation Speed</yellow>",
                line("Current", format(profile.rotationSpeed())), "Left +0.05 • Right -0.05 • Shift step 0.25");
        control(holder, inventory, 24, "vertical", Material.FEATHER, "<yellow>Vertical Speed</yellow>",
                line("Current", format(profile.verticalSpeed())), "Left +0.05 • Right -0.05 • Shift step 0.25");
        control(holder, inventory, 25, "per-point", Material.BLAZE_POWDER, "<yellow>Particles / Point</yellow>",
                line("Current", profile.particlesPerPoint()), "Left +1 • Right -1 • Shift step 4");
        control(holder, inventory, 28, "range", Material.SPYGLASS, "<aqua>Receiver Range</aqua>",
                line("Current", format(profile.receiverRange()) + " blocks"), "Left +4 • Right -4 • Shift step 16");
        control(holder, inventory, 29, "crate-budget", Material.CHEST, "<gold>Per-Crate Budget</gold>",
                line("Current", profile.maxPerCratePerTick() + "/tick"), "Left +10 • Right -10 • Shift step 50");
        control(holder, inventory, 30, "viewer-budget", Material.PLAYER_HEAD, "<gold>Per-Viewer Budget</gold>",
                line("Current", profile.maxPerViewerPerTick() + "/tick"), "Left +10 • Right -10 • Shift step 50");
        action(holder, inventory, 32, "preview", "", Material.ENDER_EYE, "<green>Preview One Frame</green>");

        action(holder, inventory, 45, "assign-crate", "", Material.CHEST, "<green>Assign to This Crate</green>");
        action(holder, inventory, 46, "inherit", "", Material.COMPARATOR, "<yellow>Inherit Global</yellow>");
        action(holder, inventory, 47, "set-global", "", Material.BEACON, "<aqua>Set Global</aqua>");
        action(holder, inventory, 48, "clone", "", Material.SLIME_BALL, "<green>Clone Profile</green>");
        action(holder, inventory, 49, "back", "", Material.OAK_DOOR, "<gray>Profile List</gray>");
        action(holder, inventory, 50, "reset", "", Material.RECOVERY_COMPASS, "<yellow>Reset to Legacy Defaults</yellow>");
        action(holder, inventory, 53, "test-lab", "", Material.AMETHYST_SHARD, "<aqua>Back to Test Lab</aqua>");
        player.openInventory(inventory);
    }

    private void listClick(Player player, IdleProfileHolder holder, int slot) {
        SlotAction action = holder.action(slot);
        if (action == null) return;
        switch (action.id) {
            case "edit" -> openDetail(player, holder.crateId, holder.mode, action.value);
            case "previous" -> openList(player, holder.crateId, holder.mode, holder.page - 1);
            case "next" -> openList(player, holder.crateId, holder.mode, holder.page + 1);
            case "back" -> returnHandler.open(player, holder.crateId, holder.mode);
            case "inherit" -> mutate(player, snapshot -> IdleAnimationProfiles.inheritGlobal(snapshot, holder.crateId),
                    () -> openList(player, holder.crateId, holder.mode, holder.page));
            case "legacy-global" -> mutate(player,
                    snapshot -> IdleAnimationProfiles.withGlobal(snapshot, IdleAnimationProfiles.LEGACY_INHERIT),
                    () -> openList(player, holder.crateId, holder.mode, holder.page));
            case "legacy-crate" -> mutate(player,
                    snapshot -> IdleAnimationProfiles.assign(snapshot, holder.crateId, IdleAnimationProfiles.LEGACY_INHERIT),
                    () -> openList(player, holder.crateId, holder.mode, holder.page));
            case "clone-effective" -> requestClone(player, holder, action.value);
            default -> { }
        }
    }

    private void detailClick(Player player, IdleProfileHolder holder, int slot, boolean right, boolean shift) {
        SlotAction action = holder.action(slot);
        if (action == null) return;
        IdleAnimationProfile profile = store.snapshot().profiles().get(holder.profileId);
        if (profile == null) {
            openList(player, holder.crateId, holder.mode, 0);
            return;
        }
        switch (action.id) {
            case "style" -> updateProfile(player, holder, current -> copy(current,
                    IdleAnimationStyle.valueOf(action.value), current.particle(), current.radius(), current.height(),
                    current.points(), current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(),
                    current.receiverRange(), current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case "particle" -> updateProfile(player, holder, current -> copy(current, current.style(),
                    next(SAFE_PARTICLES, current.particle()), current.radius(), current.height(), current.points(),
                    current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(), current.receiverRange(),
                    current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case "radius" -> numeric(player, holder, right, shift, Numeric.RADIUS, profile);
            case "height" -> numeric(player, holder, right, shift, Numeric.HEIGHT, profile);
            case "points" -> numeric(player, holder, right, shift, Numeric.POINTS, profile);
            case "rotation" -> numeric(player, holder, right, shift, Numeric.ROTATION, profile);
            case "vertical" -> numeric(player, holder, right, shift, Numeric.VERTICAL, profile);
            case "per-point" -> numeric(player, holder, right, shift, Numeric.PER_POINT, profile);
            case "range" -> numeric(player, holder, right, shift, Numeric.RANGE, profile);
            case "crate-budget" -> numeric(player, holder, right, shift, Numeric.CRATE_BUDGET, profile);
            case "viewer-budget" -> numeric(player, holder, right, shift, Numeric.VIEWER_BUDGET, profile);
            case "preview" -> preview(player, profile);
            case "assign-crate" -> mutate(player,
                    value -> IdleAnimationProfiles.assign(value, holder.crateId, holder.profileId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "inherit" -> mutate(player,
                    value -> IdleAnimationProfiles.inheritGlobal(value, holder.crateId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "set-global" -> mutate(player,
                    value -> IdleAnimationProfiles.withGlobal(value, holder.profileId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "clone" -> requestClone(player, holder, holder.profileId);
            case "reset" -> updateProfile(player, holder, ignored -> plugin.settings().idleParticleProfile());
            case "back" -> openList(player, holder.crateId, holder.mode, 0);
            case "test-lab" -> returnHandler.open(player, holder.crateId, holder.mode);
            default -> { }
        }
    }

    private void numeric(Player player, IdleProfileHolder holder, boolean right, boolean shift,
                         Numeric control, IdleAnimationProfile profile) {
        double direction = right ? -1.0 : 1.0;
        switch (control) {
            case RADIUS -> updateProfile(player, holder, current -> copy(current, current.style(), current.particle(),
                    clamp(current.radius() + direction * (shift ? 0.5 : 0.1), 0.0, 8.0), current.height(), current.points(),
                    current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(), current.receiverRange(),
                    current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case HEIGHT -> updateProfile(player, holder, current -> copy(current, current.style(), current.particle(),
                    current.radius(), clamp(current.height() + direction * (shift ? 0.5 : 0.1), 0.0, 8.0), current.points(),
                    current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(), current.receiverRange(),
                    current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case POINTS -> {
                int next = clamp(profile.points() + (right ? -(shift ? 8 : 1) : (shift ? 8 : 1)), 1, 128);
                updateProfile(player, holder, current -> copy(current, current.style(), current.particle(), current.radius(),
                        current.height(), next, current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(),
                        current.receiverRange(), current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            }
            case ROTATION -> updateProfile(player, holder, current -> copy(current, current.style(), current.particle(),
                    current.radius(), current.height(), current.points(),
                    clamp(current.rotationSpeed() + direction * (shift ? 0.25 : 0.05), -4.0, 4.0),
                    current.verticalSpeed(), current.particlesPerPoint(), current.receiverRange(),
                    current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case VERTICAL -> updateProfile(player, holder, current -> copy(current, current.style(), current.particle(),
                    current.radius(), current.height(), current.points(), current.rotationSpeed(),
                    clamp(current.verticalSpeed() + direction * (shift ? 0.25 : 0.05), -4.0, 4.0),
                    current.particlesPerPoint(), current.receiverRange(), current.maxPerCratePerTick(),
                    current.maxPerViewerPerTick()));
            case PER_POINT -> {
                int next = clamp(profile.particlesPerPoint() + (right ? -(shift ? 4 : 1) : (shift ? 4 : 1)), 1, 32);
                updateProfile(player, holder, current -> copy(current, current.style(), current.particle(), current.radius(),
                        current.height(), current.points(), current.rotationSpeed(), current.verticalSpeed(), next,
                        current.receiverRange(), current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            }
            case RANGE -> updateProfile(player, holder, current -> copy(current, current.style(), current.particle(),
                    current.radius(), current.height(), current.points(), current.rotationSpeed(), current.verticalSpeed(),
                    current.particlesPerPoint(), clamp(current.receiverRange() + direction * (shift ? 16.0 : 4.0), 1.0, 256.0),
                    current.maxPerCratePerTick(), current.maxPerViewerPerTick()));
            case CRATE_BUDGET -> {
                int next = clamp(profile.maxPerCratePerTick() + (right ? -(shift ? 50 : 10) : (shift ? 50 : 10)), 1, 10_000);
                updateProfile(player, holder, current -> copy(current, current.style(), current.particle(), current.radius(),
                        current.height(), current.points(), current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(),
                        current.receiverRange(), next, current.maxPerViewerPerTick()));
            }
            case VIEWER_BUDGET -> {
                int next = clamp(profile.maxPerViewerPerTick() + (right ? -(shift ? 50 : 10) : (shift ? 50 : 10)), 1, 10_000);
                updateProfile(player, holder, current -> copy(current, current.style(), current.particle(), current.radius(),
                        current.height(), current.points(), current.rotationSpeed(), current.verticalSpeed(), current.particlesPerPoint(),
                        current.receiverRange(), current.maxPerCratePerTick(), next));
            }
        }
    }

    private void preview(Player player, IdleAnimationProfile profile) {
        if (!profile.enabled()) {
            player.sendActionBar(Text.parse("<yellow>NONE has no idle particles to preview.</yellow>"));
            return;
        }
        Location origin = player.getLocation().clone().add(0.0, 0.1, 0.0);
        int remaining = Math.min(256, profile.maxPerViewerPerTick());
        for (IdleAnimationMath.Offset offset : IdleAnimationMath.sample(profile, 0L)) {
            if (remaining <= 0) break;
            int count = Math.min(profile.particlesPerPoint(), remaining);
            player.spawnParticle(profile.particle(), origin.clone().add(offset.x(), offset.y(), offset.z()),
                    count, 0.0, 0.0, 0.0, 0.0);
            remaining -= count;
        }
        player.sendActionBar(Text.parse("<green>Rendered one bounded idle-profile preview frame.</green>"));
    }

    private void updateProfile(Player player, IdleProfileHolder holder, UnaryOperator<IdleAnimationProfile> update) {
        mutate(player, snapshot -> {
            IdleAnimationProfile current = snapshot.profiles().get(holder.profileId);
            if (current == null) throw new IllegalStateException("Idle profile no longer exists");
            return IdleAnimationProfiles.withProfile(snapshot, holder.profileId,
                    Objects.requireNonNull(update.apply(current), "profile update"));
        }, () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
    }

    private void requestClone(Player player, IdleProfileHolder holder, String sourceId) {
        plugin.editSessions().request(player,
                Text.parse("<aqua>Enter a new idle profile ID (lowercase letters, numbers, _ or -):</aqua>"),
                (target, raw) -> {
                    String newId = raw.trim().toLowerCase(Locale.ROOT);
                    CompletableFuture<Void> write = store.mutate(snapshot -> {
                        if (snapshot.profiles().containsKey(newId)) {
                            throw new IllegalArgumentException("That idle profile already exists");
                        }
                        IdleAnimationProfile source = IdleAnimationProfiles.LEGACY_INHERIT.equals(sourceId)
                                ? plugin.settings().idleParticleProfile() : snapshot.profiles().get(sourceId);
                        if (source == null) throw new IllegalStateException("Source idle profile no longer exists");
                        return IdleAnimationProfiles.withProfile(snapshot, newId, source);
                    });
                    writeFeedback(target, write);
                    openDetail(target, holder.crateId, holder.mode, newId);
                });
    }

    private void mutate(Player player, UnaryOperator<IdleAnimationProfiles.Snapshot> mutation, Runnable reopen) {
        try {
            CompletableFuture<Void> write = store.mutate(mutation);
            writeFeedback(player, write);
            reopen.run();
        } catch (RuntimeException error) {
            player.sendActionBar(Text.parse("<red>Idle profile change rejected:</red> <gray>"
                    + safe(rootMessage(error)) + "</gray>"));
        }
    }

    private void writeFeedback(Player player, CompletableFuture<Void> write) {
        UUID playerId = player.getUniqueId();
        write.whenComplete((ignored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(playerId);
                if (target == null || !target.isOnline()) return;
                target.sendActionBar(error == null
                        ? Text.parse("<green>Idle profile saved.</green>")
                        : Text.parse("<red>Idle profile is live but could not be saved:</red> <gray>"
                                + safe(rootMessage(error)) + "</gray>"));
            });
        });
    }

    private static IdleAnimationProfile copy(IdleAnimationProfile current, IdleAnimationStyle style, Particle particle,
                                             double radius, double height, int points, double rotationSpeed,
                                             double verticalSpeed, int particlesPerPoint, double receiverRange,
                                             int maxPerCrate, int maxPerViewer) {
        return new IdleAnimationProfile(style, particle, radius, height, points, rotationSpeed, verticalSpeed,
                particlesPerPoint, receiverRange, maxPerCrate, maxPerViewer);
    }

    private static <T> T next(List<T> values, T current) {
        int index = values.indexOf(current);
        return values.get(index < 0 || index + 1 >= values.size() ? 0 : index + 1);
    }

    private static void action(IdleProfileHolder holder, Inventory inventory, int slot, String id, String value,
                               Material material, String name) {
        inventory.setItem(slot, item(material, name, List.of()));
        holder.actions.add(new SlotAction(slot, id, value));
    }

    private static void control(IdleProfileHolder holder, Inventory inventory, int slot, String id,
                                Material material, String name, Component value, String hint) {
        inventory.setItem(slot, item(material, name, List.of(value, Component.text(hint, NamedTextColor.DARK_GRAY))));
        holder.actions.add(new SlotAction(slot, id, ""));
    }

    private static ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.parse(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        GuiChromeRenderer.render(inventory, plugin.menusConfig());
    }

    private static Component line(String label, Object value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    private static String human(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static boolean current(Player player, IdleProfileHolder holder) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top == holder.getInventory() && top.getHolder() == holder;
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

    private enum View { LIST, DETAIL }
    private enum Numeric { RADIUS, HEIGHT, POINTS, ROTATION, VERTICAL, PER_POINT, RANGE, CRATE_BUDGET, VIEWER_BUDGET }
    private record SlotAction(int slot, String id, String value) { }

    private static final class IdleProfileHolder implements InventoryHolder {
        private final UUID playerId;
        private final String crateId;
        private final Mode mode;
        private final View view;
        private final String profileId;
        private final int page;
        private final List<SlotAction> actions = new ArrayList<>();
        private Inventory inventory;

        private IdleProfileHolder(UUID playerId, String crateId, Mode mode, View view, String profileId, int page) {
            this.playerId = playerId;
            this.crateId = crateId;
            this.mode = mode;
            this.view = view;
            this.profileId = profileId;
            this.page = page;
        }

        private SlotAction action(int slot) {
            return actions.stream().filter(action -> action.slot == slot).findFirst().orElse(null);
        }

        private void attach(Inventory inventory) { this.inventory = inventory; }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) throw new IllegalStateException("Idle profile editor inventory is not attached");
            return inventory;
        }
    }
}

package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.animation.OpeningAnimationProfile;
import com.antondev.crates.animation.OpeningAnimationProfileStore;
import com.antondev.crates.animation.OpeningAnimationProfiles;
import com.antondev.crates.animation.OpeningAnimationStage;
import com.antondev.crates.animation.OpeningAnimationStyle;
import com.antondev.crates.config.Text;
import com.antondev.crates.service.CrateSimulationService.Mode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * Non-listener editor for 6.0 opening presentation profiles. Inventory events
 * are delegated here only by SimulationAdminListener/CrateMenuEventRouter.
 */
public final class OpeningAnimationProfileEditor {
    private static final List<Integer> PROFILE_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    private static final List<Particle> SAFE_PARTICLES = List.of(
            Particle.END_ROD, Particle.CRIT, Particle.PORTAL, Particle.HAPPY_VILLAGER,
            Particle.SOUL_FIRE_FLAME, Particle.ELECTRIC_SPARK, Particle.TOTEM_OF_UNDYING);
    private static final List<Sound> SAFE_SOUNDS = List.of(
            Sound.BLOCK_NOTE_BLOCK_PLING,
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            Sound.UI_TOAST_CHALLENGE_COMPLETE,
            Sound.BLOCK_AMETHYST_BLOCK_CHIME,
            Sound.ENTITY_FIREWORK_ROCKET_TWINKLE);

    @FunctionalInterface
    public interface ReturnHandler {
        void open(Player player, String crateId, Mode mode);
    }

    private final PlexonCrates plugin;
    private final OpeningAnimationProfileStore store;
    private final ReturnHandler returnHandler;

    public OpeningAnimationProfileEditor(PlexonCrates plugin, OpeningAnimationProfileStore store,
                                         ReturnHandler returnHandler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.store = Objects.requireNonNull(store, "store");
        this.returnHandler = Objects.requireNonNull(returnHandler, "returnHandler");
    }

    public void open(Player player, String crateId, Mode mode) {
        openList(player, crateId, mode, 0);
    }

    public boolean routeClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ProfileHolder holder)) return false;
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
        if (!(event.getView().getTopInventory().getHolder() instanceof ProfileHolder)) return false;
        event.setCancelled(true);
        return true;
    }

    private void openList(Player player, String crateId, Mode mode, int requestedPage) {
        OpeningAnimationProfiles.Snapshot snapshot = store.snapshot();
        List<String> ids = snapshot.profiles().keySet().stream().sorted().toList();
        int pages = Math.max(1, (ids.size() + PROFILE_SLOTS.size() - 1) / PROFILE_SLOTS.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        ProfileHolder holder = new ProfileHolder(player.getUniqueId(), crateId, mode, View.LIST, "", page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Text.parse("<gradient:#BCA7FF:#E8E0FF><bold>OPENING PROFILES</bold></gradient> <dark_gray>•</dark_gray> <gray>"
                        + (page + 1) + "/" + pages + "</gray>"));
        holder.attach(inventory);
        fill(inventory);

        String assigned = snapshot.crateAssignments().get(crateId);
        String effective = assigned == null ? snapshot.globalProfileId() : assigned;
        inventory.setItem(4, item(Material.AMETHYST_SHARD, "<white><bold>Assignment Status</bold></white>", List.of(
                line("Crate", crateId),
                line("Effective profile", effective),
                line("Assignment", assigned == null ? "inherits global" : "crate override"),
                line("Global profile", snapshot.globalProfileId()),
                Component.empty(),
                Component.text("Profiles are presentation-only; changing them does not alter reward odds or payment.", NamedTextColor.GREEN))));

        int start = page * PROFILE_SLOTS.size();
        for (int index = 0; index < PROFILE_SLOTS.size() && start + index < ids.size(); index++) {
            String id = ids.get(start + index);
            OpeningAnimationProfile profile = snapshot.profiles().get(id);
            boolean isEffective = id.equals(effective);
            boolean isGlobal = id.equals(snapshot.globalProfileId());
            List<Component> lore = new ArrayList<>();
            lore.add(line("Style", profile.style()));
            lore.add(line("Duration", profile.totalTicks() + " ticks"));
            lore.add(line("Particle budget", profile.particleBudgetPerTick() + "/tick"));
            if (isEffective) lore.add(Component.text("Effective for this crate", NamedTextColor.GREEN));
            if (isGlobal) lore.add(Component.text("Global profile", NamedTextColor.LIGHT_PURPLE));
            lore.add(Component.empty());
            lore.add(Component.text("Click to edit this profile.", NamedTextColor.AQUA));
            inventory.setItem(PROFILE_SLOTS.get(index), item(
                    isEffective ? Material.ENCHANTED_BOOK : Material.BOOK,
                    "<white>" + id + "</white>", lore));
            holder.actions.add(new SlotAction(PROFILE_SLOTS.get(index), "edit", id));
        }
        if (page > 0) action(holder, inventory, 45, "previous", "", Material.ARROW, "<gray>Previous</gray>");
        action(holder, inventory, 48, "clone-effective", effective, Material.SLIME_BALL,
                "<green>Clone Effective Profile</green>");
        action(holder, inventory, 49, "back", "", Material.OAK_DOOR, "<gray>Back to Test Lab</gray>");
        action(holder, inventory, 50, "inherit", "", Material.COMPARATOR, "<yellow>Inherit Global</yellow>");
        if ((page + 1) * PROFILE_SLOTS.size() < ids.size()) {
            action(holder, inventory, 53, "next", "", Material.ARROW, "<gray>Next</gray>");
        }
        player.openInventory(inventory);
    }

    private void openDetail(Player player, String crateId, Mode mode, String profileId) {
        OpeningAnimationProfiles.Snapshot snapshot = store.snapshot();
        OpeningAnimationProfile profile = snapshot.profiles().get(profileId);
        if (profile == null) {
            openList(player, crateId, mode, 0);
            return;
        }
        ProfileHolder holder = new ProfileHolder(player.getUniqueId(), crateId, mode, View.DETAIL, profileId, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Text.parse("<gradient:#BCA7FF:#E8E0FF><bold>PROFILE</bold></gradient> <dark_gray>•</dark_gray> <white>"
                        + profileId + "</white>"));
        holder.attach(inventory);
        fill(inventory);

        inventory.setItem(4, item(Material.NETHER_STAR, "<white><bold>" + profileId + "</bold></white>", List.of(
                line("Style", profile.style()), line("Duration", profile.totalTicks() + " ticks"),
                line("Particle", profile.particle()), line("Sound", soundLabel(profile.sound())),
                line("Particle budget", profile.particleBudgetPerTick() + "/tick"),
                line("Receiver range", profile.receiverRange()),
                line("Summary on finish", profile.summaryOnFinish()),
                Component.empty(),
                Component.text("Left click increases; right click decreases. Shift uses a larger step.", NamedTextColor.DARK_GRAY))));

        OpeningAnimationStyle[] styles = OpeningAnimationStyle.values();
        for (int index = 0; index < styles.length; index++) {
            OpeningAnimationStyle style = styles[index];
            int slot = 10 + index;
            inventory.setItem(slot, item(style == profile.style() ? Material.LIME_DYE : Material.GRAY_DYE,
                    (style == profile.style() ? "<green>" : "<gray>") + human(style.name()) + (style == profile.style() ? "</green>" : "</gray>"),
                    List.of(Component.text(style == profile.style() ? "Selected" : "Click to select", NamedTextColor.GRAY))));
            holder.actions.add(new SlotAction(slot, "style", style.name()));
        }

        int stageSlot = 19;
        for (OpeningAnimationStage stage : OpeningAnimationStage.values()) {
            inventory.setItem(stageSlot, item(Material.CLOCK, "<aqua>" + human(stage.name()) + "</aqua>", List.of(
                    line("Ticks", profile.ticks(stage)),
                    Component.text("Left +5 • Right -5 • Shift step 20", NamedTextColor.DARK_GRAY))));
            holder.actions.add(new SlotAction(stageSlot++, "stage", stage.name()));
        }

        control(holder, inventory, 28, "particle", Material.END_ROD, "<light_purple>Particle</light_purple>",
                line("Current", profile.particle()), "Click to cycle safe presets.");
        control(holder, inventory, 29, "sound", Material.NOTE_BLOCK, "<light_purple>Sound</light_purple>",
                line("Current", soundLabel(profile.sound())), "Click to cycle safe presets.");
        control(holder, inventory, 30, "volume", Material.JUKEBOX, "<aqua>Sound Volume</aqua>",
                line("Current", format(profile.soundVolume())), "Left +0.1 • Right -0.1");
        control(holder, inventory, 31, "pitch", Material.GOAT_HORN, "<aqua>Sound Pitch</aqua>",
                line("Current", format(profile.soundPitch())), "Left +0.1 • Right -0.1");
        control(holder, inventory, 32, "particle-budget", Material.BLAZE_POWDER, "<yellow>Particle Budget</yellow>",
                line("Current", profile.particleBudgetPerTick() + "/tick"), "Left +16 • Right -16 • Shift step 64");
        control(holder, inventory, 33, "range", Material.SPYGLASS, "<yellow>Receiver Range</yellow>",
                line("Current", format(profile.receiverRange()) + " blocks"), "Left +4 • Right -4 • Shift step 16");
        control(holder, inventory, 34, "summary", Material.FILLED_MAP, "<white>Summary on Finish</white>",
                line("Current", profile.summaryOnFinish()), "Click to toggle.");

        action(holder, inventory, 45, "assign-crate", "", Material.CHEST, "<green>Assign to This Crate</green>");
        action(holder, inventory, 46, "inherit", "", Material.COMPARATOR, "<yellow>Inherit Global</yellow>");
        action(holder, inventory, 47, "set-global", "", Material.BEACON, "<light_purple>Set Global</light_purple>");
        action(holder, inventory, 48, "clone", "", Material.SLIME_BALL, "<green>Clone Profile</green>");
        action(holder, inventory, 49, "back", "", Material.OAK_DOOR, "<gray>Profile List</gray>");
        action(holder, inventory, 50, "reset", "", Material.RECOVERY_COMPASS, "<yellow>Reset Style Defaults</yellow>");
        action(holder, inventory, 53, "test-lab", "", Material.AMETHYST_SHARD, "<aqua>Return to Animation Preview</aqua>");
        player.openInventory(inventory);
    }

    private void listClick(Player player, ProfileHolder holder, int slot) {
        SlotAction action = holder.action(slot);
        if (action == null) return;
        switch (action.id) {
            case "edit" -> openDetail(player, holder.crateId, holder.mode, action.value);
            case "previous" -> openList(player, holder.crateId, holder.mode, holder.page - 1);
            case "next" -> openList(player, holder.crateId, holder.mode, holder.page + 1);
            case "back" -> returnHandler.open(player, holder.crateId, holder.mode);
            case "inherit" -> mutate(player, snapshot -> OpeningAnimationProfiles.inheritGlobal(snapshot, holder.crateId),
                    () -> openList(player, holder.crateId, holder.mode, holder.page));
            case "clone-effective" -> requestClone(player, holder, action.value);
            default -> { }
        }
    }

    private void detailClick(Player player, ProfileHolder holder, int slot, boolean right, boolean shift) {
        SlotAction action = holder.action(slot);
        if (action == null) return;
        OpeningAnimationProfiles.Snapshot snapshot = store.snapshot();
        OpeningAnimationProfile profile = snapshot.profiles().get(holder.profileId);
        if (profile == null) {
            openList(player, holder.crateId, holder.mode, 0);
            return;
        }
        switch (action.id) {
            case "style" -> updateProfile(player, holder,
                    ignored -> profile.withStyle(OpeningAnimationStyle.valueOf(action.value)));
            case "stage" -> {
                OpeningAnimationStage stage = OpeningAnimationStage.valueOf(action.value);
                int step = shift ? 20 : 5;
                int next = clamp(profile.ticks(stage) + (right ? -step : step), 0, 400);
                updateProfile(player, holder, current -> withStage(current, stage, next));
            }
            case "particle" -> updateProfile(player, holder,
                    current -> copy(current, next(SAFE_PARTICLES, current.particle()), current.sound(),
                            current.soundVolume(), current.soundPitch(), current.particleBudgetPerTick(),
                            current.receiverRange(), current.summaryOnFinish()));
            case "sound" -> updateProfile(player, holder,
                    current -> copy(current, current.particle(), next(SAFE_SOUNDS, current.sound()),
                            current.soundVolume(), current.soundPitch(), current.particleBudgetPerTick(),
                            current.receiverRange(), current.summaryOnFinish()));
            case "volume" -> updateProfile(player, holder, current -> copy(current, current.particle(), current.sound(),
                    clamp((float) (current.soundVolume() + (right ? -0.1 : 0.1)), 0.0f, 4.0f),
                    current.soundPitch(), current.particleBudgetPerTick(), current.receiverRange(), current.summaryOnFinish()));
            case "pitch" -> updateProfile(player, holder, current -> copy(current, current.particle(), current.sound(),
                    current.soundVolume(), clamp((float) (current.soundPitch() + (right ? -0.1 : 0.1)), 0.5f, 2.0f),
                    current.particleBudgetPerTick(), current.receiverRange(), current.summaryOnFinish()));
            case "particle-budget" -> {
                int step = shift ? 64 : 16;
                int next = clamp(profile.particleBudgetPerTick() + (right ? -step : step), 0, 2_000);
                updateProfile(player, holder, current -> copy(current, current.particle(), current.sound(),
                        current.soundVolume(), current.soundPitch(), next, current.receiverRange(), current.summaryOnFinish()));
            }
            case "range" -> {
                double step = shift ? 16.0 : 4.0;
                double next = clamp(profile.receiverRange() + (right ? -step : step), 1.0, 128.0);
                updateProfile(player, holder, current -> copy(current, current.particle(), current.sound(),
                        current.soundVolume(), current.soundPitch(), current.particleBudgetPerTick(), next,
                        current.summaryOnFinish()));
            }
            case "summary" -> updateProfile(player, holder, current -> copy(current, current.particle(), current.sound(),
                    current.soundVolume(), current.soundPitch(), current.particleBudgetPerTick(), current.receiverRange(),
                    !current.summaryOnFinish()));
            case "assign-crate" -> mutate(player,
                    value -> OpeningAnimationProfiles.assign(value, holder.crateId, holder.profileId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "inherit" -> mutate(player,
                    value -> OpeningAnimationProfiles.inheritGlobal(value, holder.crateId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "set-global" -> mutate(player,
                    value -> OpeningAnimationProfiles.withGlobal(value, holder.profileId),
                    () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
            case "clone" -> requestClone(player, holder, holder.profileId);
            case "reset" -> updateProfile(player, holder,
                    current -> OpeningAnimationProfile.defaults(current.style()));
            case "back" -> openList(player, holder.crateId, holder.mode, 0);
            case "test-lab" -> {
                returnHandler.open(player, holder.crateId, holder.mode);
                player.sendActionBar(Text.parse("<aqua>Use Animation Preview to test the effective opening profile without granting.</aqua>"));
            }
            default -> { }
        }
    }

    private void updateProfile(Player player, ProfileHolder holder,
                               UnaryOperator<OpeningAnimationProfile> update) {
        mutate(player, snapshot -> {
            OpeningAnimationProfile current = snapshot.profiles().get(holder.profileId);
            if (current == null) throw new IllegalStateException("Profile no longer exists");
            return OpeningAnimationProfiles.withProfile(snapshot, holder.profileId,
                    Objects.requireNonNull(update.apply(current), "profile update"));
        }, () -> openDetail(player, holder.crateId, holder.mode, holder.profileId));
    }

    private void requestClone(Player player, ProfileHolder holder, String sourceId) {
        plugin.editSessions().request(player,
                Text.parse("<aqua>Enter a new animation profile ID (lowercase letters, numbers, _ or -):</aqua>"),
                (target, raw) -> {
                    String newId = raw.trim().toLowerCase(Locale.ROOT);
                    CompletableFuture<Void> write = store.mutate(snapshot -> {
                        if (snapshot.profiles().containsKey(newId)) {
                            throw new IllegalArgumentException("That animation profile already exists");
                        }
                        OpeningAnimationProfile source = snapshot.profiles().get(sourceId);
                        if (source == null) throw new IllegalStateException("Source animation profile no longer exists");
                        return OpeningAnimationProfiles.withProfile(snapshot, newId, source);
                    });
                    writeFeedback(target, write);
                    openDetail(target, holder.crateId, holder.mode, newId);
                });
    }

    private void mutate(Player player, UnaryOperator<OpeningAnimationProfiles.Snapshot> mutation,
                        Runnable reopen) {
        try {
            CompletableFuture<Void> write = store.mutate(mutation);
            writeFeedback(player, write);
            reopen.run();
        } catch (RuntimeException error) {
            player.sendActionBar(Text.parse("<red>Animation profile change rejected:</red> <gray>"
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
                        ? Text.parse("<green>Animation profile saved.</green>")
                        : Text.parse("<red>Animation profile is live but could not be saved:</red> <gray>"
                                + safe(rootMessage(error)) + "</gray>"));
            });
        });
    }

    private static OpeningAnimationProfile withStage(OpeningAnimationProfile profile,
                                                      OpeningAnimationStage stage, int ticks) {
        EnumMap<OpeningAnimationStage, Integer> values = new EnumMap<>(OpeningAnimationStage.class);
        values.putAll(profile.stageTicks());
        values.put(stage, ticks);
        return new OpeningAnimationProfile(profile.style(), values, profile.particle(), profile.sound(),
                profile.soundVolume(), profile.soundPitch(), profile.particleBudgetPerTick(),
                profile.receiverRange(), profile.summaryOnFinish());
    }

    private static OpeningAnimationProfile copy(OpeningAnimationProfile profile, Particle particle, Sound sound,
                                                float volume, float pitch, int particleBudget,
                                                double range, boolean summary) {
        return new OpeningAnimationProfile(profile.style(), profile.stageTicks(), particle, sound,
                volume, pitch, particleBudget, range, summary);
    }

    private static <T> T next(List<T> values, T current) {
        int index = values.indexOf(current);
        return values.get(index < 0 || index + 1 >= values.size() ? 0 : index + 1);
    }

    private static void action(ProfileHolder holder, Inventory inventory, int slot, String id, String value,
                               Material material, String name) {
        inventory.setItem(slot, item(material, name, List.of()));
        holder.actions.add(new SlotAction(slot, id, value));
    }

    private static void control(ProfileHolder holder, Inventory inventory, int slot, String id,
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

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private static Component line(String label, Object value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    private static String human(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String soundLabel(Sound sound) {
        for (Sound candidate : SAFE_SOUNDS) {
            if (candidate == sound) return human(soundField(candidate));
        }
        return "Custom/other built-in";
    }

    private static String soundField(Sound sound) {
        for (java.lang.reflect.Field field : Sound.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !Sound.class.isAssignableFrom(field.getType())) continue;
            try {
                if (field.get(null) == sound) return field.getName();
            } catch (IllegalAccessException ignored) {
                // Public interface constants should always be accessible.
            }
        }
        return "SOUND";
    }

    private static boolean current(Player player, ProfileHolder holder) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top == holder.getInventory() && top.getHolder() == holder;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private record SlotAction(int slot, String id, String value) { }

    private static final class ProfileHolder implements InventoryHolder {
        private final UUID playerId;
        private final String crateId;
        private final Mode mode;
        private final View view;
        private final String profileId;
        private final int page;
        private final List<SlotAction> actions = new ArrayList<>();
        private Inventory inventory;

        private ProfileHolder(UUID playerId, String crateId, Mode mode, View view, String profileId, int page) {
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
            if (inventory == null) throw new IllegalStateException("Profile editor inventory is not attached");
            return inventory;
        }
    }
}

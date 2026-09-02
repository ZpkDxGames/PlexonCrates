package com.antondev.crates.gui;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.config.ItemCodec;
import com.antondev.crates.config.Text;
import com.antondev.crates.domain.reward.RewardRarity;
import com.antondev.crates.domain.reward.RewardLimits;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.model.CrateReward;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/** Owns safe chat inputs and transient item-builder state. Persistent crate drafts remain YAML/SQLite backed. */
public final class EditSessionService implements Listener {
    private final PlexonCrates plugin;
    private final Map<UUID, TextInput> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, KeyDraft> keys = new HashMap<>();
    private final Map<UUID, RewardDraft> rewards = new HashMap<>();
    private final BukkitTask cleanup;

    public EditSessionService(PlexonCrates plugin) {
        this.plugin = plugin;
        cleanup = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 100L, 100L);
    }

    public void request(Player player, Component prompt, InputHandler handler) {
        long expires = System.currentTimeMillis() + plugin.settings().inputTimeoutSeconds() * 1000L;
        inputs.put(player.getUniqueId(), new TextInput(handler, expires));
        player.closeInventory();
        player.sendMessage(prompt);
        player.sendMessage(Text.parse("<dark_gray>Type <white>/cancel</white> to stop. Input expires in "
                + plugin.settings().inputTimeoutSeconds() + " seconds.</dark_gray>"));
    }

    public KeyDraft beginKey(Player player, String id) {
        KeyDraft draft = new KeyDraft(id, Text.parse("<white><bold>" + pretty(id) + " Key</bold></white>"));
        keys.put(player.getUniqueId(), draft);
        return draft;
    }

    public KeyDraft beginKeyRotation(Player player, KeyDefinition definition, ItemStack previous) {
        KeyDraft draft = new KeyDraft(definition.id(), definition.displayName());
        draft.previous = ItemCodec.one(previous);
        draft.keepPreviousAsLegacy = true;
        keys.put(player.getUniqueId(), draft);
        return draft;
    }

    public KeyDraft key(Player player) { return keys.get(player.getUniqueId()); }
    public void clearKey(Player player) { keys.remove(player.getUniqueId()); }

    public RewardDraft beginReward(Player player, String crateId, String id) {
        RewardDraft draft = new RewardDraft(crateId, id,
                Text.parse("<white><bold>" + pretty(id) + "</bold></white>"));
        rewards.put(player.getUniqueId(), draft);
        return draft;
    }

    public RewardDraft beginReward(Player player, String crateId, CrateReward source, int orderIndex) {
        RewardDraft draft = new RewardDraft(crateId, source.id(), source.displayName());
        draft.editing = true;
        draft.enabled = source.enabled();
        draft.displayItem = source.displayCopy();
        source.itemCopies().forEach(item -> draft.items.add(item.clone()));
        draft.commands.addAll(source.commands());
        draft.weight = source.weight();
        draft.rarity = source.rarity();
        draft.experiencePoints = source.experiencePoints();
        draft.experienceLevels = source.experienceLevels();
        draft.money = source.money();
        draft.requiredPermission = source.requiredPermission();
        draft.blockedPermission = source.blockedPermission();
        draft.limits = source.limits();
        draft.presentation = source.presentation();
        draft.personalMessage = source.personalMessage();
        draft.broadcast = source.broadcast();
        draft.originalOrderIndex = orderIndex;
        draft.orderIndex = orderIndex;
        rewards.put(player.getUniqueId(), draft);
        return draft;
    }

    public RewardDraft reward(Player player) { return rewards.get(player.getUniqueId()); }
    public void clearReward(Player player) { rewards.remove(player.getUniqueId()); }

    @EventHandler
    public void chat(AsyncChatEvent event) {
        TextInput input = inputs.remove(event.getPlayer().getUniqueId());
        if (input == null) return;
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> complete(event.getPlayer(), input, value));
    }

    @EventHandler
    public void command(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/cancel")) return;
        if (inputs.remove(event.getPlayer().getUniqueId()) == null) return;
        event.setCancelled(true);
        plugin.messages().send(event.getPlayer(), "input-cancelled");
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        inputs.remove(event.getPlayer().getUniqueId());
        keys.remove(event.getPlayer().getUniqueId());
        rewards.remove(event.getPlayer().getUniqueId());
    }

    public void stop() {
        cleanup.cancel();
        inputs.clear();
        keys.clear();
        rewards.clear();
    }

    private void complete(Player player, TextInput input, String value) {
        if (!player.isOnline()) return;
        if (System.currentTimeMillis() > input.expiresAt()) {
            plugin.messages().send(player, "input-timeout");
            return;
        }
        try {
            if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Input cannot be empty or contain control characters");
            }
            input.handler().accept(player, value);
        } catch (Exception error) {
            plugin.messages().send(player, "input-invalid", Text.value("error", concise(error)));
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, TextInput> entry : List.copyOf(inputs.entrySet())) {
            if (entry.getValue().expiresAt() >= now || !inputs.remove(entry.getKey(), entry.getValue())) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) plugin.messages().send(player, "input-timeout");
        }
        long sessionExpiry = now - plugin.settings().sessionTimeoutMinutes() * 60_000L;
        keys.entrySet().removeIf(entry -> entry.getValue().updatedAt < sessionExpiry);
        rewards.entrySet().removeIf(entry -> entry.getValue().updatedAt < sessionExpiry);
    }

    private static String concise(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String pretty(String id) {
        StringBuilder result = new StringBuilder();
        for (String word : id.split("[_-]")) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @FunctionalInterface
    public interface InputHandler { void accept(Player player, String value) throws Exception; }
    private record TextInput(InputHandler handler, long expiresAt) {}

    public static final class KeyDraft {
        private final String id;
        private Component displayName;
        private ItemStack template;
        private ItemStack previous;
        private boolean keepPreviousAsLegacy;
        private long updatedAt = System.currentTimeMillis();

        private KeyDraft(String id, Component displayName) {
            this.id = id;
            this.displayName = displayName;
        }
        public String id() { return id; }
        public Component displayName() { return displayName; }
        public void displayName(Component value) { displayName = value; touch(); }
        public ItemStack template() { return template == null ? null : template.clone(); }
        public void template(ItemStack value) { template = ItemCodec.one(value); touch(); }
        public ItemStack previous() { return previous == null ? null : previous.clone(); }
        public boolean rotation() { return previous != null; }
        public boolean keepPreviousAsLegacy() { return keepPreviousAsLegacy; }
        public void toggleLegacy() { keepPreviousAsLegacy = !keepPreviousAsLegacy; touch(); }
        private void touch() { updatedAt = System.currentTimeMillis(); }
    }

    public static final class RewardDraft {
        private final String crateId;
        private final String id;
        private Component displayName;
        private ItemStack displayItem;
        private boolean editing;
        private boolean enabled = true;
        private double weight = 10.0;
        private RewardRarity rarity = RewardRarity.COMMON;
        private final List<ItemStack> items = new ArrayList<>();
        private final List<String> commands = new ArrayList<>();
        private int experiencePoints;
        private int experienceLevels;
        private double money;
        private String requiredPermission = "";
        private String blockedPermission = "";
        private RewardLimits limits = RewardLimits.unlimited();
        private RewardPresentation presentation = RewardPresentation.none();
        private String personalMessage = "";
        private String broadcast = "";
        private int originalOrderIndex = -1;
        private int orderIndex = -1;
        private long updatedAt = System.currentTimeMillis();

        private RewardDraft(String crateId, String id, Component displayName) {
            this.crateId = crateId;
            this.id = id;
            this.displayName = displayName;
        }
        public String crateId() { return crateId; }
        public String id() { return id; }
        public Component displayName() { return displayName; }
        public void displayName(Component value) { displayName = value; touch(); }
        public ItemStack displayItem() { return displayItem == null ? null : displayItem.clone(); }
        public boolean editing() { return editing; }
        public boolean enabled() { return enabled; }
        public void toggleEnabled() { enabled = !enabled; touch(); }
        public double weight() { return weight; }
        public void weight(double value) {
            if (!Double.isFinite(value) || value <= 0 || value > 1_000_000_000) throw new IllegalArgumentException("Weight must be positive and finite");
            weight = value; touch();
        }
        public RewardRarity rarity() { return rarity; }
        public void rarity(RewardRarity value) { rarity = value; touch(); }
        public List<ItemStack> items() { return items.stream().map(ItemStack::clone).toList(); }
        public void addItem(ItemStack value) { items.add(value.clone()); touch(); }
        public void clearItems() { items.clear(); touch(); }
        public List<String> commands() { return List.copyOf(commands); }
        public void addCommand(String value) {
            if (value.isBlank() || value.startsWith("/") || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("Command must be one line and omit the leading /");
            }
            commands.add(value); touch();
        }
        public void removeCommand(int oneBasedIndex) {
            int index = oneBasedIndex - 1;
            if (index < 0 || index >= commands.size()) throw new IllegalArgumentException("Unknown command number");
            commands.remove(index);
            touch();
        }
        public void editCommand(int oneBasedIndex, String value) {
            validateCommand(value);
            int index = oneBasedIndex - 1;
            if (index < 0 || index >= commands.size()) throw new IllegalArgumentException("Unknown command number");
            commands.set(index, value);
            touch();
        }
        public void moveCommand(int oneBasedFrom, int oneBasedTo) {
            int from = oneBasedFrom - 1;
            int to = oneBasedTo - 1;
            if (from < 0 || from >= commands.size() || to < 0 || to >= commands.size()) {
                throw new IllegalArgumentException("Command positions are outside the current list");
            }
            String command = commands.remove(from);
            commands.add(to, command);
            touch();
        }
        public int experiencePoints() { return experiencePoints; }
        public void experiencePoints(int value) { if (value < 0) throw new IllegalArgumentException("XP cannot be negative"); experiencePoints = value; touch(); }
        public int experienceLevels() { return experienceLevels; }
        public void experienceLevels(int value) { if (value < 0) throw new IllegalArgumentException("Levels cannot be negative"); experienceLevels = value; touch(); }
        public double money() { return money; }
        public void money(double value) { if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Money cannot be negative"); money = value; touch(); }
        public String requiredPermission() { return requiredPermission; }
        public String blockedPermission() { return blockedPermission; }
        public void permissions(String required, String blocked) {
            requiredPermission = required == null ? "" : required.trim();
            blockedPermission = blocked == null ? "" : blocked.trim();
            touch();
        }
        public RewardLimits limits() { return limits; }
        public void limits(RewardLimits value) { limits = value; touch(); }
        public RewardPresentation presentation() { return presentation; }
        public void presentation(RewardPresentation value) {
            Text.parse(value.title());
            Text.parse(value.subtitle());
            presentation = value;
            touch();
        }
        public String personalMessage() { return personalMessage; }
        public String broadcast() { return broadcast; }
        public void messages(String personal, String serverBroadcast) {
            personalMessage = personal == null ? "" : personal.trim();
            broadcast = serverBroadcast == null ? "" : serverBroadcast.trim();
            Text.parse(personalMessage);
            Text.parse(broadcast);
            touch();
        }
        public int orderIndex() { return orderIndex; }
        public boolean orderChanged() { return editing && orderIndex != originalOrderIndex; }
        public void orderIndex(int zeroBasedIndex) {
            if (!editing || zeroBasedIndex < 0) throw new IllegalArgumentException("Only an existing reward can be reordered");
            orderIndex = zeroBasedIndex;
            touch();
        }
        public boolean deliverable() { return !items.isEmpty() || !commands.isEmpty() || experiencePoints > 0 || experienceLevels > 0 || money > 0; }
        private void touch() { updatedAt = System.currentTimeMillis(); }

        private static void validateCommand(String value) {
            if (value == null || value.isBlank() || value.startsWith("/")
                    || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("Command must be one line and omit the leading /");
            }
        }
    }
}

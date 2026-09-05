package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.antondev.crates.config.Text;
import com.antondev.crates.domain.reward.RewardPresentation;
import com.antondev.crates.gui.GuiSessionService;
import com.antondev.crates.gui.MenuHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class AdministrationIntegrationTest {
    private ServerMock server;
    private PlexonCrates plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("Survival_World");
        plugin = MockBukkit.load(PlexonCrates.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void exactDragCaptureIsNonDestructiveAndBlocksEditorItemsAndDistributions() {
        var player = server.addPlayer("Editor");
        player.setOp(true);
        ItemStack original = new ItemStack(Material.NETHER_STAR, 7);
        original.editMeta(meta -> {
            meta.displayName(Component.text("Custom exact key"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "custom_data"),
                    PersistentDataType.STRING, "preserve-me");
        });

        plugin.editSessions().beginKey(player, "dragged_key");
        plugin.adminMenus().openKeyTemplate(player);
        MenuHolder holder = (MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        int input = plugin.menusConfig().slot("key-template.input-placeholder");

        var spread = new LinkedHashMap<Integer, ItemStack>();
        spread.put(input, original.clone());
        spread.put(input + 1, original.clone());
        InventoryDragEvent distributed = new InventoryDragEvent(player.getOpenInventory(), null,
                original, false, spread);
        plugin.adminMenus().handleDrag(distributed, holder);
        assertTrue(distributed.isCancelled());
        assertNull(plugin.editSessions().key(player).template());

        ItemStack editorItem = player.getOpenInventory().getTopInventory().getItem(0).clone();
        InventoryDragEvent rejected = new InventoryDragEvent(player.getOpenInventory(), null,
                editorItem, false, java.util.Map.of(input, editorItem));
        plugin.adminMenus().handleDrag(rejected, holder);
        assertNull(plugin.editSessions().key(player).template());

        InventoryDragEvent accepted = new InventoryDragEvent(player.getOpenInventory(), null,
                original, false, java.util.Map.of(input, original.clone()));
        plugin.adminMenus().handleDrag(accepted, holder);

        ItemStack captured = plugin.editSessions().key(player).template();
        assertTrue(accepted.isCancelled());
        assertEquals(7, original.getAmount());
        assertEquals(1, captured.getAmount());
        ItemStack normalizedOriginal = original.clone();
        normalizedOriginal.setAmount(1);
        assertTrue(captured.isSimilar(normalizedOriginal));
    }

    @Test
    void rewardPoolMultiSlotDragCapturesExactlyOnceWithoutChangingTheSource() {
        var player = server.addPlayer("PoolDragEditor");
        player.setOp(true);
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.menus().openRewards(player, crate, 0);
        awaitDraft(player, crate.id());
        MenuHolder holder = (MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        List<Integer> slots = plugin.menusConfig().slots("reward-pool.reward-slots");
        ItemStack original = exactReward(Material.NETHER_STAR, 7);
        byte[] before = original.serializeAsBytes();

        var targets = new LinkedHashMap<Integer, ItemStack>();
        targets.put(slots.getFirst(), original.clone()); // occupied
        targets.put(slots.get(10), original.clone()); // empty
        targets.put(slots.get(11), original.clone()); // empty
        InventoryDragEvent drag = new InventoryDragEvent(player.getOpenInventory(), null,
                original, false, targets);
        plugin.menus().drag(drag);

        var updated = plugin.crates().find("basic").orElseThrow();
        var captured = updated.rewards().values().stream()
                .filter(reward -> reward.id().startsWith("nether_star_")).toList();
        assertTrue(drag.isCancelled());
        assertEquals(1, captured.size());
        assertEquals(7, captured.getFirst().itemCopies().getFirst().getAmount());
        assertTrue(captured.getFirst().itemCopies().getFirst().isSimilar(original));
        assertEquals(10_000, updated.rewards().values().stream()
                .filter(reward -> reward.enabled()).mapToInt(reward -> reward.chanceBasisPoints()).sum());
        assertEquals(7, original.getAmount());
        assertArrayEquals(before, original.serializeAsBytes());
        assertEquals(MenuHolder.Kind.REWARDS,
                ((MenuHolder) player.getOpenInventory().getTopInventory().getHolder()).kind());
    }

    @Test
    void rewardPoolCursorAndShiftCaptureAreCopyOnly() {
        var player = server.addPlayer("PoolClickEditor");
        player.setOp(true);
        var crate = plugin.crates().find("basic").orElseThrow();
        List<Integer> slots = plugin.menusConfig().slots("reward-pool.reward-slots");

        ItemStack cursorSource = exactReward(Material.DIAMOND_SWORD, 1);
        byte[] cursorBefore = cursorSource.serializeAsBytes();
        plugin.menus().openRewards(player, crate, 0);
        awaitDraft(player, crate.id());
        player.setItemOnCursor(cursorSource);
        InventoryClickEvent cursorClick = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slots.get(8), ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
        plugin.menus().click(cursorClick);
        assertTrue(cursorClick.isCancelled());
        assertArrayEquals(cursorBefore, cursorSource.serializeAsBytes());
        assertEquals(9, plugin.crates().find("basic").orElseThrow().rewards().size());

        ItemStack shiftSource = exactReward(Material.EMERALD, 13);
        byte[] shiftBefore = shiftSource.serializeAsBytes();
        player.getInventory().setItem(0, shiftSource);
        InventoryClickEvent shiftClick = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.QUICKBAR, player.getOpenInventory().getTopInventory().getSize() + 27,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        plugin.menus().click(shiftClick);
        assertTrue(shiftClick.isCancelled());
        assertArrayEquals(shiftBefore, shiftSource.serializeAsBytes());
        assertEquals(13, player.getInventory().getItem(0).getAmount());
        assertEquals(10, plugin.crates().find("basic").orElseThrow().rewards().size());
    }

    @Test
    void existingRewardCanBeFullyEditedAndReorderedThroughTheBuilder() throws Exception {
        var player = server.addPlayer("RewardEditor");
        player.setOp(true);
        var crate = plugin.crates().find("basic").orElseThrow();
        var original = crate.orderedRewards().getFirst();
        plugin.adminMenus().ensureDraft(player, crate.id());
        awaitDraft(player, crate.id());
        plugin.adminMenus().editReward(player, crate, original);
        var draft = plugin.editSessions().reward(player);
        draft.baseChancePercent(42.5);
        draft.toggleEnabled();
        draft.addCommand("say reward-editor-test");
        draft.presentation(new RewardPresentation("<gold>Winner</gold>", "<gray>Well done</gray>",
                "minecraft:entity.player.levelup", 0.75f, 1.25f, true));
        draft.orderIndex(crate.rewards().size() - 1);

        int confirm = plugin.menusConfig().slot("reward-builder.confirm");
        player.simulateInventoryClick(player.getOpenInventory(), ClickType.LEFT, confirm);

        var updatedCrate = plugin.crates().find("basic").orElseThrow();
        var updated = updatedCrate.rewards().get(original.id());
        assertFalse(updated.enabled());
        assertEquals(0.0, updated.baseChancePercent());
        assertTrue(updated.commands().contains("say reward-editor-test"));
        assertEquals("minecraft:entity.player.levelup", updated.presentation().sound());
        assertTrue(updated.presentation().firework());
        assertEquals(original.id(), updatedCrate.orderedRewards().getLast().id());
        assertEquals(original.itemCopies().size(), updated.itemCopies().size());
    }

    @Test
    void supersededInventorySessionCannotRouteItsServerSideActions() {
        var player = server.addPlayer("SessionEditor");
        player.setOp(true);

        plugin.adminMenus().openDashboard(player);
        MenuHolder stale = (MenuHolder) player.getOpenInventory().getTopInventory().getHolder();
        plugin.adminMenus().openDashboard(player);
        MenuHolder current = (MenuHolder) player.getOpenInventory().getTopInventory().getHolder();

        assertNotEquals(stale.sessionId(), current.sessionId());
        assertEquals(current.sessionId(), plugin.guiSessions().activeSession(player.getUniqueId()).orElseThrow());
        assertEquals(GuiSessionService.Validation.SUPERSEDED_SESSION,
                plugin.guiSessions().validate(player, stale, plugin.draftSessions()));

        int crates = plugin.menusConfig().slot("admin.crates");
        InventoryClickEvent delayed = new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, crates, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        plugin.adminMenus().handleClick(delayed, stale);

        assertTrue(delayed.isCancelled());
        assertSame(current, player.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void matchingLeaseCanAdvanceButTakeoverLeavesThePreviousViewStale() throws Exception {
        var first = server.addPlayer("LeaseOwner");
        var second = server.addPlayer("LeaseTaker");
        first.setOp(true);
        second.setOp(true);
        var crate = plugin.crates().find("basic").orElseThrow();

        plugin.adminMenus().openCrateEditor(first, crate);
        awaitDraft(first, crate.id());
        MenuHolder firstHolder = (MenuHolder) first.getOpenInventory().getTopInventory().getHolder();
        long before = firstHolder.revision();
        plugin.crates().setDescription(crate.id(), List.of(Component.text("Revision advance")), first.getName());
        plugin.adminMenus().saveDraftRevision(first, crate.id(), "IDENTITY", "Changed description");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        var saved = plugin.draftSessions().view(first.getUniqueId(), crate.id()).orElseThrow();
        assertTrue(saved.revision() > before);
        assertEquals(saved.revision(), firstHolder.revision());
        assertEquals(GuiSessionService.Validation.CURRENT,
                plugin.guiSessions().validate(first, firstHolder, plugin.draftSessions()));

        plugin.adminMenus().openCrateEditor(second, plugin.crates().find(crate.id()).orElseThrow());
        awaitDraft(second, crate.id());
        second.simulateInventoryClick(second.getOpenInventory(), ClickType.LEFT,
                plugin.menusConfig().slot("editor.takeover"));
        second.simulateInventoryClick(second.getOpenInventory(), ClickType.LEFT,
                plugin.menusConfig().slot("confirm-takeover.confirm"));
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        var displaced = plugin.draftSessions().view(first.getUniqueId(), crate.id()).orElseThrow();
        assertFalse(displaced.writable());
        assertNotEquals(displaced.leaseToken(), firstHolder.leaseToken());
        assertEquals(GuiSessionService.Validation.STALE_DRAFT,
                plugin.guiSessions().validate(first, firstHolder, plugin.draftSessions()));

        int back = plugin.menusConfig().slot("editor.back");
        InventoryClickEvent staleBack = new InventoryClickEvent(first.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, back, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        plugin.adminMenus().handleClick(staleBack, firstHolder);
        assertTrue(staleBack.isCancelled());
        assertSame(firstHolder, first.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void secondAdministratorIsReadOnlyUntilConfirmedTakeover() {
        var first = server.addPlayer("FirstEditor");
        var second = server.addPlayer("SecondEditor");
        first.setOp(true);
        second.setOp(true);
        var crate = plugin.crates().find("basic").orElseThrow();

        plugin.adminMenus().openCrateEditor(first, crate);
        awaitDraft(first, crate.id());
        plugin.adminMenus().openCrateEditor(second, crate);
        awaitDraft(second, crate.id());
        assertTrue(plugin.draftSessions().view(first.getUniqueId(), crate.id()).orElseThrow().writable());
        assertFalse(plugin.draftSessions().view(second.getUniqueId(), crate.id()).orElseThrow().writable());

        int before = crate.rewards().size();
        plugin.menus().openRewards(second, crate, 0);
        List<Integer> slots = plugin.menusConfig().slots("reward-pool.reward-slots");
        ItemStack source = exactReward(Material.DIAMOND, 3);
        second.setItemOnCursor(source);
        InventoryClickEvent rejected = new InventoryClickEvent(second.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slots.get(8), ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR);
        plugin.menus().click(rejected);
        assertEquals(before, plugin.crates().find(crate.id()).orElseThrow().rewards().size());

        plugin.adminMenus().openCrateEditor(second, crate);
        second.simulateInventoryClick(second.getOpenInventory(), ClickType.LEFT,
                plugin.menusConfig().slot("editor.takeover"));
        assertEquals(MenuHolder.Kind.CONFIRM_TAKEOVER,
                ((MenuHolder) second.getOpenInventory().getTopInventory().getHolder()).kind());
        second.simulateInventoryClick(second.getOpenInventory(), ClickType.LEFT,
                plugin.menusConfig().slot("confirm-takeover.confirm"));
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        assertTrue(plugin.draftSessions().view(second.getUniqueId(), crate.id()).orElseThrow().writable());
        assertFalse(plugin.draftSessions().view(first.getUniqueId(), crate.id()).orElseThrow().writable());
    }

    @Test
    void draftEditsStayOutOfPlayerRuntimeUntilAtomicPublication() throws Exception {
        var editor = server.addPlayer("Publisher");
        editor.setOp(true);
        var activeBefore = plugin.runtime().find("basic").orElseThrow();
        long runtimeBefore = plugin.runtime().snapshot().revision();
        Component changedName = Component.text("Unpublished draft name");

        plugin.adminMenus().ensureDraft(editor, "basic");
        awaitDraft(editor, "basic");
        plugin.crates().setDisplayName("basic", changedName, editor.getName());
        plugin.adminMenus().saveDraftRevision(editor, "basic", "IDENTITY", "Changed display name");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        assertEquals(changedName, plugin.crates().find("basic").orElseThrow().displayName());
        assertEquals(activeBefore.displayName(), plugin.runtime().find("basic").orElseThrow().displayName());

        var future = plugin.definitionPublisher().publish(editor.getUniqueId(), editor.getName(), "basic");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        var publication = future.join();

        assertEquals(changedName, plugin.runtime().find("basic").orElseThrow().displayName());
        assertNotEquals(runtimeBefore, plugin.runtime().snapshot().revision());
        assertEquals(2, publication.crateRevision());
        assertTrue(publication.yamlMirrorUpdated());
        assertTrue(plugin.draftSessions().view(editor.getUniqueId(), "basic").isEmpty());
        var counts = plugin.definitionRepository().counts("basic").join();
        assertEquals(8, counts.rewards());
        assertTrue(counts.items() > 0);
        assertTrue(counts.actions() >= counts.items());
        assertEquals(1, counts.keyLinks());
    }

    @Test
    void disablingAndReenablingPublishesTheLifecycleWithoutLeakingIntoRuntime() throws Exception {
        var editor = server.addPlayer("LifecycleEditor");
        editor.setOp(true);
        var before = plugin.runtime().find("basic").orElseThrow();
        long initialRevision = plugin.definitionRevision("basic");

        plugin.adminMenus().ensureDraft(editor, "basic");
        awaitDraft(editor, "basic");
        plugin.crates().setState("basic", com.antondev.crates.domain.crate.CrateState.DISABLED, editor.getName());
        plugin.adminMenus().saveDraftRevision(editor, "basic", "STATE", "Disabled crate");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        var disabled = plugin.definitionPublisher().publish(editor.getUniqueId(), editor.getName(), "basic");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        assertEquals(com.antondev.crates.domain.crate.CrateState.DISABLED, disabled.join().crate().state());
        assertTrue(plugin.runtime().find("basic").isEmpty());
        assertEquals(initialRevision + 1, plugin.definitionRevision("basic"));
        assertEquals("DISABLED", plugin.definitionRepository().loadPublished().join().definitions().stream()
                .filter(definition -> definition.crateId().equals("basic")).findFirst().orElseThrow().lifecycle());

        plugin.adminMenus().ensureDraft(editor, "basic");
        awaitDraft(editor, "basic");
        plugin.crates().setState("basic", com.antondev.crates.domain.crate.CrateState.PUBLISHED, editor.getName());
        plugin.adminMenus().saveDraftRevision(editor, "basic", "STATE", "Re-enabled crate");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        var reenabled = plugin.definitionPublisher().publish(editor.getUniqueId(), editor.getName(), "basic");
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);

        assertEquals(com.antondev.crates.domain.crate.CrateState.PUBLISHED, reenabled.join().crate().state());
        // Adventure's gradient component implementation may not compare equal after a
        // YAML round-trip even when its serialized MiniMessage is unchanged. Compare
        // the stable presentation form so this lifecycle test focuses on the runtime
        // not leaking a disabled definition into the player-facing registry.
        assertEquals(Text.serialize(before.displayName()),
                Text.serialize(plugin.runtime().find("basic").orElseThrow().displayName()));
        assertEquals(initialRevision + 2, plugin.definitionRevision("basic"));
    }

    @Test
    void malformedYamlMirrorCannotOverrideTheCanonicalPublishedRuntime() throws Exception {
        var player = server.addPlayer("ReloadEditor");
        player.setOp(true);
        double originalChance = plugin.crates().find("basic").orElseThrow().rewards().get("coal_cache").baseChancePercent();
        Path file = plugin.getDataFolder().toPath().resolve("crates/basic.yml");
        String valid = Files.readString(file);
        String invalid = valid.replaceFirst("chance-basis-points: 2800", "chance-basis-points: 0");
        assertFalse(valid.equals(invalid));
        Files.writeString(file, invalid);
        try {
            assertTrue(plugin.reloadFor(player));
            assertEquals(originalChance,
                    plugin.crates().find("basic").orElseThrow().rewards().get("coal_cache").baseChancePercent());
            assertTrue(plugin.crates().find("basic").orElseThrow().enabled());
            assertEquals(invalid, Files.readString(file));
            Files.delete(file);
            assertTrue(plugin.reloadFor(player));
            assertEquals(originalChance,
                    plugin.crates().find("basic").orElseThrow().rewards().get("coal_cache").baseChancePercent());
            assertTrue(plugin.crates().serialized("basic").contains("coal_cache"));
        } finally {
            Files.writeString(file, valid);
        }
    }

    @Test
    void durableDraftPayloadRestoresWhenItsYamlMirrorIsMissing() throws Exception {
        var player = server.addPlayer("DraftRestartEditor");
        player.setOp(true);
        var activeName = plugin.runtime().find("basic").orElseThrow().displayName();
        var draftName = Component.text("Durable restart draft");
        Path file = plugin.getDataFolder().toPath().resolve("crates/basic.yml");
        String valid = Files.readString(file);
        try {
            plugin.adminMenus().ensureDraft(player, "basic");
            awaitDraft(player, "basic");
            plugin.crates().setDisplayName("basic", draftName, player.getName());
            plugin.adminMenus().saveDraftRevision(player, "basic", "IDENTITY", "Changed display name");
            plugin.database().awaitIdle().join();
            server.getScheduler().performTicks(2);

            Files.delete(file);
            assertTrue(plugin.reloadFor(player));

            assertEquals(draftName, plugin.crates().find("basic").orElseThrow().displayName());
            assertEquals(activeName, plugin.runtime().find("basic").orElseThrow().displayName());
            assertTrue(Files.notExists(file));
            assertTrue(plugin.crates().serialized("basic").contains("Durable restart draft"));
        } finally {
            Files.writeString(file, valid);
        }
    }

    @Test
    void wandLinkPersistsAndProtectsTheBlockFromWorldMutations() throws Exception {
        var player = server.addPlayer("Builder");
        player.setOp(true);
        var configFile = new java.io.File(plugin.getDataFolder(), "config.yml");
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
        config.set("holograms.enabled", false); // MockBukkit does not implement TextDisplay spawning.
        config.save(configFile);
        assertTrue(plugin.reloadFor(player));
        var world = server.getWorld("Survival_World");
        world.getChunkAt(0, 0).load();
        var linked = world.getBlockAt(8, 70, 8);
        linked.setType(Material.CHEST);

        assertTrue(plugin.wand().link(player, linked, plugin.crates().find("basic").orElseThrow()));
        plugin.database().awaitIdle().join();

        assertEquals(1, plugin.locations().count("basic"));
        assertEquals(1, plugin.database().loadLocations().size());
        plugin.displays().refresh();

        player.setOp(false);
        BlockBreakEvent breaking = new BlockBreakEvent(linked, player);
        server.getPluginManager().callEvent(breaking);
        assertTrue(breaking.isCancelled());

        var other = world.getBlockAt(9, 70, 8);
        other.setType(Material.STONE);
        var affected = new java.util.ArrayList<>(List.of(linked, other));
        BlockExplodeEvent explosion = new BlockExplodeEvent(other, other.getState(), affected, 1.0f,
                ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(explosion);
        assertFalse(affected.contains(linked));
        assertTrue(affected.contains(other));

        var piston = world.getBlockAt(7, 70, 8);
        piston.setType(Material.PISTON);
        BlockPistonExtendEvent extend = new BlockPistonExtendEvent(piston, List.of(linked), BlockFace.EAST);
        server.getPluginManager().callEvent(extend);
        assertTrue(extend.isCancelled());
    }

    private ItemStack exactReward(Material material, int amount) {
        ItemStack item = new ItemStack(material, amount);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Exact " + material));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "reward_capture_" + material.name().toLowerCase()),
                    PersistentDataType.STRING, "preserve-me");
        });
        return item;
    }

    private void awaitDraft(Player player, String crateId) {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        assertTrue(plugin.draftSessions().view(player.getUniqueId(), crateId).isPresent());
    }
}

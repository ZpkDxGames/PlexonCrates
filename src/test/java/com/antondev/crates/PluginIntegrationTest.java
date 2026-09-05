package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.crate.CrateState;
import java.nio.file.Files;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PluginIntegrationTest {
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
    void bundledConfigurationLoadsFourReadyCrates() {
        assertEquals(List.of("basic", "rare", "epic", "legendary"),
                plugin.crates().ordered().stream().map(crate -> crate.id()).toList());
        assertEquals(32, plugin.crates().rewardCount());
        assertEquals("exact cached/configured templates", plugin.keys().sourceLabel());
        for (var crate : plugin.crates().ordered()) {
            assertTrue(crate.enabled());
            assertEquals(10_000, crate.rewards().values().stream().mapToInt(reward -> reward.chanceBasisPoints()).sum());
            assertTrue(plugin.keys().template(crate.keyId()).isPresent());
        }
        var shulkers = plugin.crates().find("epic").orElseThrow().rewards().get("shulker_pack").itemCopies();
        assertEquals(4, shulkers.stream().mapToInt(item -> item.getAmount()).sum());
        assertTrue(shulkers.stream().allMatch(item -> item.getAmount() <= item.getMaxStackSize()));
    }

    @Test
    void successfulOpenConsumesOneFallbackKeyBeforeAnimation() {
        var player = server.addPlayer("Tonim");
        player.setOp(false);
        World world = server.getWorld("Survival_World");
        assertTrue(world != null);
        player.teleport(world.getSpawnLocation());
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 3);
        assertEquals(3, plugin.keys().count(player, crate.keyId()));

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitOpeningCommit();

        assertEquals(2, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertFalse(plugin.openings().open(player, crate, 2, false));
        assertEquals(2, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
    }

    @Test
    void capturedRewardRoundTripsCompleteItemData() throws Exception {
        ItemStack original = new ItemStack(Material.DIAMOND, 7);
        original.editMeta(meta -> {
            meta.displayName(Component.text("External custom reward"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "capture_test"),
                    PersistentDataType.STRING, "preserved");
        });

        plugin.crates().addCapturedReward("basic", "external_reward", 10, original);

        var stored = plugin.crates().find("basic").orElseThrow().rewards().get("external_reward").itemCopies();
        assertEquals(1, stored.size());
        assertEquals(7, stored.getFirst().getAmount());
        assertTrue(stored.getFirst().isSimilar(original));
        var updated = plugin.crates().find("basic").orElseThrow();
        assertEquals(1_000, updated.rewards().get("external_reward").chanceBasisPoints());
        assertEquals(10_000, updated.rewards().values().stream()
                .filter(reward -> reward.enabled()).mapToInt(reward -> reward.chanceBasisPoints()).sum());
        String serialized = plugin.crates().serialized("basic");
        assertTrue(serialized.contains("chance-basis-points:"));
        assertFalse(serialized.contains("weight:"));
    }

    @Test
    void firstRewardAndExactChanceEditsMaintainCompleteTicketPools() throws Exception {
        plugin.crates().createDraft("chance_test", "TEST");
        plugin.crates().addCapturedReward("chance_test", "first", 1.0, new ItemStack(Material.STONE));
        assertEquals(10_000, plugin.crates().find("chance_test").orElseThrow()
                .rewards().get("first").chanceBasisPoints());

        plugin.crates().addCapturedReward("chance_test", "second", 10.0, new ItemStack(Material.DIAMOND));
        plugin.crates().setChanceBasisPoints("chance_test", "second", 1);

        var rewards = plugin.crates().find("chance_test").orElseThrow().rewards();
        assertEquals(9_999, rewards.get("first").chanceBasisPoints());
        assertEquals(1, rewards.get("second").chanceBasisPoints());
        assertEquals(10_000, rewards.values().stream().mapToInt(reward -> reward.chanceBasisPoints()).sum());
    }

    @Test
    void chanceBalancePresetsRemainExact() throws Exception {
        plugin.crates().balanceChances("basic",
                com.antondev.crates.service.CrateRegistry.ChanceBalanceMode.EQUAL, "TEST");
        var equal = plugin.crates().find("basic").orElseThrow().orderedRewards();
        assertEquals(List.of(1_250, 1_250, 1_250, 1_250, 1_250, 1_250, 1_250, 1_250),
                equal.stream().map(reward -> reward.chanceBasisPoints()).toList());

        plugin.crates().balanceChances("basic",
                com.antondev.crates.service.CrateRegistry.ChanceBalanceMode.RARITY_CURVE, "TEST");
        var curved = plugin.crates().find("basic").orElseThrow().orderedRewards();
        assertEquals(10_000, curved.stream().mapToInt(reward -> reward.chanceBasisPoints()).sum());
        assertTrue(curved.stream().allMatch(reward -> reward.chanceBasisPoints() == 1_250));
    }

    @Test
    void portableItemsAuthenticateConsumeOnceAndRejectDuplicatedReplay() {
        var player = server.addPlayer("PortableUser");
        player.setOp(true);
        World world = server.getWorld("Survival_World");
        assertTrue(world != null);
        player.teleport(world.getSpawnLocation());
        var crate = plugin.runtime().find("basic").orElseThrow();

        ItemStack issued = plugin.portables().issue(
                crate, com.antondev.crates.service.PortableCrateCodec.RevisionPolicy.LATEST_PUBLISHED,
                0, player.getUniqueId(), null).join();
        assertTrue(plugin.portables().isPortable(issued));
        var issue = plugin.portables().verify(issued).join().orElseThrow();
        assertEquals("UNUSED", issue.state());
        assertEquals(player.getUniqueId(), issue.issuedTo());

        NamespacedKey tokenKey = new NamespacedKey(plugin, "portable_crate_token");
        ItemStack tampered = issued.clone();
        tampered.editMeta(meta -> {
            String token = meta.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
            assertTrue(token != null);
            int changed = token.indexOf('.') + 4;
            char replacement = token.charAt(changed) == 'A' ? 'B' : 'A';
            meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING,
                    token.substring(0, changed) + replacement + token.substring(changed + 1));
        });
        assertTrue(plugin.portables().decode(tampered).isEmpty());

        issued.setAmount(2);
        player.getInventory().setItemInMainHand(issued);
        plugin.menus().openPortablePreview(player, crate, issue);
        assertTrue(player.getOpenInventory().getTopInventory().getHolder()
                instanceof com.antondev.crates.gui.MenuHolder holder
                && holder.kind() == com.antondev.crates.gui.MenuHolder.Kind.PORTABLE_PREVIEW);
        assertEquals("UNUSED", plugin.database().loadPortableIssue(issue.issueId())
                .join().orElseThrow().state());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());

        player.simulateInventoryClick(player.getOpenInventory(),
                org.bukkit.event.inventory.ClickType.LEFT, plugin.menusConfig().slot("preview.open"));
        awaitPortableCommit();

        assertEquals("CONSUMED", plugin.database().loadPortableIssue(issue.issueId())
                .join().orElseThrow().state());
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        ItemStack replay = player.getInventory().getItemInMainHand();
        assertTrue(plugin.portables().isPortable(replay));
        assertEquals(1, replay.getAmount());

        assertTrue(plugin.openings().openPortable(player, crate, issue, replay.clone()));
        awaitPortableCommit();

        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.portables().isPortable(player.getInventory().getItemInMainHand()));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test
    void ordinaryPreviewIsAccurateNonConsumingAndListsEveryAcceptedKey() {
        var player = server.addPlayer("PreviewUser");
        player.setOp(true);
        World world = server.getWorld("Survival_World");
        assertTrue(world != null);
        player.teleport(world.getSpawnLocation());
        var source = plugin.runtime().find("basic").orElseThrow();
        var crate = new com.antondev.crates.model.Crate(
                source.id(), source.state(), source.displayOrder(), source.displayName(), source.description(),
                source.icon(), source.permission(), source.worlds(), source.excludedWorlds(),
                List.of("basic", "rare"), source.keyCost(), source.cooldownSeconds(), source.bulkEnabled(),
                source.bulkMaximum(), source.animation(), source.hologramLines(), source.broadcast(),
                source.pity(), source.rewards());
        plugin.keys().give(player, "basic", 2);
        plugin.keys().give(player, "rare", 4);
        var firstReward = crate.orderedRewards().getFirst();
        var originalLore = firstReward.displayCopy().getItemMeta().lore();

        plugin.menus().openPreview(player, crate, 0, false);

        assertTrue(player.getOpenInventory().getTopInventory().getHolder()
                instanceof com.antondev.crates.gui.MenuHolder holder
                && holder.kind() == com.antondev.crates.gui.MenuHolder.Kind.PREVIEW);
        ItemStack rewardDisplay = player.getOpenInventory().getTopInventory()
                .getItem(plugin.menusConfig().slots("preview.reward-slots").getFirst());
        assertTrue(rewardDisplay != null);
        String rewardLore = plainLore(rewardDisplay);
        assertTrue(rewardLore.contains("Eligible chance » 28%"));
        assertTrue(rewardLore.contains("Base chance » 28%"));
        ItemStack open = player.getOpenInventory().getTopInventory()
                .getItem(plugin.menusConfig().slot("preview.open"));
        assertTrue(open != null);
        String paymentLore = plainLore(open).toLowerCase(java.util.Locale.ROOT);
        assertTrue(paymentLore.contains("basic"));
        assertTrue(paymentLore.contains("2 available"));
        assertTrue(paymentLore.contains("rare"));
        assertTrue(paymentLore.contains("4 available"));
        assertEquals(2, plugin.keys().count(player, "basic"));
        assertEquals(4, plugin.keys().count(player, "rare"));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertEquals(originalLore, firstReward.displayCopy().getItemMeta().lore());

        player.closeInventory();
        server.getScheduler().performTicks(2);
        assertEquals(2, plugin.keys().count(player, "basic"));
        assertEquals(4, plugin.keys().count(player, "rare"));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
    }

    @Test
    void crateExportImportsAsAValidatedDraftWithoutChangingTheSource() throws Exception {
        var exportDirectory = plugin.getDataFolder().toPath().resolve("exports");
        var exported = plugin.crates().exportDefinition("basic", exportDirectory);

        assertTrue(Files.isRegularFile(exported));
        var imported = plugin.crates().importAsDraft(exported, "basic_copy", "TEST");

        assertEquals("basic_copy", imported.id());
        assertEquals(CrateState.DRAFT, imported.state());
        assertEquals(8, imported.rewards().size());
        assertEquals(CrateState.PUBLISHED, plugin.crates().find("basic").orElseThrow().state());
        assertThrows(IllegalArgumentException.class,
                () -> plugin.crates().importAsDraft(exported, "../unsafe", "TEST"));
    }

    private void awaitOpeningCommit() {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
    }

    private void awaitPortableCommit() {
        for (int pass = 0; pass < 6; pass++) {
            plugin.database().awaitIdle().join();
            server.getScheduler().performTicks(2);
        }
    }

    private static String plainLore(ItemStack item) {
        var lore = item.getItemMeta().lore();
        if (lore == null) return "";
        return lore.stream().map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}

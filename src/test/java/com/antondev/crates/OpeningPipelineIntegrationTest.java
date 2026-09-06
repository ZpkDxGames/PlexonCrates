package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.api.event.CratePreOpenEvent;
import com.antondev.crates.api.event.PortableCrateUseEvent;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class OpeningPipelineIntegrationTest {
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
    void cancelledPreOpenConsumesNoKeyAndCreatesNoHistory() throws Exception {
        var player = server.addPlayer("Cancelled");
        player.setOp(false);
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 1);
        server.getPluginManager().registerEvents(new Canceller(), plugin);

        assertFalse(plugin.openings().open(player, crate, 1, false));

        assertEquals(1, plugin.keys().count(player, crate.keyId()));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
        assertFalse(plugin.openings().isOpening(player.getUniqueId()));
    }

    @Test
    void cancelledPortableUseConsumesNeitherIssuanceNorItem() {
        var player = server.addPlayer("PortableCancelled");
        player.setOp(true);
        var crate = plugin.runtime().find("basic").orElseThrow();
        ItemStack item = plugin.portables().issue(
                crate, com.antondev.crates.service.PortableCrateCodec.RevisionPolicy.LATEST_PUBLISHED,
                0, player.getUniqueId(), null).join();
        var issue = plugin.portables().verify(item).join().orElseThrow();
        player.getInventory().setItemInMainHand(item);
        server.getPluginManager().registerEvents(new PortableCanceller(), plugin);

        assertFalse(plugin.openings().openPortable(player, crate, issue, item.clone()));

        assertEquals("UNUSED", plugin.database().loadPortableIssue(issue.issueId())
                .join().orElseThrow().state());
        assertTrue(plugin.portables().isPortable(player.getInventory().getItemInMainHand()));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertFalse(plugin.openings().isOpening(player.getUniqueId()));
    }

    @Test
    void fullInventoryFailureConsumesNoKey() throws Exception {
        plugin.crates().createDraft("capacity_test", "TEST");
        plugin.crates().setAcceptedKeys("capacity_test", List.of("basic"), 1, "TEST");
        plugin.crates().addCapturedReward("capacity_test", "diamond", 1, new ItemStack(Material.DIAMOND));
        plugin.crates().publish("capacity_test", plugin.keys(), "TEST");
        var crate = plugin.crates().find("capacity_test").orElseThrow();
        var player = server.addPlayer("FullInventory");
        player.setOp(false);

        ItemStack[] contents = new ItemStack[36];
        Arrays.setAll(contents, ignored -> new ItemStack(Material.COBBLESTONE, 64));
        contents[0] = plugin.keys().template("basic").orElseThrow();
        player.getInventory().setStorageContents(contents);
        setDropOverflow(false);

        assertFalse(plugin.openings().open(player, crate, 1, false));

        assertEquals(1, plugin.keys().count(player, "basic"));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
    }

    @Test
    void overlappingRequestsCommitExactlyOneOpening() throws Exception {
        var player = server.addPlayer("DoubleClick");
        player.setOp(false);
        var crate = plugin.crates().find("rare").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 5);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        assertFalse(plugin.openings().open(player, crate, 1, false));
        awaitOpeningCommit();

        assertEquals(4, plugin.keys().count(player, crate.keyId()));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
    }

    @Test
    void insufficientMassPaymentConsumesNothingInsteadOfSilentlyOpeningPartially() throws Exception {
        var player = server.addPlayer("PartialBatch");
        player.setOp(false);
        var crate = plugin.crates().find("basic").orElseThrow();
        plugin.keys().give(player, crate.keyId(), 1);

        assertFalse(plugin.openings().open(player, crate, 2, false));

        assertEquals(1, plugin.keys().count(player, crate.keyId()));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
    }

    @Test
    void selectiveBrowseCloseAndStaleChoiceConsumeNothingWhileConfirmationDeliversExactReward() throws Exception {
        plugin.crates().createDraft("selective_test", "TEST");
        plugin.crates().setAcceptedKeys("selective_test", List.of("basic"), 1, "TEST");
        plugin.crates().setOpening("selective_test", 0, true, 10,
                com.antondev.crates.domain.crate.AnimationType.INSTANT, "TEST");
        plugin.crates().setOpeningMode("selective_test",
                com.antondev.crates.domain.opening.OpeningMode.SELECTIVE, "TEST");
        plugin.crates().addCapturedReward("selective_test", "ordinary", 100,
                new ItemStack(Material.STONE), "TEST");
        plugin.crates().addBundleReward("selective_test", "chosen",
                net.kyori.adventure.text.Component.text("Chosen Diamond"), 10,
                com.antondev.crates.domain.reward.RewardRarity.EPIC,
                List.of(new ItemStack(Material.DIAMOND)), List.of(), 0, 0, 0,
                new com.antondev.crates.domain.reward.RewardLimits(1, 0, 0, 0, 0, 0, 0),
                "", "", "", "", "TEST");
        plugin.crates().publish("selective_test", plugin.keys(), "TEST");
        var published = plugin.crates().find("selective_test").orElseThrow();
        plugin.runtime().install(plugin.runtime().snapshot().revision() + 1, 1, published,
                plugin.crates().serialized("selective_test").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var crate = plugin.runtime().find("selective_test").orElseThrow();
        var player = server.addPlayer("SelectiveUser");
        player.setOp(false);
        plugin.keys().give(player, "basic", 2);

        assertFalse(plugin.openings().open(player, crate, 1, false));
        assertEquals(2, plugin.keys().count(player, "basic"));

        int rewardSlot = openSelectiveConfirmation(player, crate, "chosen");
        assertTrue(rewardSlot >= 0);
        assertEquals(2, plugin.keys().count(player, "basic"));
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
        player.closeInventory();
        server.getScheduler().performTicks(2);
        assertEquals(2, plugin.keys().count(player, "basic"));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());

        openSelectiveConfirmation(player, crate, "chosen");
        player.simulateInventoryClick(player.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT,
                plugin.menusConfig().slot("selective-confirm.confirm"));
        awaitOpeningCommit();

        assertEquals(1, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals("chosen", history.getFirst().rewardIds());
        assertTrue(Arrays.stream(player.getInventory().getStorageContents())
                .filter(java.util.Objects::nonNull).anyMatch(item -> item.getType() == Material.DIAMOND));

        assertFalse(plugin.openings().openSelected(player, crate, "chosen", 1,
                com.antondev.crates.domain.opening.OpenSource.GUI, null));
        assertEquals(1, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
    }

    @Test
    void massOpeningAtomicallyEarnsOneExactMilestoneClaim() throws Exception {
        plugin.crates().createDraft("milestone_test", "TEST");
        plugin.crates().setAcceptedKeys("milestone_test", List.of("basic"), 1, "TEST");
        plugin.crates().setOpening("milestone_test", 0, true, 64,
                com.antondev.crates.domain.crate.AnimationType.INSTANT, "TEST");
        plugin.crates().addCapturedReward("milestone_test", "diamond", 100,
                new ItemStack(Material.DIAMOND), "TEST");
        plugin.crates().setMilestone("milestone_test", "first_open", 1,
                com.antondev.crates.service.MilestoneService.RepeatPolicy.ONCE, 0,
                com.antondev.crates.service.MilestoneService.DeliveryPolicy.CLAIM, "diamond",
                net.kyori.adventure.text.Component.text("First Open"), new ItemStack(Material.CHEST), true, "TEST");
        plugin.crates().publish("milestone_test", plugin.keys(), "TEST");
        var crate = plugin.crates().find("milestone_test").orElseThrow();
        var player = server.addPlayer("MilestoneUser");
        player.setOp(false);
        plugin.keys().give(player, "basic", 2);

        assertTrue(plugin.openings().open(player, crate, 2, false));
        awaitVirtualOpeningCommit();

        var state = plugin.database().loadMilestoneState(player.getUniqueId(), crate.id()).join();
        assertEquals(2, state.openings());
        assertEquals(java.util.Set.of("first_open#0"),
                com.antondev.crates.service.MilestoneProgressService.decodeEarned(state.earnedPayload()));
        var claims = plugin.database().loadClaims(player.getUniqueId(), 10, 0).join();
        assertEquals(1, claims.size());
        assertEquals("MILESTONE", claims.getFirst().sourceType());
        assertEquals("diamond", claims.getFirst().rewardId());
        assertEquals(2, plugin.database().history(player.getUniqueId(), 10, 0).getFirst().openingCount());
    }

    @Test
    void virtualOnlyOpeningDebitsTheFrozenRevisionAndDeliversOnce() throws Exception {
        var crate = enableVirtualWallet("VIRTUAL_ONLY", false);
        var player = server.addPlayer("VirtualOnly");
        player.setOp(false);
        plugin.database().creditVirtualKeys(player.getUniqueId(), "basic", 2,
                "test-grant", "TEST", "virtual-only", null).join();

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitVirtualOpeningCommit();

        assertEquals(1, plugin.database().loadVirtualKeyBalance(player.getUniqueId(), "basic").join().balance());
        assertEquals(0, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
    }

    @Test
    void cancelledVirtualOpeningDebitsNothing() throws Exception {
        var crate = enableVirtualWallet("VIRTUAL_ONLY", false);
        var player = server.addPlayer("VirtualCancelled");
        player.setOp(false);
        plugin.database().creditVirtualKeys(player.getUniqueId(), "basic", 1,
                "test-cancel-grant", "TEST", "virtual-cancel", null).join();
        server.getPluginManager().registerEvents(new Canceller(), plugin);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitVirtualOpeningCommit();

        assertEquals(1, plugin.database().loadVirtualKeyBalance(player.getUniqueId(), "basic").join().balance());
        assertEquals(0, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());
    }

    @Test
    void keyBypassCannotAccidentallyMassOpen() throws Exception {
        var player = server.addPlayer("Operator");
        player.setOp(true);
        var crate = plugin.crates().find("epic").orElseThrow();

        assertTrue(plugin.openings().open(player, crate, 10, false));
        awaitOpeningCommit();

        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(1, history.getFirst().openingCount());
        assertEquals(0, history.getFirst().keyAmount());
    }

    @Test
    void tokenRerollConsumesOpeningKeyOnceChargesOnceAndDeliversOnlyReplacement() throws Exception {
        var crate = createRerollCrate("reroll_token", 15, true);
        var player = server.addPlayer("TokenReroll");
        player.setOp(false);
        plugin.keys().give(player, "basic", 1);
        plugin.database().creditRerolls(player.getUniqueId(), 1,
                "reroll-test-grant", "TEST", "reroll-token", null).join();

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitRerollDecision(player);
        var before = plugin.openings().rerollView(player).orElseThrow();
        String original = before.candidate().id();
        assertEquals(0, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.database().loadRerollBalance(player.getUniqueId()).join().balance());
        assertTrue(plugin.database().history(player.getUniqueId(), 10, 0).isEmpty());

        // MockBukkit cannot retain the custom reroll InventoryView. The production listener
        // delegates this control to the same journaled operation exercised here.
        plugin.openings().requestReroll(player);
        awaitRerollDecision(player);
        var after = plugin.openings().rerollView(player).orElseThrow();
        assertFalse(original.equals(after.candidate().id()));
        assertEquals(0, plugin.database().loadRerollBalance(player.getUniqueId()).join().balance());

        plugin.openings().acceptReroll(player, "ACCEPT");
        awaitVirtualOpeningCommit();

        assertEquals(0, plugin.keys().count(player, "basic"));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(after.candidate().id(), history.getFirst().rewardIds());
        assertTrue(history.getFirst().outcomeDetail().contains("status=REROLLED"));
        assertTrue(history.getFirst().outcomeDetail().contains("decision=ACCEPT"));
    }

    @Test
    void singleEligibleRewardSkipsRerollAndChargesNothing() throws Exception {
        var crate = createRerollCrate("reroll_single", 15, false);
        var player = server.addPlayer("SingleReroll");
        player.setOp(false);
        plugin.keys().give(player, "basic", 1);
        plugin.database().creditRerolls(player.getUniqueId(), 1,
                "single-reroll-grant", "TEST", "reroll-single", null).join();

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitVirtualOpeningCommit();

        assertTrue(plugin.openings().rerollView(player).isEmpty());
        assertEquals(1, plugin.database().loadRerollBalance(player.getUniqueId()).join().balance());
        assertEquals(1, plugin.database().history(player.getUniqueId(), 10, 0).size());
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
    }

    @Test
    void failedRerollCostRetainsCandidateAndKeepsAcceptAvailable() throws Exception {
        var crate = createRerollCrate("reroll_no_balance", 15, true);
        var player = server.addPlayer("NoRerollBalance");
        player.setOp(false);
        plugin.keys().give(player, "basic", 1);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitRerollDecision(player);
        String retained = plugin.openings().rerollView(player).orElseThrow().candidate().id();
        plugin.openings().requestReroll(player);
        awaitRerollDecision(player);

        var decision = plugin.openings().rerollView(player).orElseThrow();
        assertEquals(retained, decision.candidate().id());
        assertFalse(decision.processing());
        assertEquals(0, plugin.database().loadRerollBalance(player.getUniqueId()).join().balance());
        plugin.openings().acceptReroll(player, "ACCEPT");
        awaitVirtualOpeningCommit();

        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(retained, history.getFirst().rewardIds());
        assertTrue(history.getFirst().outcomeDetail().contains("status=FAILED"));
    }

    @Test
    void closingRerollDecisionAcceptsCurrentCandidateExactlyOnce() throws Exception {
        var crate = createRerollCrate("reroll_close", 15, true);
        var player = server.addPlayer("CloseReroll");
        player.setOp(false);
        plugin.keys().give(player, "basic", 1);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitRerollDecision(player);
        String accepted = plugin.openings().rerollView(player).orElseThrow().candidate().id();
        // MockBukkit does not emit a populated InventoryCloseEvent from closeInventory(); the
        // listener delegates to this same idempotent accept-current operation on Paper.
        plugin.openings().acceptReroll(player, "CLOSE");
        player.closeInventory();
        awaitVirtualOpeningCommit();

        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(accepted, history.getFirst().rewardIds());
        assertTrue(history.getFirst().outcomeDetail().contains("reason=CLOSE"));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertFalse(plugin.openings().isOpening(player.getUniqueId()));
    }

    @Test
    void rerollTimeoutAcceptsCurrentCandidateExactlyOnce() throws Exception {
        var crate = createRerollCrate("reroll_timeout", 1, true);
        var player = server.addPlayer("TimeoutReroll");
        player.setOp(false);
        plugin.keys().give(player, "basic", 1);

        assertTrue(plugin.openings().open(player, crate, 1, false));
        awaitRerollDecision(player);
        String accepted = plugin.openings().rerollView(player).orElseThrow().candidate().id();
        server.getScheduler().performTicks(21);
        awaitVirtualOpeningCommit();

        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(accepted, history.getFirst().rewardIds());
        assertTrue(history.getFirst().outcomeDetail().contains("reason=TIMEOUT"));
        assertEquals(1, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertFalse(plugin.openings().isOpening(player.getUniqueId()));
    }

    @Test
    void explicitlyEnabledMassRerollReplacesFinalCandidateWithoutDuplicatingBatch() throws Exception {
        var crate = createRerollCrate("reroll_mass", 15, true, true);
        var player = server.addPlayer("MassReroll");
        player.setOp(false);
        plugin.keys().give(player, "basic", 2);
        plugin.database().creditRerolls(player.getUniqueId(), 1,
                "mass-reroll-grant", "TEST", "reroll-mass", null).join();

        assertTrue(plugin.openings().open(player, crate, 2, false));
        awaitRerollDecision(player);
        String originalFinal = plugin.openings().rerollView(player).orElseThrow().candidate().id();
        plugin.openings().requestReroll(player);
        awaitRerollDecision(player);
        assertFalse(originalFinal.equals(plugin.openings().rerollView(player).orElseThrow().candidate().id()));
        plugin.openings().acceptReroll(player, "ACCEPT");
        awaitVirtualOpeningCommit();

        var history = plugin.database().history(player.getUniqueId(), 10, 0);
        assertEquals(1, history.size());
        assertEquals(2, history.getFirst().openingCount());
        assertEquals(2, history.getFirst().rewardIds().split(",").length);
        assertEquals(2, plugin.statistics().player(player.getUniqueId(), crate.id()));
        assertEquals(0, plugin.keys().count(player, "basic"));
        assertEquals(0, plugin.database().loadRerollBalance(player.getUniqueId()).join().balance());
    }

    @Test
    void draftCannotPublishWithAnEmptyPoolOrUnresolvedKey() throws Exception {
        plugin.crates().createDraft("unfinished", "TEST");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> plugin.crates().publish("unfinished", plugin.keys(), "TEST"));

        assertTrue(error.getMessage().contains("Select at least one key"));
        assertTrue(error.getMessage().contains("enabled deliverable reward"));
        assertFalse(plugin.crates().find("unfinished").orElseThrow().enabled());
    }

    private void setDropOverflow(boolean enabled) throws Exception {
        var file = new java.io.File(plugin.getDataFolder(), "config.yml");
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        config.set("settings.drop-overflow-items", enabled);
        config.save(file);
        assertTrue(plugin.reloadFor(server.getConsoleSender()));
    }

    private com.antondev.crates.model.Crate enableVirtualWallet(String policy, boolean mixed) throws Exception {
        var configFile = new java.io.File(plugin.getDataFolder(), "config.yml");
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
        config.set("features.virtual-key-wallet", true);
        config.save(configFile);
        assertTrue(plugin.reloadFor(server.getConsoleSender()));
        plugin.crates().createDraft("virtual_test", "TEST");
        plugin.crates().setAcceptedKeys("virtual_test", List.of("basic"), 1, "TEST");
        plugin.crates().setPaymentPolicy("virtual_test",
                com.antondev.crates.domain.key.KeyPaymentPolicy.valueOf(policy), mixed, "TEST");
        plugin.crates().addCapturedReward("virtual_test", "reward", 1,
                new ItemStack(Material.DIAMOND), "TEST");
        plugin.crates().publish("virtual_test", plugin.keys(), "TEST");
        return plugin.crates().find("virtual_test").orElseThrow();
    }

    private com.antondev.crates.model.Crate createRerollCrate(
            String id, int timeoutSeconds, boolean twoRewards) throws Exception {
        return createRerollCrate(id, timeoutSeconds, twoRewards, false);
    }

    private com.antondev.crates.model.Crate createRerollCrate(
            String id, int timeoutSeconds, boolean twoRewards, boolean massAllowed) throws Exception {
        plugin.crates().createDraft(id, "TEST");
        plugin.crates().setAcceptedKeys(id, List.of("basic"), 1, "TEST");
        plugin.crates().setOpening(id, 0, true, 10,
                com.antondev.crates.domain.crate.AnimationType.INSTANT, "TEST");
        plugin.crates().addCapturedReward(id, "diamond", 100,
                new ItemStack(Material.DIAMOND), "TEST");
        if (twoRewards) {
            plugin.crates().addCapturedReward(id, "emerald", 50,
                    new ItemStack(Material.EMERALD), "TEST");
        }
        plugin.crates().setRerollPolicy(id, new com.antondev.crates.service.RerollService.Policy(
                true, 1, com.antondev.crates.service.RerollService.CostType.TOKEN, 1, "", true,
                timeoutSeconds, com.antondev.crates.service.RerollService.TimeoutPolicy.ACCEPT_CURRENT,
                massAllowed), "TEST");
        plugin.crates().publish(id, plugin.keys(), "TEST");
        return plugin.crates().find(id).orElseThrow();
    }

    private int openSelectiveConfirmation(org.bukkit.entity.Player player,
                                          com.antondev.crates.model.Crate crate, String rewardId) {
        plugin.menus().openPreview(player, crate, 0, false);
        var preview = (com.antondev.crates.gui.MenuHolder)
                player.getOpenInventory().getTopInventory().getHolder();
        int rewardSlot = plugin.menusConfig().slots("preview.reward-slots").stream()
                .filter(slot -> preview.action(slot) != null
                        && preview.action(slot).id().equals("select-reward")
                        && preview.action(slot).value().equals(rewardId))
                .findFirst().orElseThrow();
        var click = new org.bukkit.event.inventory.InventoryClickEvent(player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, rewardSlot,
                org.bukkit.event.inventory.ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        plugin.menus().click(click);
        assertTrue(player.getOpenInventory().getTopInventory().getHolder()
                instanceof com.antondev.crates.gui.MenuHolder confirmation
                && confirmation.kind() == com.antondev.crates.gui.MenuHolder.Kind.SELECTIVE_CONFIRM
                && confirmation.rewardId().equals(rewardId));
        return rewardSlot;
    }

    private void awaitOpeningCommit() {
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
        plugin.database().awaitIdle().join();
        server.getScheduler().performTicks(2);
    }

    private void awaitVirtualOpeningCommit() {
        for (int pass = 0; pass < 10; pass++) {
            plugin.database().awaitIdle().join();
            server.getScheduler().performTicks(2);
        }
    }

    private void awaitRerollDecision(org.bukkit.entity.Player player) {
        for (int pass = 0; pass < 10; pass++) {
            plugin.database().awaitIdle().join();
            server.getScheduler().performTicks(2);
            if (plugin.openings().rerollView(player).isPresent()
                    && !plugin.openings().rerollView(player).orElseThrow().processing()) return;
        }
        assertTrue(plugin.openings().rerollView(player).isPresent(), "reroll decision did not become ready");
    }

    private static final class Canceller implements Listener {
        @EventHandler
        public void cancel(CratePreOpenEvent event) {
            event.setCancelled(true);
        }
    }

    private static final class PortableCanceller implements Listener {
        @EventHandler
        public void cancel(PortableCrateUseEvent event) {
            event.setCancelled(true);
        }
    }
}

from pathlib import Path

menu = Path('src/main/java/com/antondev/crates/gui/MenuService.java')
source = menu.read_text(encoding='utf-8')
import_anchor = 'import com.antondev.crates.config.Text;\n'
import_line = 'import com.antondev.crates.gui.player.ProbabilityPresentation;\n'
if import_line not in source:
    source = source.replace(import_anchor, import_anchor + import_line, 1)

start = source.index('            double chance = canWin ? RewardSelector.chance(reward, eligible) : 0;')
end = source.index('            if (crate.pity().enabled()', start)
replacement = '''            double chance = canWin ? RewardSelector.chance(reward, eligible) : 0;
            var lore = new ArrayList<Component>();
            if (adminOrigin) {
                for (String line : menus.strings("preview.reward-lore")) {
                    lore.add(Text.parse(line,
                            Text.value("eligible_chance", format(chance)),
                            Text.value("base_chance", format(reward.baseChancePercent())),
                            Text.value("chance", format(chance)),
                            Text.value("weight", format(reward.baseChancePercent()))));
                }
                if (reward.chanceBasisPoints() <= 0) {
                    lore.add(Text.parse("<red>Not in pool (0.00%).</red>"));
                } else if (!canWin) {
                    lore.add(Text.parse("<red>This source and its allowed alternative are unavailable.</red>"));
                }
            } else {
                ProbabilityPresentation probability = selective
                        ? ProbabilityPresentation.selective(canWin, false)
                        : ProbabilityPresentation.random(chance, reward.baseChancePercent(), canWin, false);
                lore.add(Text.parse("<gray>" + probability.primary() + "</gray>"));
                if (!probability.secondary().isBlank()) {
                    lore.add(Text.parse("<dark_gray>" + probability.secondary() + "</dark_gray>"));
                }
            }
            if (plugin.settings().alternativeRewardsEnabled() && reward.hasAlternative()) {
                if (adminOrigin) {
                    lore.add(Text.parse("<gold>Alternative:</gold> <white>" + reward.alternativeRewardId()
                            + "</white> <gray>for " + reward.alternativeReasons().stream().map(Enum::name).sorted()
                            .collect(java.util.stream.Collectors.joining(", ")) + "</gray>"));
                } else {
                    CrateReward alternative = crate.rewards().get(reward.alternativeRewardId());
                    lore.add(Text.parse("<gold>Fallback reward:</gold> ").append(alternative == null
                            ? Text.parse("<gray>Configured alternative</gray>") : alternative.displayName()));
                    lore.add(Text.parse("<gray>Used only when this reward is unavailable.</gray>"));
                }
            }
            if (outcome != null && outcome.fallback()) {
                if (adminOrigin) {
                    lore.add(Text.parse("<yellow>Current outcome:</yellow> ").append(outcome.actual().displayName())
                            .append(Text.parse(" <dark_gray>(" + outcome.alternativeReason().name() + ")</dark_gray>")));
                    lore.add(Text.parse("<gray>The source ticket's configured chance is retained.</gray>"));
                } else {
                    lore.add(Text.parse("<yellow>Current eligible reward:</yellow> ").append(outcome.actual().displayName()));
                    lore.add(Text.parse("<gray>The configured selection chance remains unchanged.</gray>"));
                }
            }
            if (selective) {
                if (!selectable) lore.add(Text.parse("<red>Selective opening is disabled by the server.</red>"));
                else if (canWin) {
                    lore.add(Text.parse("<green>Click to choose this exact reward.</green>"));
                    holder.bind(rewardSlots.get(slotIndex), "select-reward", reward.id());
                }
            }
'''
source = source[:start] + replacement + source[end:]

confirmation = source.index('    private void openSelectiveConfirmation')
start = source.index('        ItemStack display = actual.displayCopy();', confirmation)
end = source.index('        int cost = portableIssueId == null ? crate.keyCost() : 1;', start)
replacement = '''        ItemStack display = actual.displayCopy();
        var delivery = new ArrayList<Component>();
        delivery.add(Component.empty());
        delivery.add(Text.parse("<gray>Reward</gray> <dark_gray>»</dark_gray> ").append(actual.displayName()));
        if (outcome.fallback()) {
            delivery.add(Text.parse("<yellow>A fallback reward is active because the original reward is unavailable.</yellow>"));
        }
        delivery.add(Text.parse("<gray>Amount</gray> <dark_gray>»</dark_gray> <white>1 opening</white>"));
        int itemCount = actual.itemCopies().stream().mapToInt(ItemStack::getAmount).sum();
        delivery.add(Text.parse("<gray>Items</gray> <dark_gray>»</dark_gray> <white>" + itemCount
                + " across " + actual.itemCopies().size() + " exact stack(s)</white>"));
        if (!actual.commands().isEmpty()) delivery.add(Text.parse("<gray>Server actions</gray> <dark_gray>»</dark_gray> <white>"
                + actual.commands().size() + " configured action(s)</white>"));
        if (actual.experiencePoints() > 0) delivery.add(Text.parse("<gray>Experience points</gray> <dark_gray>»</dark_gray> <white>"
                + actual.experiencePoints() + "</white>"));
        if (actual.experienceLevels() > 0) delivery.add(Text.parse("<gray>Experience levels</gray> <dark_gray>»</dark_gray> <white>"
                + actual.experienceLevels() + "</white>"));
        if (actual.money() > 0) delivery.add(Text.parse("<gray>Money</gray> <dark_gray>»</dark_gray> <white>"
                + format(actual.money()) + "</white>"));
        delivery.add(Text.parse("<green>Eligible now; eligibility is checked again on confirm.</green>"));
        appendLore(display, delivery);
        inventory.setItem(menus.slot("selective-confirm.reward"), display);

'''
source = source[:start] + replacement + source[end:]
menu.write_text(source, encoding='utf-8')

phase2 = Path('src/test/java/com/antondev/crates/Phase2SimulationContractTest.java')
text = phase2.read_text(encoding='utf-8').replace("PLUGIN_VERSION: '5.0.0-rc.3'", "PLUGIN_VERSION: '5.0.0-rc.4'")
phase2.write_text(text, encoding='utf-8')

ux = Path('src/test/java/com/antondev/crates/gui/player/Phase3PlayerCrateUxArchitectureTest.java')
text = ux.read_text(encoding='utf-8')
anchor = '    @Test void playerFacingSourceDoesNotExposeReliabilityEnumsOrSchemaTerms() throws IOException {'
test = '''    @Test void compatibilityPreviewUsesPlayerProbabilityLanguageAndCleanConfirmation() throws IOException {
        String source = Files.readString(LEGACY_MENU);
        String preview = section(source, "private void renderPreview", "private void appendMilestonePreview");
        assertTrue(preview.contains("ProbabilityPresentation.selective"));
        assertTrue(preview.contains("ProbabilityPresentation.random"));
        assertTrue(preview.contains("Fallback reward:"));
        assertTrue(preview.contains("Current eligible reward:"));
        String confirm = section(source, "private void openSelectiveConfirmation", "public void openAdmin");
        assertFalse(confirm.contains("Source reward"));
        assertFalse(confirm.contains("Actual reward"));
        assertFalse(confirm.contains("alternativeReason().name()"));
        assertFalse(confirm.contains("requiredPermission()"));
    }

'''
if test not in text:
    text = text.replace(anchor, test + anchor, 1)
ux.write_text(text, encoding='utf-8')

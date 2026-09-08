package com.plexoncrates.animation;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.manager.GUIManager;
import com.plexoncrates.util.ColorUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class CsgoRollAnimation implements Animation {
    private static final int[] ROW = {9,10,11,12,13,14,15,16,17};
    private final PlexonCrates plugin; private final GUIManager gui;
    private final Map<UUID, AtomicBoolean> cancelled = new ConcurrentHashMap<>();

    public CsgoRollAnimation(PlexonCrates plugin, GUIManager gui) { this.plugin = plugin; this.gui = gui; }

    @Override
    public void play(Player player, Crate crate, Reward reward, int durationTicks, Runnable complete) {
        AtomicBoolean stop = new AtomicBoolean(false); cancelled.put(player.getUniqueId(), stop);
        Inventory inventory = gui.createOpeningInventory(player, ColorUtil.color("&8Opening " + crate.displayName()), 27);
        fill(inventory);
        Deque<ItemStack> strip = new ArrayDeque<>();
        List<Reward> rewards = new ArrayList<>(crate.rewards()); if (rewards.isEmpty()) rewards.add(reward);
        for (int ignored : ROW) strip.add(randomReward(rewards).displayItem());
        render(inventory, strip); player.openInventory(inventory);
        int steps = Math.max(24, Math.min(42, durationTicks / 3));
        tick(player, reward, rewards, inventory, strip, 0, steps, stop, complete);
    }

    private void tick(Player player, Reward winningReward, List<Reward> rewards, Inventory inventory,
                      Deque<ItemStack> strip, int step, int totalSteps, AtomicBoolean stop, Runnable complete) {
        if (stop.get() || !player.isOnline()) { cancelled.remove(player.getUniqueId()); return; }
        strip.removeFirst(); strip.addLast(randomReward(rewards).displayItem()); render(inventory, strip);
        float pitch = Math.min(2.0F, 0.7F + (step / (float) totalSteps) * 1.2F);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35F, pitch);
        if (step >= totalSteps - 1) {
            inventory.setItem(13, highlighted(winningReward.displayItem(), "&e&lYOUR REWARD"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.15F);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> { cancelled.remove(player.getUniqueId()); complete.run(); }, 10L);
            return;
        }
        double progress = step / (double) totalSteps;
        long delay = progress < 0.45D ? 1L : progress < 0.72D ? 2L : progress < 0.90D ? 4L : 7L;
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> tick(player, winningReward, rewards, inventory, strip, step + 1, totalSteps, stop, complete), delay);
    }

    private static void render(Inventory inventory, Deque<ItemStack> items) {
        int index = 0; for (ItemStack item : items) inventory.setItem(ROW[index++], item.clone());
        inventory.setItem(4, marker("&e▼")); inventory.setItem(22, marker("&e▲"));
    }
    private static Reward randomReward(List<Reward> rewards) { return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size())); }
    private static void fill(Inventory inventory) { ItemStack pane = marker("&8"); for (int slot=0; slot<inventory.getSize(); slot++) inventory.setItem(slot,pane); for (int slot:ROW) inventory.setItem(slot,null); }
    private static ItemStack marker(String name) { ItemStack item=new ItemStack(Material.GRAY_STAINED_GLASS_PANE); ItemMeta meta=item.getItemMeta(); meta.setDisplayName(ColorUtil.color(name)); item.setItemMeta(meta); return item; }
    private static ItemStack highlighted(ItemStack source, String line) { ItemStack item=source.clone(); ItemMeta meta=item.getItemMeta(); List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>(); lore.add(""); lore.add(ColorUtil.color(line)); meta.setLore(lore); item.setItemMeta(meta); return item; }
    @Override public void cancel(UUID playerId) { AtomicBoolean flag=cancelled.remove(playerId); if(flag!=null) flag.set(true); }
}

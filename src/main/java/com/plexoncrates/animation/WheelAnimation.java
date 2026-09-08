package com.plexoncrates.animation;

import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.Reward;
import com.plexoncrates.manager.GUIManager;
import com.plexoncrates.util.ColorUtil;
import java.util.ArrayList;
import java.util.Collections;
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

public final class WheelAnimation implements Animation {
    private static final int[] RING={10,11,12,13,14,15,16,25,34,43,42,41,40,39,38,37,28,19};
    private final PlexonCrates plugin; private final GUIManager gui;
    private final Map<UUID, AtomicBoolean> cancelled=new ConcurrentHashMap<>();
    public WheelAnimation(PlexonCrates plugin, GUIManager gui){this.plugin=plugin;this.gui=gui;}

    @Override public void play(Player player,Crate crate,Reward reward,int durationTicks,Runnable complete){
        AtomicBoolean stop=new AtomicBoolean(false);cancelled.put(player.getUniqueId(),stop);
        Inventory inventory=gui.createOpeningInventory(player,ColorUtil.color("&8Wheel • "+crate.displayName()),54);fill(inventory);
        List<Reward> rewards=new ArrayList<>(crate.rewards());if(rewards.isEmpty())rewards.add(reward);
        List<ItemStack> ring=new ArrayList<>();for(int ignored:RING)ring.add(randomReward(rewards).displayItem());render(inventory,ring,-1);player.openInventory(inventory);
        int steps=Math.max(28,Math.min(48,durationTicks/2));tick(player,reward,rewards,inventory,ring,0,steps,stop,complete);
    }
    private void tick(Player player,Reward winningReward,List<Reward> rewards,Inventory inventory,List<ItemStack> ring,int step,int totalSteps,AtomicBoolean stop,Runnable complete){
        if(stop.get()||!player.isOnline()){cancelled.remove(player.getUniqueId());return;}
        Collections.rotate(ring,1);if(step%3==0)ring.set(0,randomReward(rewards).displayItem());int marker=step%RING.length;render(inventory,ring,marker);
        player.playSound(player.getLocation(),Sound.BLOCK_NOTE_BLOCK_HAT,0.35F,Math.min(2.0F,0.75F+step/(float)totalSteps));
        if(step>=totalSteps-1){int winningSlot=RING[4];inventory.setItem(winningSlot,highlighted(winningReward.displayItem()));inventory.setItem(22,center("&e&lWINNER"));player.playSound(player.getLocation(),Sound.ENTITY_PLAYER_LEVELUP,1.0F,1.2F);plugin.getServer().getScheduler().runTaskLater(plugin,()->{cancelled.remove(player.getUniqueId());complete.run();},10L);return;}
        double progress=step/(double)totalSteps;long delay=progress<0.55D?1L:progress<0.78D?2L:progress<0.92D?4L:7L;
        plugin.getServer().getScheduler().runTaskLater(plugin,()->tick(player,winningReward,rewards,inventory,ring,step+1,totalSteps,stop,complete),delay);
    }
    private static void render(Inventory inventory,List<ItemStack> ring,int marker){for(int index=0;index<RING.length;index++){ItemStack item=ring.get(index).clone();if(index==marker)item=highlighted(item);inventory.setItem(RING[index],item);}inventory.setItem(22,center("&6&lSPINNING"));}
    private static Reward randomReward(List<Reward> rewards){return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size()));}
    private static void fill(Inventory inventory){ItemStack pane=center("&8");for(int slot=0;slot<inventory.getSize();slot++)inventory.setItem(slot,pane);for(int slot:RING)inventory.setItem(slot,null);}
    private static ItemStack center(String name){ItemStack item=new ItemStack(Material.BLACK_STAINED_GLASS_PANE);ItemMeta meta=item.getItemMeta();meta.setDisplayName(ColorUtil.color(name));item.setItemMeta(meta);return item;}
    private static ItemStack highlighted(ItemStack source){ItemStack item=source.clone();ItemMeta meta=item.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add(ColorUtil.color("&e▶ Current position"));meta.setLore(lore);item.setItemMeta(meta);return item;}
    @Override public void cancel(UUID playerId){AtomicBoolean flag=cancelled.remove(playerId);if(flag!=null)flag.set(true);}
}

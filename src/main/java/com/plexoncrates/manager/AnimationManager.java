package com.plexoncrates.manager;

import com.plexoncrates.animation.Animation;
import com.plexoncrates.animation.CsgoRollAnimation;
import com.plexoncrates.animation.WheelAnimation;
import com.plexoncrates.config.ConfigManager;
import com.plexoncrates.core.PlexonCrates;
import com.plexoncrates.crate.Crate;
import com.plexoncrates.crate.CrateLocation;
import com.plexoncrates.crate.Reward;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

public final class AnimationManager {
    private final PlexonCrates plugin;private final ConfigManager config;private final CrateManager crates;private final RewardExecutor rewards;
    private final Map<String,Animation> animations=new ConcurrentHashMap<>();private final Map<UUID,Animation> active=new ConcurrentHashMap<>();private BukkitTask idleTask;private double phase;
    public AnimationManager(PlexonCrates plugin,ConfigManager config,CrateManager crates,GUIManager gui,RewardExecutor rewards){this.plugin=plugin;this.config=config;this.crates=crates;this.rewards=rewards;animations.put("CSGO",new CsgoRollAnimation(plugin,gui));animations.put("WHEEL",new WheelAnimation(plugin,gui));}
    public void startIdleEffects(){stopIdleEffects();if(!config.effectsEnabled())return;idleTask=plugin.getServer().getScheduler().runTaskTimer(plugin,this::renderIdleEffects,20L,config.idleIntervalTicks());}public void stopIdleEffects(){if(idleTask!=null){idleTask.cancel();idleTask=null;}}public boolean isOpening(UUID playerId){return active.containsKey(playerId);}
    public void start(org.bukkit.entity.Player player,Crate crate,Reward reward,Location physicalSource){if(active.containsKey(player.getUniqueId())){player.sendMessage(config.message("already-opening"));return;}Animation animation=animations.getOrDefault(crate.animation().toUpperCase(Locale.ROOT),animations.get("CSGO"));active.put(player.getUniqueId(),animation);animation.play(player,crate,reward,config.openingDurationTicks(),()->{active.remove(player.getUniqueId());if(player.isOnline()){player.closeInventory();rewards.execute(player,crate,reward);if(physicalSource!=null)showWorldReward(player,reward,physicalSource);}});}
    public void cancel(UUID playerId){Animation animation=active.remove(playerId);if(animation!=null)animation.cancel(playerId);}public void shutdown(){stopIdleEffects();active.keySet().forEach(this::cancel);active.clear();}
    private void renderIdleEffects(){if(!config.effectsEnabled()||!config.particlesEnabled())return;phase+=0.22D;int rendered=0;for(Crate crate:crates.all()){if(!crate.enabled()||"NONE".equalsIgnoreCase(crate.idleEffect()))continue;for(CrateLocation stored:crate.locations()){if(rendered++>=config.maxBlocksPerIdlePass())return;Location center=stored.center();if(center==null)continue;World world=center.getWorld();if(world==null||!world.isChunkLoaded(center.getBlockX()>>4,center.getBlockZ()>>4))continue;switch(crate.idleEffect().toUpperCase(Locale.ROOT)){case "CLOUD"->cloud(center);case "FOUNTAIN"->fountain(center);default->helix(center);}}}}
    private void helix(Location center){World world=center.getWorld();if(world==null)return;for(int arm=0;arm<2;arm++){double angle=phase+arm*Math.PI;double x=Math.cos(angle)*0.65D;double z=Math.sin(angle)*0.65D;double y=0.25D+((phase+arm*0.6D)%2.2D);world.spawnParticle(Particle.END_ROD,center.clone().add(x,y,z),1,0,0,0,0);}}
    private void cloud(Location center){World world=center.getWorld();if(world==null)return;double angle=phase*0.7D;world.spawnParticle(Particle.CLOUD,center.clone().add(Math.cos(angle)*0.45D,0.9D,Math.sin(angle)*0.45D),2,0.12D,0.05D,0.12D,0.01D);}
    private void fountain(Location center){World world=center.getWorld();if(world==null)return;world.spawnParticle(Particle.ENCHANTMENT_TABLE,center.clone().add(0,1.0D,0),4,0.35D,0.6D,0.35D,0.05D);if(((int)(phase*10))%3==0)world.spawnParticle(Particle.END_ROD,center.clone().add(0,1.25D,0),1,0.05D,0.2D,0.05D,0.01D);}
    private void showWorldReward(org.bukkit.entity.Player player,Reward reward,Location source){World world=source.getWorld();if(world==null)return;Location spawn=source.clone().add(0.5D,1.05D,0.5D);ItemStack shown=reward.displayItem();shown.setAmount(1);ArmorStand stand=world.spawn(spawn,ArmorStand.class,entity->{entity.setVisible(false);entity.setGravity(false);entity.setSmall(true);entity.setMarker(true);entity.setArms(true);entity.setRightArmPose(new EulerAngle(Math.toRadians(270),0,0));if(entity.getEquipment()!=null)entity.getEquipment().setItemInMainHand(shown);});world.spawnParticle(Particle.EXPLOSION_LARGE,spawn,1);world.spawnParticle(Particle.FIREWORKS_SPARK,spawn,20,0.35D,0.35D,0.35D,0.08D);if(config.soundsEnabled())world.playSound(spawn,Sound.ENTITY_FIREWORK_ROCKET_BLAST,1.0F,1.25F);final int[] tick={0};BukkitTask[] task=new BukkitTask[1];task[0]=plugin.getServer().getScheduler().runTaskTimer(plugin,()->{if(!stand.isValid()||tick[0]++>=25){if(stand.isValid()){Location end=stand.getLocation();world.spawnParticle(Particle.TOTEM,end,20,0.35D,0.35D,0.35D,0.05D);stand.remove();}task[0].cancel();return;}stand.teleport(stand.getLocation().add(0,0.055D,0));stand.setRotation((tick[0]*18F)%360F,0F);},1L,2L);}
}

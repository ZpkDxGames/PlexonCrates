package com.plexoncrates.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.plexoncrates.crate.Reward;
import com.plexoncrates.crate.RewardAction;
import com.plexoncrates.crate.RewardActionType;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class WeightedSelectorTest {
    @Test void chanceUsesEnabledIntegerWeights(){Reward common=reward("common",true,70),rare=reward("rare",true,25),mythic=reward("mythic",true,5);List<Reward> rewards=List.of(common,rare,mythic);assertEquals(70.0D,WeightedSelector.chance(common,rewards),0.0001D);assertEquals(25.0D,WeightedSelector.chance(rare,rewards),0.0001D);assertEquals(5.0D,WeightedSelector.chance(mythic,rewards),0.0001D);}
    @Test void disabledRewardsDoNotContributeToChanceOrSelection(){Reward enabled=reward("enabled",true,1),disabled=reward("disabled",false,999999);List<Reward> rewards=List.of(enabled,disabled);assertEquals(100.0D,WeightedSelector.chance(enabled,rewards),0.0001D);assertEquals(0.0D,WeightedSelector.chance(disabled,rewards),0.0001D);for(int attempt=0;attempt<100;attempt++)assertEquals("enabled",WeightedSelector.select(rewards).orElseThrow().id());}
    @Test void zeroWeightPoolDoesNotSelect(){assertTrue(WeightedSelector.select(List.of(reward("zero",true,0))).isEmpty());}
    private static Reward reward(String id,boolean enabled,int weight){return new Reward(id,enabled,weight,new ItemStack(Material.STONE),List.of(new RewardAction(RewardActionType.ITEM,"",null)));}
}

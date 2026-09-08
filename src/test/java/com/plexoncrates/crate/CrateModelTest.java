package com.plexoncrates.crate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

final class CrateModelTest {
    @Test void copyProtectsItemAndRewardCollections(){Reward reward=new Reward("diamond",true,10,new ItemStack(Material.DIAMOND,2),List.of(new RewardAction(RewardActionType.ITEM,"",null)));Crate original=new Crate("vote",true,"&aVote",List.of("line"),new ItemStack(Material.CHEST),"&aVote Key",new ItemStack(Material.TRIPWIRE_HOOK),"CSGO","HELIX",List.of(reward),List.of());Crate copy=original.copy();assertEquals("vote",copy.id());assertEquals(1,copy.rewards().size());assertNotSame(original.icon(),copy.icon());assertTrue(copy.reward("diamond").isPresent());}
}

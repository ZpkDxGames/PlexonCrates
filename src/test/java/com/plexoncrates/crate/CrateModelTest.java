package com.plexoncrates.crate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

final class CrateModelTest {
    @Test
    void copyProtectsItemAndRewardCollections() {
        Reward reward = new Reward("diamond", true, 10, cloneableStack(),
                List.of(new RewardAction(RewardActionType.ITEM, "", null)));
        Crate original = new Crate("vote", true, "&aVote", List.of("line"), cloneableStack(),
                "&aVote Key", cloneableStack(), "CSGO", "HELIX", List.of(reward), List.of());

        Crate copy = original.copy();

        assertEquals("vote", copy.id());
        assertEquals(1, copy.rewards().size());
        assertNotSame(original.icon(), copy.icon());
        assertTrue(copy.reward("diamond").isPresent());
    }

    private static ItemStack cloneableStack() {
        return mock(ItemStack.class, invocation -> {
            if (invocation.getMethod().getName().equals("clone")
                    && invocation.getMethod().getParameterCount() == 0) {
                return cloneableStack();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}

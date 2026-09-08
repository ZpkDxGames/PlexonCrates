package com.antondev.crates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.antondev.crates.domain.opening.OpenSource;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

final class QuestsContractTest {
    @Test
    void crateOpenEventMatchesPlexonQuests31PublicContract() throws Exception {
        Class<?> eventType = Class.forName("com.antondev.crates.api.event.CrateOpenEvent");
        assertTrue(Event.class.isAssignableFrom(eventType));
        assertFalse(Cancellable.class.isAssignableFrom(eventType));
        assertEquals(Player.class, eventType.getMethod("player").getReturnType());

        Class<?> planType = eventType.getMethod("plan").getReturnType();
        assertEquals(UUID.class, planType.getMethod("transactionId").getReturnType());
        assertEquals(String.class, planType.getMethod("crateId").getReturnType());
        assertEquals(String.class, planType.getMethod("keyId").getReturnType());
        assertEquals(int.class, planType.getMethod("openingCount").getReturnType());
        assertEquals(List.class, planType.getMethod("rewardIds").getReturnType());
        assertEquals(OpenSource.class, planType.getMethod("source").getReturnType());
    }
}

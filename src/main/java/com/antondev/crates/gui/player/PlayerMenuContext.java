package com.antondev.crates.gui.player;

import com.antondev.crates.service.KeyPaymentPlanner;
import java.util.Objects;

/** Immutable navigation state. Internal IDs never become player-facing text. */
public record PlayerMenuContext(
        Screen screen,
        int hallPage,
        int page,
        String crateId,
        String rewardId,
        int amount,
        KeyPaymentPlanner.Preference paymentPreference) {

    public enum Screen {
        HALL,
        PREVIEW,
        QUANTITY,
        MASS_CONFIRM,
        SELECTIVE_CONFIRM,
        PENDING_REWARDS
    }

    public PlayerMenuContext {
        screen = Objects.requireNonNull(screen, "screen");
        crateId = Objects.requireNonNullElse(crateId, "");
        rewardId = Objects.requireNonNullElse(rewardId, "");
        paymentPreference = Objects.requireNonNull(paymentPreference, "paymentPreference");
        if (hallPage < 0 || page < 0 || amount < 0) {
            throw new IllegalArgumentException("Player menu navigation values cannot be negative");
        }
    }

    public static PlayerMenuContext hall(int page) {
        return new PlayerMenuContext(Screen.HALL, Math.max(0, page), Math.max(0, page), "", "", 0,
                KeyPaymentPlanner.Preference.PHYSICAL);
    }

    public static PlayerMenuContext preview(String crateId, int hallPage, int rewardPage) {
        return new PlayerMenuContext(Screen.PREVIEW, Math.max(0, hallPage), Math.max(0, rewardPage),
                crateId, "", 0, KeyPaymentPlanner.Preference.PHYSICAL);
    }

    public PlayerMenuContext withPaymentPreference(KeyPaymentPlanner.Preference preference) {
        return new PlayerMenuContext(screen, hallPage, page, crateId, rewardId, amount, preference);
    }

    public PlayerMenuContext withRewardPage(int rewardPage) {
        return new PlayerMenuContext(screen, hallPage, Math.max(0, rewardPage), crateId, rewardId, amount,
                paymentPreference);
    }

    public PlayerMenuContext quantity() {
        return new PlayerMenuContext(Screen.QUANTITY, hallPage, page, crateId, "", 0, paymentPreference);
    }

    public PlayerMenuContext massConfirm(int selectedAmount) {
        return new PlayerMenuContext(Screen.MASS_CONFIRM, hallPage, page, crateId, "", selectedAmount,
                paymentPreference);
    }

    public PlayerMenuContext selectiveConfirm(String selectedRewardId) {
        return new PlayerMenuContext(Screen.SELECTIVE_CONFIRM, hallPage, page, crateId, selectedRewardId, 1,
                paymentPreference);
    }

    public static PlayerMenuContext pendingRewards(int page, int hallPage) {
        return new PlayerMenuContext(Screen.PENDING_REWARDS, Math.max(0, hallPage), Math.max(0, page),
                "", "", 0, KeyPaymentPlanner.Preference.PHYSICAL);
    }
}

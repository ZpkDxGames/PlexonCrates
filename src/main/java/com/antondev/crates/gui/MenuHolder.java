package com.antondev.crates.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MenuHolder implements InventoryHolder {
    public enum Kind { BROWSER, PREVIEW, ADMIN, EDITOR, REWARDS, CONFIRM_DELETE, OPENING }

    private final Kind kind;
    private final String crateId;
    private final String rewardId;
    private final int page;
    private final boolean adminOrigin;
    private Inventory inventory;

    public MenuHolder(Kind kind, String crateId, String rewardId, int page, boolean adminOrigin) {
        this.kind = kind;
        this.crateId = crateId;
        this.rewardId = rewardId;
        this.page = page;
        this.adminOrigin = adminOrigin;
    }

    public void attach(Inventory inventory) { this.inventory = inventory; }
    public Kind kind() { return kind; }
    public String crateId() { return crateId; }
    public String rewardId() { return rewardId; }
    public int page() { return page; }
    public boolean adminOrigin() { return adminOrigin; }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Menu inventory has not been attached yet");
        return inventory;
    }
}

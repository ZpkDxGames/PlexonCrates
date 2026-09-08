package com.antondev.crates.gui;

import com.antondev.crates.service.DraftSessionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MenuHolder implements InventoryHolder {
    public enum Kind {
        BROWSER, CLAIMS, PREVIEW, MASS_OPEN, PORTABLE_PREVIEW, SELECTIVE_CONFIRM,
        ADMIN, EDITOR, REWARDS, CONFIRM_DELETE, OPENING, REROLL, SUMMARY,
        CRATE_LIST, KEY_LIST, KEY_TEMPLATE, KEY_SELECT, REWARD_BUILDER,
        MILESTONES, MILESTONE_DETAIL, MILESTONE_REWARD_SELECT, CONFIRM_MILESTONE_DELETE,
        LOCATIONS, STATISTICS, SYSTEM, GLOBAL_REWARDS, WAND_SELECT, CONFIRM_UNLINK, CONFIRM_CRATE_DELETE,
        CONFIRM_KEY_DELETE, CONFIRM_TAKEOVER
    }
    public record Action(String id, String value) {}

    private final UUID sessionId = UUID.randomUUID();
    private final Kind kind;
    private final String crateId;
    private final String rewardId;
    private final int page;
    private final boolean adminOrigin;
    private volatile long revision;
    private volatile long leaseToken;
    private volatile UUID draftId;
    private volatile boolean draftBound;
    private Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();

    public MenuHolder(Kind kind, String crateId, String rewardId, int page, boolean adminOrigin) {
        this(kind, crateId, rewardId, page, adminOrigin, 0);
    }

    public MenuHolder(Kind kind, String crateId, String rewardId, int page, boolean adminOrigin, long revision) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.crateId = Objects.requireNonNull(crateId, "crateId");
        this.rewardId = Objects.requireNonNull(rewardId, "rewardId");
        if (page < 0 || revision < 0) throw new IllegalArgumentException("Menu page and revision cannot be negative");
        this.page = page;
        this.adminOrigin = adminOrigin;
        this.revision = revision;
    }

    public void attach(Inventory inventory) { this.inventory = inventory; }
    public UUID sessionId() { return sessionId; }
    public Kind kind() { return kind; }
    public String crateId() { return crateId; }
    public String rewardId() { return rewardId; }
    public String targetId() { return crateId.isBlank() ? rewardId : crateId; }
    public int page() { return page; }
    public boolean adminOrigin() { return adminOrigin; }
    public long revision() { return revision; }
    public long leaseToken() { return leaseToken; }
    public UUID draftId() { return draftId; }
    public boolean draftBound() { return draftBound; }

    /** Installs the authoritative draft identity when a view is first rendered. */
    public void bindDraft(DraftSessionService.View view) {
        Objects.requireNonNull(view, "view");
        if (!crateId.equals(view.crateId())) {
            throw new IllegalArgumentException("Draft target does not match this menu");
        }
        draftId = view.draftId();
        revision = view.revision();
        leaseToken = view.leaseToken();
        draftBound = true;
    }

    /**
     * Advances a live view only inside the lease it was opened for. A takeover deliberately leaves the old
     * stamp untouched, making every action in that inventory stale until it is reopened.
     */
    public boolean advanceDraft(DraftSessionService.View view) {
        Objects.requireNonNull(view, "view");
        if (!draftBound) {
            bindDraft(view);
            return true;
        }
        if (!crateId.equals(view.crateId())
                || leaseToken != 0 && leaseToken != view.leaseToken()
                || draftId != null && !draftId.equals(view.draftId())
                || view.revision() < revision) return false;
        draftId = view.draftId();
        revision = view.revision();
        leaseToken = view.leaseToken();
        return true;
    }

    public boolean matchesDraft(DraftSessionService.View view) {
        return draftBound
                && crateId.equals(view.crateId())
                && Objects.equals(draftId, view.draftId())
                && revision == view.revision()
                && leaseToken == view.leaseToken();
    }

    public void bind(int slot, String action) { bind(slot, action, ""); }
    public void bind(int slot, String action, String value) { actions.put(slot, new Action(action, value)); }
    public Action action(int slot) { return actions.get(slot); }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Menu inventory has not been attached yet");
        return inventory;
    }
}

package com.antondev.crates.gui;

import com.antondev.crates.service.DraftSessionService;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Owns the one currently routable inventory session for each player. */
public final class GuiSessionService {
    public enum Validation {
        CURRENT,
        SUPERSEDED_SESSION,
        STALE_DRAFT
    }

    private record Active(
            UUID sessionId, MenuHolder.Kind kind, String targetId, int page, MenuHolder holder) {
        private Active {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(holder, "holder");
        }
    }

    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();

    public void activate(UUID playerId, MenuHolder holder) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(holder, "holder");
        active.put(playerId, new Active(holder.sessionId(), holder.kind(), holder.targetId(), holder.page(), holder));
    }

    public Validation validate(Player player, MenuHolder holder, DraftSessionService drafts) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(drafts, "drafts");
        UUID playerId = player.getUniqueId();
        Active current = active.get(playerId);
        if (current == null
                || current.holder() != holder
                || !current.sessionId().equals(holder.sessionId())
                || current.kind() != holder.kind()
                || !current.targetId().equals(holder.targetId())
                || current.page() != holder.page()
                || player.getOpenInventory().getTopInventory().getHolder() != holder
                || player.getOpenInventory().getTopInventory() != holder.getInventory()) {
            return Validation.SUPERSEDED_SESSION;
        }
        if (!holder.draftBound()) return Validation.CURRENT;
        Optional<DraftSessionService.View> view = drafts.view(playerId, holder.crateId());
        return view.isPresent() && holder.matchesDraft(view.get())
                ? Validation.CURRENT : Validation.STALE_DRAFT;
    }

    public Optional<UUID> activeSession(UUID playerId) {
        Active session = active.get(Objects.requireNonNull(playerId, "playerId"));
        return session == null ? Optional.empty() : Optional.of(session.sessionId());
    }

    public void close(UUID playerId, UUID sessionId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionId, "sessionId");
        active.computeIfPresent(playerId, (ignored, current) ->
                current.sessionId().equals(sessionId) ? null : current);
    }

    public void clear(UUID playerId) {
        active.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public void clear() {
        active.clear();
    }

    public int size() {
        return active.size();
    }
}

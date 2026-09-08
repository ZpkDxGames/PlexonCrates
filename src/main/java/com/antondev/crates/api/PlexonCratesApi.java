package com.antondev.crates.api;

import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.model.Crate;
import java.util.Collection;
import java.util.Optional;
import org.bukkit.entity.Player;

public interface PlexonCratesApi {
    Optional<Crate> crate(String id);
    Collection<Crate> crates();
    Optional<KeyDefinition> key(String id);
    Collection<KeyDefinition> keys();
    default long runtimeRevision() { return 0L; }
    default long crateRevision(String crateId) { return 0L; }
    boolean requestOpening(Player player, String crateId, int amount, OpenSource source);
}

package com.antondev.crates.api;

import com.antondev.crates.PlexonCrates;
import com.antondev.crates.domain.key.KeyDefinition;
import com.antondev.crates.domain.opening.OpenSource;
import com.antondev.crates.model.Crate;
import java.util.Collection;
import java.util.Optional;
import org.bukkit.entity.Player;

public final class PlexonCratesApiImpl implements PlexonCratesApi {
    private final PlexonCrates plugin;

    public PlexonCratesApiImpl(PlexonCrates plugin) { this.plugin = plugin; }

    @Override public Optional<Crate> crate(String id) { return plugin.runtime().find(id); }
    @Override public Collection<Crate> crates() { return ListCopies.crates(plugin.runtime().all()); }
    @Override public Optional<KeyDefinition> key(String id) { return plugin.keys().definition(id); }
    @Override public Collection<KeyDefinition> keys() { return ListCopies.keys(plugin.keys().definitions()); }
    @Override public long runtimeRevision() { return plugin.runtime().snapshot().revision(); }
    @Override public long crateRevision(String crateId) { return plugin.runtime().crateRevision(crateId); }

    @Override
    public boolean requestOpening(Player player, String crateId, int amount, OpenSource source) {
        Crate crate = plugin.runtime().find(crateId).orElse(null);
        return crate != null && plugin.openings().open(player, crate, amount, source, null);
    }

    private static final class ListCopies {
        private static Collection<Crate> crates(Collection<Crate> crates) { return java.util.List.copyOf(crates); }
        private static Collection<KeyDefinition> keys(Collection<KeyDefinition> keys) { return java.util.List.copyOf(keys); }
    }
}

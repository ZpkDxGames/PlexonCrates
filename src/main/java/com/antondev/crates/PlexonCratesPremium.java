package com.antondev.crates;

import com.antondev.crates.gui.SimulationAdminListener;
import com.antondev.crates.service.CrateSimulationService;

/**
 * Phase 2 bootstrap that adds the 5.0 analytical admin surface while leaving
 * the mature 4.6 opening/payment/transaction engine unchanged.
 */
public final class PlexonCratesPremium extends PlexonCrates {
    private CrateSimulationService simulations;

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isEnabled()) return;
        simulations = new CrateSimulationService();
        getServer().getPluginManager().registerEvents(new SimulationAdminListener(this, simulations), this);
        getLogger().info("PlexonCrates 5.0 premium simulation runtime enabled (analytical/non-granting).");
    }

    @Override
    public void onDisable() {
        if (simulations != null) {
            simulations.close();
            simulations = null;
        }
        super.onDisable();
    }

    public CrateSimulationService simulations() {
        return simulations;
    }
}

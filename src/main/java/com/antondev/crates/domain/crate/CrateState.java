package com.antondev.crates.domain.crate;

public enum CrateState {
    DRAFT,
    PUBLISHED,
    DISABLED,
    ARCHIVED;

    public boolean playerVisible() {
        return this == PUBLISHED;
    }
}

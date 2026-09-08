package com.antondev.crates.domain.key;

/** Explicit physical/virtual key resolution policy for one crate. */
public enum KeyPaymentPolicy {
    PHYSICAL_ONLY,
    VIRTUAL_ONLY,
    PHYSICAL_FIRST,
    VIRTUAL_FIRST,
    PLAYER_CHOICE
}

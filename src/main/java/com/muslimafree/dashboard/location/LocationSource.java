package com.muslimafree.dashboard.location;

/** Where a member's search position comes from, most precise first. */
public enum LocationSource {
    /** Shared from the device, snapped to a ~1 km grid, and fresh. */
    DEVICE,
    /** Centre of the city / district of the profile. */
    CITY,
    /** Centre of the governorate of the profile. */
    REGION;

    public boolean isApproximate() {
        return this != DEVICE;
    }
}

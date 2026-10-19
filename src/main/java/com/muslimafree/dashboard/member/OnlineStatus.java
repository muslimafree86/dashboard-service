package com.muslimafree.dashboard.member;

/** Online dot / "قبل 10 ساعات" on a card; computed exactly as on the member profile page. */
public enum OnlineStatus {
    ONLINE,
    /** Active, but the member chose "appear busy" in account settings. */
    BUSY,
    OFFLINE
}

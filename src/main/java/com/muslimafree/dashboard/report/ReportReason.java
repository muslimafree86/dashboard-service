package com.muslimafree.dashboard.report;

/** Why a member is reported. */
public enum ReportReason {
    FAKE_PROFILE,
    INAPPROPRIATE_PHOTOS,
    HARASSMENT,
    SCAM,
    OFFENSIVE_MESSAGES,
    /** Not looking for marriage, or already married while claiming otherwise. */
    NOT_SERIOUS,
    UNDERAGE,
    OTHER
}

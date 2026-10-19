package com.muslimafree.dashboard.report;

public enum ReportStatus {
    OPEN,
    /** A moderator acted (e.g. suspended the account in user-profile-managment's admin API). */
    ACTIONED,
    DISMISSED
}

package com.muslimafree.dashboard.member;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * التعليم, in ascending order exactly as user-profile-managment declares it: a partner preference on education is a
 * minimum level, so the discovery SQL compares ranks rather than values.
 */
public enum EducationLevel {
    SECONDARY,
    DIPLOMA,
    BACHELOR,
    MASTER,
    DOCTORATE;

    /** 1-based rank, matching {@link #sqlRank(String)} ({@code FIELD} returns 0 for NULL / unknown values). */
    public static int rankOf(String value) {
        return valueOf(value).ordinal() + 1;
    }

    /** {@code FIELD(column, 'SECONDARY', ..., 'DOCTORATE')}: 1..5, or 0 when the column is NULL. */
    public static String sqlRank(String column) {
        return "FIELD(" + column + ", " + Arrays.stream(values()).map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", ")) + ")";
    }
}

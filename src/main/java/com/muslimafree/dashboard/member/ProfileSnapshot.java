package com.muslimafree.dashboard.member;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.UUID;

/**
 * The parts of a member's profile (owned by user-profile-managment) that discovery reasons about: the viewer's own
 * profile, or the member a "similar members" strip is built around. Enum-valued attributes are kept as the stored
 * codes: discovery compares them, it never interprets them (except the education order).
 */
public record ProfileSnapshot(
        UUID id,
        String accountStatus,
        boolean onboardingCompleted,
        Gender gender,
        LocalDate dateOfBirth,
        String countryCode,
        String region,
        String city,
        String maritalStatus,
        Traits traits,
        Preferences preferences,
        BigDecimal deviceLatitude,
        BigDecimal deviceLongitude,
        LocalDateTime deviceUpdatedAt) {

    /** صفاته - the attributes another member's preferences are compared with. */
    public record Traits(
            String education,
            String children,
            String smoking,
            String sect,
            String employmentSector,
            String appearance,
            String bodyShape) {
    }

    /** ما يبحث عنه - partner preferences; every {@code null} means "اي" (any). */
    public record Preferences(
            Integer ageMin,
            Integer ageMax,
            String countryCode,
            String region,
            String minEducation,
            String children,
            String smoking,
            String sect,
            String employmentSector,
            String appearance,
            String bodyShape) {
    }

    public Integer ageOn(LocalDate today) {
        return dateOfBirth == null ? null : Period.between(dateOfBirth, today).getYears();
    }

    public boolean hasDeviceLocation() {
        return deviceLatitude != null && deviceLongitude != null;
    }
}

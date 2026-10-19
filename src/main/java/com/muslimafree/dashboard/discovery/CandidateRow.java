package com.muslimafree.dashboard.discovery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One member returned by the discovery query, with the sort keys the next page's cursor is built from. */
public record CandidateRow(
        UUID id,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String countryCode,
        String region,
        String city,
        String gender,
        Integer prefAgeMin,
        Integer prefAgeMax,
        String presenceMode,
        LocalDateTime lastActiveAt,
        /** PENDING_REVIEW / APPROVED / REJECTED: until APPROVED other members see "new user" instead of the name */
        String reviewStatus,
        BigDecimal activeKey,
        BigDecimal createdKey,
        BigDecimal distanceKm,
        String locationSource,
        BigDecimal score) {
}

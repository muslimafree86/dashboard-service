package com.muslimafree.dashboard.discovery;

import com.muslimafree.dashboard.location.LocationSource;
import com.muslimafree.dashboard.location.PlaceResponse;
import com.muslimafree.dashboard.member.Gender;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the Home search bar shows before the member searches (Figma: "ابحث عن انثي · العمر 20 - 45 · مصر · القاهرة"),
 * taken from their partner preferences, plus the limits of each field.
 */
public record SearchDefaults(
        @Schema(description = "Fixed: derived from the member's own gender") Gender lookingForGender,
        Integer ageMin,
        Integer ageMax,
        String country,
        String city,
        @Schema(description = "Where my distances are measured from: DEVICE (shared, fresh), CITY or REGION (centre "
                + "of my city / governorate from my profile); absent = distance search impossible")
        LocationSource locationSource,
        @Schema(description = "My city / governorate when locationSource is CITY or REGION") PlaceResponse locationPlace,
        Limits limits) {

    public record Limits(int minAge, int maxAge, int maxDistanceKm, int maxPageSize) {
    }
}

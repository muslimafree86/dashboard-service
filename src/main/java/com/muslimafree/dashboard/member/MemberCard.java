package com.muslimafree.dashboard.member;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One member card of the Home page (Figma 77:626): photo (or placeholder) with photo count, online dot, name,
 * "مصر ، المعادي ، القاهرة", "ابحث عن : ذكر 31 - 47", "قبل 10 ساعات". {@code id} opens the member profile
 * ({@code GET /api/v1/members/{id}} of user-profile-managment). No e-mail, date of birth, coordinates or score.
 */
public record MemberCard(
        UUID id,
        String firstName,
        String lastName,
        @Schema(description = "Age in years (never the date of birth)") Integer age,
        Location location,
        @Schema(description = "ابحث عن") LookingFor lookingFor,
        Presence presence,
        @Schema(description = "Signed, short-lived link to the main approved photo; absent = placeholder")
        String photoUrl,
        @Schema(description = "Number of approved photos (badge on the card)") int photoCount,
        @Schema(description = "Distance in km: whole km (at least 1) between device positions, rounded to 5 km (at least "
                + "5) when either side is only known by city / governorate; absent when either position is unknown")
        Integer distanceKm,
        @Schema(description = "distanceKm is between city / governorate centres: show it as \"~25 km\"")
        Boolean distanceApproximate) {

    public record Location(String countryCode, String region, String city) {
    }

    public record LookingFor(Gender gender, Integer ageMin, Integer ageMax) {
    }

    /** Same shape and rules as user-profile-managment's {@code Presence}: minute precision. */
    public record Presence(OnlineStatus status, Instant lastActiveAt) {
    }
}

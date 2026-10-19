package com.muslimafree.dashboard.common.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.muslimafree.dashboard.dailypicks.DailyPicks;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code app.*} in application.yml. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Web web,
        @Valid @NotNull Security security,
        @Valid @NotNull Discovery discovery,
        @Valid @NotNull Location location,
        @Valid @NotNull Photos photos,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Interactions interactions,
        @Valid @NotNull Messaging messaging,
        @Valid @NotNull Reports reports,
        @Valid @NotNull DailyPicks dailyPicks) {

    public record Web(List<String> allowedOrigins) {
    }

    public record Security(@NotBlank String issuer, String jwkSetUri, String publicKeyLocation,
            @NotNull Duration sessionCacheTtl) {
    }

    /**
     * @param oppositeGenderOnly   same rule as user-profile-managment's {@code app.profile.opposite-gender-only}
     * @param requireVerifiedEmail same rule as {@code app.profile.require-verified-email-for-visibility}
     * @param onlineWindow         "متصل الان": active within this window (same as {@code app.profile.online-window})
     * @param candidatePool        how many of the most recently active matching members are ranked per search
     */
    public record Discovery(
            boolean oppositeGenderOnly,
            boolean requireVerifiedEmail,
            @NotNull Duration onlineWindow,
            @Min(20) @Max(10_000) int candidatePool,
            @NotNull Duration cursorTtl,
            @Valid @NotNull Similar similar,
            @Valid @NotNull Weights weights) {
    }

    public record Similar(@Min(1) @Max(50) int limit, @Min(0) @Max(20) int ageSpan) {
    }

    /** Relative weights of the relevance signals; only their ratios matter. */
    public record Weights(
            @Min(0) int preferenceFit,
            @Min(0) int reciprocalFit,
            @Min(0) int activity,
            @Min(0) int proximity) {

        public int total() {
            return preferenceFit + reciprocalFit + activity + proximity;
        }
    }

    /**
     * @param gridDecimals       device coordinates are snapped to this many decimals before storage (2 = ~1.1 km)
     * @param deviceMaxAge       older device locations are ignored; the member falls back to their city / governorate
     * @param maxChangesPerDay   device location moves allowed per UTC day (anti-trilateration)
     * @param reconcileInterval  period of the full search-location pass (a safety net)
     * @param syncInterval       period of the incremental pass: places changed in user-profile-managment, device
     *                           locations that just went stale
     */
    public record Location(@Min(1) @Max(4) int gridDecimals, @NotNull Duration deviceMaxAge,
            @Min(1) int maxChangesPerDay, @NotNull Duration reconcileInterval, @NotNull Duration syncInterval) {
    }

    /** @param dailyLikeLimit likes one member may give per UTC day (anti-spam: mass-liking to fish for matches) */
    public record Interactions(@Min(1) int dailyLikeLimit) {
    }

    /** @param maxPerMinute messages one member may send per minute, all conversations together (anti-spam) */
    public record Messaging(@Min(1) int maxPerMinute) {
    }

    /** @param maxPerDay reports one member may file in 24 hours (abuse of the report button) */
    public record Reports(@Min(1) int maxPerDay) {
    }

    /**
     * @param size    members per day
     * @param history a member is not picked again for this many days
     */
    public record DailyPicks(@Min(1) @Max(50) int size, @Min(0) @Max(365) int history) {
    }

    /** Per-member discovery request budget (see {@code DiscoveryRateLimiter}). */
    public record RateLimit(boolean enabled, @Min(1) int perMinute, @Min(1) int perDay) {
    }

    public record Photos(@NotBlank String storage, @NotNull Duration urlTtl, String urlSigningKey,
            Cloudfront cloudfront) {
    }

    public record Cloudfront(String domain, String keyPairId, String privateKey) {
    }
}

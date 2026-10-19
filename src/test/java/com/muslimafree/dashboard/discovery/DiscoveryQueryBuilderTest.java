package com.muslimafree.dashboard.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.location.GeoPoint;
import com.muslimafree.dashboard.location.LocationSource;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.ProfileSnapshot;

class DiscoveryQueryBuilderTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 10, 6, 12, 0);
    private static final UUID VIEWER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final DiscoveryQueryBuilder builder =
            new DiscoveryQueryBuilder(new AppProperties.Weights(45, 35, 10, 10), Duration.ofMinutes(5), 1000);

    private static ProfileSnapshot viewer(ProfileSnapshot.Preferences preferences, BigDecimal lat, BigDecimal lon) {
        return new ProfileSnapshot(VIEWER_ID, "ACTIVE", true, Gender.FEMALE, LocalDate.of(1996, 1, 1), "EG", "القاهرة",
                "المعادي", "SINGLE",
                new ProfileSnapshot.Traits("BACHELOR", null, "NO", "SUNNI", null, null, null),
                preferences, lat, lon, lat == null ? null : AS_OF);
    }

    private static ProfileSnapshot.Preferences noPreferences() {
        return new ProfileSnapshot.Preferences(null, null, null, null, null, null, null, null, null, null, null);
    }

    private static DiscoveryQuery query(ProfileSnapshot viewer, DiscoveryQuery.Filters filters, DiscoverySort sort,
            DiscoveryQuery.Keyset after) {
        GeoPoint center = viewer.deviceLatitude() == null ? null
                : new GeoPoint(viewer.deviceLatitude(), viewer.deviceLongitude(), LocationSource.DEVICE, null);
        return new DiscoveryQuery(viewer, center, AS_OF, Gender.MALE, true, filters, sort, after, 21, null);
    }

    @Test
    void alwaysAppliesTheVisibilityRulesOfTheProfileService() {
        String sql = builder.build(query(viewer(noPreferences(), null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.RELEVANCE, null)).sql();

        assertThat(sql)
                .contains("u.status = 'ACTIVE'")
                .contains("u.email_verified = TRUE")
                .contains("p.onboarding_step = 'COMPLETED'")
                .contains("p.visibility = 'VISIBLE'")
                .contains("p.gender = 'MALE'")
                .contains("p.user_id <> :viewerId")
                .contains("b.blocker_id = :viewerId AND b.blocked_id = p.user_id")
                .contains("b.blocker_id = p.user_id AND b.blocked_id = :viewerId");
    }

    @Test
    void neverPutsValuesIntoTheSqlText() {
        DiscoveryQuery.Filters filters = DiscoveryQuery.Filters.builder().country("EG").city("x' OR '1'='1").build();
        DiscoveryQueryBuilder.Sql sql = builder.build(query(viewer(noPreferences(), null, null), filters,
                DiscoverySort.RELEVANCE, null));

        assertThat(sql.sql()).doesNotContain("x'", "'EG'", VIEWER_ID.toString(), "SUNNI", "القاهرة");
        assertThat(sql.params()).containsValue("x' OR '1'='1").containsValue("EG");
    }

    @Test
    void preferenceFitOnlyCountsStatedPreferences() {
        String none = builder.build(query(viewer(noPreferences(), null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.RELEVANCE, null)).sql();
        ProfileSnapshot.Preferences two = new ProfileSnapshot.Preferences(25, 35, null, null, null, null, "NO", null,
                null, null, null);
        String some = builder.build(query(viewer(two, null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.RELEVANCE, null)).sql();

        assertThat(none).contains("45 * 1 +");
        assertThat(some).contains(") / 2)").contains("p.smoking = :fit");
    }

    @Test
    void mutualAddsConditionsInBothDirections() {
        ProfileSnapshot.Preferences wanted = new ProfileSnapshot.Preferences(28, 40, "EG", null, "BACHELOR", null, "NO",
                "SUNNI", null, null, null);
        DiscoveryQuery.Filters mutual = DiscoveryQuery.Filters.builder().mutualOnly(true).build();
        String sql = builder.build(query(viewer(wanted, null, null), mutual, DiscoverySort.RELEVANCE, null)).sql();

        assertThat(sql)
                .contains("(p.sect IS NULL OR p.sect = :m")
                .contains("(p.pref_sect IS NULL OR p.pref_sect = :m")
                .contains("(p.pref_age_min IS NULL OR p.pref_age_min <= :mutualViewerAge)")
                .contains("p.pref_min_education IS NULL OR FIELD(p.pref_min_education");
    }

    @Test
    void distanceUsesABoundingBoxAndAnInnerJoinOnlyWhenRequired() {
        ProfileSnapshot located = viewer(noPreferences(), new BigDecimal("30.00"), new BigDecimal("31.00"));
        String feed = builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.RELEVANCE, null)).sql();
        DiscoveryQuery.Filters radius = DiscoveryQuery.Filters.builder().radiusKm(10).build();
        String nearby = builder.build(query(located, radius, DiscoverySort.RELEVANCE, null)).sql();

        assertThat(feed).contains("LEFT JOIN dashboard_search_locations").doesNotContain("s.latitude BETWEEN");
        assertThat(nearby).contains("    JOIN dashboard_search_locations s").doesNotContain("LEFT JOIN")
                .contains("s.latitude BETWEEN :minLat AND :maxLat")
                .contains("s.longitude BETWEEN :minLon AND :maxLon")
                // the radius applies to the rounded distance the card shows, never to the exact one
                .contains("GREATEST(1, ROUND(ROUND(")
                .contains("<= CASE WHEN s.source <> 'DEVICE' THEN GREATEST(5, :radiusKm) ELSE :radiusKm END")
                .doesNotContain(", 3) <= :radiusKm");
    }

    @Test
    void nearestPoolsTheClosestMembersNotTheMostActive() {
        ProfileSnapshot located = viewer(noPreferences(), new BigDecimal("30.00"), new BigDecimal("31.00"));
        DiscoveryQuery.Keyset after = new DiscoveryQuery.Keyset(BigDecimal.TEN, BigDecimal.ONE, VIEWER_ID.toString());
        String sql = builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.NEAREST, after)).sql();

        assertThat(sql).contains("    JOIN dashboard_search_locations s").doesNotContain("LEFT JOIN")
                .contains(" >= :poolAfterDistance")
                .containsPattern("ORDER BY CASE WHEN s\\.source = 'DEVICE' .* ASC, u\\.last_active_at DESC, u\\.id DESC\\s+LIMIT :poolSize");
    }

    @Test
    void withoutAViewerLocationNoDistanceIsComputed() {
        String sql = builder.build(query(viewer(noPreferences(), null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.RELEVANCE, null)).sql();

        assertThat(sql).contains("NULL AS distance_km").doesNotContain("dashboard_search_locations");
    }

    @Test
    void keysetFollowsTheSortDirection() {
        DiscoveryQuery.Keyset after = new DiscoveryQuery.Keyset(BigDecimal.TEN, BigDecimal.ONE, VIEWER_ID.toString());
        ProfileSnapshot located = viewer(noPreferences(), BigDecimal.ONE, BigDecimal.ONE);

        assertThat(builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.RELEVANCE, after)).sql())
                .contains("c.score < :afterPrimary OR (c.score = :afterPrimary AND c.active_key < :afterSecondary)")
                .contains("ORDER BY c.score DESC, c.active_key DESC, c.id ASC");
        assertThat(builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.NEAREST, after)).sql())
                .contains("c.distance_km > :afterPrimary")
                .contains("ORDER BY c.distance_km ASC, c.score DESC, c.id ASC");
        assertThat(builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.RECENTLY_ACTIVE, after))
                .sql()).contains("ORDER BY c.active_key DESC, c.score DESC, c.id ASC");
    }

    @Test
    void boundingBoxSkipsTheLongitudeBandAcrossTheAntimeridian() {
        ProfileSnapshot nearDateLine = viewer(noPreferences(), new BigDecimal("0"), new BigDecimal("179.99"));
        DiscoveryQuery.Filters radius = DiscoveryQuery.Filters.builder().radiusKm(50).build();

        assertThat(builder.build(query(nearDateLine, radius, DiscoverySort.RELEVANCE, null)).sql())
                .contains("s.latitude BETWEEN").doesNotContain("s.longitude BETWEEN");
    }

    @Test
    void ranksOnlyABoundedPoolWalkedInIndexOrder() {
        DiscoveryQueryBuilder.Sql sql = builder.build(query(viewer(noPreferences(), null, null),
                DiscoveryQuery.Filters.none(), DiscoverySort.RELEVANCE, null));

        assertThat(sql.sql()).contains("ORDER BY u.last_active_at DESC, u.id DESC\n    LIMIT :poolSize\n  ) pool");
        assertThat(sql.params()).containsEntry("poolSize", 1000);
    }

    @Test
    void hiddenMembersAreLeftOutUnlessAsked() {
        ProfileSnapshot v = viewer(noPreferences(), null, null);
        String feed = builder.build(query(v, DiscoveryQuery.Filters.none(), DiscoverySort.RELEVANCE, null)).sql();
        String check = builder.build(query(v, DiscoveryQuery.Filters.builder().includeHidden(true)
                .memberIds(java.util.Set.of(VIEWER_ID)).build(), DiscoverySort.RELEVANCE, null)).sql();

        assertThat(feed).contains("h.viewer_id = :viewerId AND h.hidden_id = p.user_id");
        assertThat(check).doesNotContain("dashboard_hidden_members").contains("p.user_id IN (:memberIds)");
    }

    @Test
    void advancedFiltersAreStrictAndParameterized() {
        DiscoveryQuery.Filters filters = DiscoveryQuery.Filters.builder()
                .maritalStatuses(java.util.Set.of("SINGLE", "DIVORCED")).minEducation("BACHELOR")
                .sects(java.util.Set.of("SUNNI")).smoking(java.util.Set.of("NO")).children("NONE").nationality("EG")
                .withPhoto(true).build();
        DiscoveryQueryBuilder.Sql sql = builder.build(query(viewer(noPreferences(), null, null), filters,
                DiscoverySort.RELEVANCE, null));

        assertThat(sql.sql())
                .contains("p.marital_status IN (:in)")
                .contains("FIELD(p.education, 'SECONDARY', 'DIPLOMA', 'BACHELOR', 'MASTER', 'DOCTORATE') >= :minEducation")
                .contains("p.sect IN (:in2)")
                .contains("p.smoking IN (:in3)")
                .contains("p.children = :children")
                .contains("p.nationality = :nationality")
                .contains("ph.status = 'APPROVED'")
                .doesNotContain("'SINGLE'", "'NONE'");
        assertThat(sql.params()).containsEntry("in", java.util.List.of("DIVORCED", "SINGLE")).containsEntry("minEducation", 3);
    }

    @Test
    void newestTakesThePoolByJoinDate() {
        String sql = builder.build(query(viewer(noPreferences(), null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.NEWEST, null)).sql();

        assertThat(sql).contains("ORDER BY u.created_at DESC, u.id DESC\n    LIMIT :poolSize")
                .contains("ORDER BY c.created_key DESC, c.score DESC, c.id ASC");
    }

    @Test
    void proximityUsesDistanceWhenBothPositionsAreKnown() {
        ProfileSnapshot located = viewer(noPreferences(), new BigDecimal("30.00"), new BigDecimal("31.00"));
        String sql = builder.build(query(located, DiscoveryQuery.Filters.none(), DiscoverySort.RELEVANCE, null)).sql();

        assertThat(sql).contains("CASE WHEN s.latitude IS NULL THEN CASE WHEN p.country_code = :proxCountry")
                .contains(", 3) <= 10 THEN 1 WHEN").contains("s.source AS location_source");
    }

    @Test
    void zeroWeightsGiveAConstantScore() {
        DiscoveryQueryBuilder flat =
                new DiscoveryQueryBuilder(new AppProperties.Weights(0, 0, 0, 0), Duration.ofMinutes(5), 1000);

        assertThat(flat.build(query(viewer(noPreferences(), null, null), DiscoveryQuery.Filters.none(),
                DiscoverySort.RELEVANCE, null)).sql()).contains("0 AS score");
    }
}

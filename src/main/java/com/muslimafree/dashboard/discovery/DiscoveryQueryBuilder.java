package com.muslimafree.dashboard.discovery;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.util.SqlParams;
import com.muslimafree.dashboard.location.GeoPoint;
import com.muslimafree.dashboard.member.EducationLevel;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.VisibilityRule;

/**
 * Turns a {@link DiscoveryQuery} into ONE SQL statement that does all of discovery in the database: eligibility,
 * filters, distance, relevance score, ordering and keyset pagination. Only the page itself (size + 1 rows) leaves the
 * database.
 *
 * <h2>Two stages (README "Performance")</h2>
 * Scoring is per candidate, so scoring every eligible member costs time proportional to the number of members (about
 * half a second for 40 000 on MariaDB, measured) on every request. The statement therefore works like a recommender:
 * <ol>
 * <li><b>candidate pool</b> - eligibility and all filters, then the {@code poolSize} most recently active matches. That
 * order is an index ({@code ix_users_last_active}), so the database stops reading as soon as the pool is full.</li>
 * <li><b>ranking</b> - score, distance and sort keys for the pool only, then keyset pagination.</li>
 * </ol>
 * {@code NEAREST} is the exception: its pool is the {@code poolSize} closest matches (from the page's keyset position
 * on), so a nearby member is never missed because they were less active than members far away. The service keeps that
 * cheap by searching growing circles ({@code DiscoveryService}).
 *
 * <h2>Distances (README "Nearby")</h2>
 * Members only ever see a rounded distance: whole km (at least 1) between two device positions, else 5 km steps (at
 * least 5). The radius filter, the {@code NEAREST} order and the cursor all use that same rounded value, so none of them
 * tells more about a member's position than their card does. The exact distance only feeds the proximity score.
 * <pre>
 * SELECT ... FROM (
 *     SELECT columns, distance_km, score [, similarity]
 *     FROM (SELECT p.user_id ... WHERE eligibility AND filters ORDER BY u.last_active_at DESC LIMIT pool) pool
 *     JOIN user_profiles p JOIN users u [LEFT|INNER JOIN dashboard_search_locations s]
 * ) c
 * [WHERE keyset]
 * ORDER BY sort keys, c.id
 * LIMIT size + 1
 * </pre>
 *
 * <h2>Relevance (README "Matching")</h2>
 * The score compares the same nine attributes as the green/red tables of the member profile page (user-profile-managment
 * {@code CompatibilityCalculator}): age, country, education (minimum level), children, smoking, sect, employment sector,
 * appearance, body shape. Each signal is 0..1:
 * <ul>
 * <li><b>preference fit</b> - how well the candidate matches what the viewer looks for: mean over the preferences the
 * viewer stated; 1 per match, 0.5 when the candidate has not filled the attribute in, 0 per contradiction. 1 when the
 * viewer stated nothing ("اي" everywhere).</li>
 * <li><b>reciprocal fit</b> - the same, in the other direction: does the viewer match what the candidate looks for
 * (exactly the green/red cells the viewer sees on the candidate's profile).</li>
 * <li><b>activity</b> - online now 1, last 24 h 0.7, last 7 days 0.4, last 30 days 0.1, else 0.</li>
 * <li><b>proximity</b> - by distance when both positions are known (within 10 km 1 ... beyond 300 km 0), else same
 * governorate 1, same country 0.5, else 0.</li>
 * </ul>
 * score = round(1000 x weighted mean), weights from {@code app.discovery.weights}. It is an integer so that keyset
 * comparisons are exact.
 */
public class DiscoveryQueryBuilder {

    /** Mean Earth radius used by the haversine distance. */
    private static final double EARTH_RADIUS_KM = 6371.0088;
    /** Kilometres per degree of latitude, for the bounding-box pre-filter. */
    private static final double KM_PER_DEGREE = 111.045;
    /** Shown distances between approximate positions (city / governorate centres) are rounded to this step. */
    public static final int APPROXIMATE_STEP_KM = 5;
    /**
     * The radius applies to the rounded distance, so the box around it is a little wider: a member 10.4 km away is
     * shown "10 km" and an approximate 12.4 km "10 km".
     */
    private static final int BOX_MARGIN_KM = 3;

    private static final String ACTIVE_KEY =
            "TIMESTAMPDIFF(MICROSECOND, TIMESTAMP '1970-01-01 00:00:00', "
                    + "COALESCE(u.last_active_at, TIMESTAMP '1970-01-01 00:00:00'))";
    private static final String CREATED_KEY =
            "TIMESTAMPDIFF(MICROSECOND, TIMESTAMP '1970-01-01 00:00:00', u.created_at)";

    private final AppProperties.Weights weights;
    private final Duration onlineWindow;
    private final int poolSize;

    public DiscoveryQueryBuilder(AppProperties.Weights weights, Duration onlineWindow, int poolSize) {
        this.weights = weights;
        this.onlineWindow = onlineWindow;
        this.poolSize = poolSize;
    }

    public record Sql(String sql, Map<String, Object> params) {
    }

    public Sql build(DiscoveryQuery query) {
        SqlParams params = new SqlParams();
        ProfileSnapshot viewer = query.viewer();
        DiscoveryQuery.Filters filters = query.filters();
        LocalDate today = query.asOf().toLocalDate();
        GeoPoint center = query.center();
        boolean withDistance = center != null;
        String distance = withDistance ? distance(center, params) : "NULL";
        String shown = withDistance ? shownDistance(center, distance) : "NULL";
        boolean nearest = query.similarTo() == null && query.sort() == DiscoverySort.NEAREST;

        // stage 1 - candidate pool: eligibility and every filter, most recently active (or joined) first, at most
        // poolSize rows
        StringBuilder sql = new StringBuilder(4096);
        sql.append("SELECT c.* FROM (\n  SELECT p.user_id AS id, p.first_name, p.last_name, p.date_of_birth, ")
                .append("p.country_code, p.region, p.city, p.gender, p.pref_age_min, p.pref_age_max, ")
                .append("p.presence_mode, u.last_active_at, u.review_status,\n    ")
                .append(ACTIVE_KEY).append(" AS active_key,\n    ")
                .append(CREATED_KEY).append(" AS created_key,\n    ")
                .append(shown).append(" AS distance_km,\n    ")
                .append(withDistance ? "s.source" : "NULL").append(" AS location_source,\n    ")
                .append(score(viewer, distance, today, query.asOf(), params)).append(" AS score");
        if (query.similarTo() != null) {
            sql.append(",\n    ").append(similarity(query.similarTo(), params)).append(" AS similarity");
        }
        sql.append("\n  FROM (\n    SELECT p.user_id FROM user_profiles p\n    JOIN users u ON u.id = p.user_id\n");
        boolean poolNeedsLocation = withDistance && (filters.radiusKm() != null || nearest);
        if (poolNeedsLocation) {
            sql.append("    JOIN dashboard_search_locations s ON s.user_id = p.user_id\n");
        }
        List<String> where = new ArrayList<>();
        eligibility(query, params, where);
        filters(query, today, params, where);
        if (filters.mutualOnly()) {
            mutual(viewer, today, params, where);
        }
        if (withDistance && filters.radiusKm() != null) {
            boundingBox(center, filters.radiusKm() + BOX_MARGIN_KM, params, where);
            String radius = params.add("radiusKm", filters.radiusKm());
            // approximate distances come in 5 km steps from 5: a smaller radius still finds the same city
            where.add(shown + " <= CASE WHEN " + approximate(center) + " THEN GREATEST(" + APPROXIMATE_STEP_KM + ", "
                    + radius + ") ELSE " + radius + " END");
        }
        if (nearest && query.after() != null) {
            // nothing before the previous page's last distance can follow it
            where.add(shown + " >= " + params.add("poolAfterDistance", query.after().primary()));
        }
        // (last_active_at, id) is the order of ix_users_last_active, (created_at, id) that of ix_users_created: the
        // database walks the index from the most recent member and stops as soon as the pool is full, instead of
        // reading every eligible member. NEAREST pools the closest members instead (within the circle given).
        boolean newest = query.similarTo() == null && query.sort() == DiscoverySort.NEWEST;
        sql.append("    WHERE ").append(String.join("\n      AND ", where))
                .append(nearest ? "\n    ORDER BY " + shown + " ASC, u.last_active_at DESC, u.id DESC"
                        : newest ? "\n    ORDER BY u.created_at DESC, u.id DESC"
                        : "\n    ORDER BY u.last_active_at DESC, u.id DESC")
                .append("\n    LIMIT ")
                .append(params.add("poolSize", poolSize)).append("\n  ) pool\n");

        // stage 2 - rank the pool: display columns, distance and score for at most poolSize rows
        sql.append("  JOIN user_profiles p ON p.user_id = pool.user_id\n  JOIN users u ON u.id = p.user_id\n");
        if (withDistance) {
            sql.append(poolNeedsLocation ? "  JOIN" : "  LEFT JOIN")
                    .append(" dashboard_search_locations s ON s.user_id = p.user_id\n");
        }
        sql.append(") c");

        Order order = order(query);
        if (query.after() != null && query.similarTo() == null) {
            sql.append("\nWHERE ").append(keyset(order, query.after(), params));
        }
        sql.append("\nORDER BY ").append(order.sql()).append("\nLIMIT ").append(params.add("limit", query.limit()));
        return new Sql(sql.toString(), params.values());
    }

    // ------------------------------------------------------------------------------------------------ eligibility

    /**
     * Exactly user-profile-managment's {@code MemberVisibilityPolicy}, so that every card opens: the member is ACTIVE,
     * verified, finished onboarding, VISIBLE, of the sought gender, not the viewer, and neither blocked the other.
     * On top of that, members the viewer marked "not interested" are left out (unless the filters ask for them).
     */
    private static void eligibility(DiscoveryQuery query, SqlParams params, List<String> where) {
        String viewerId = params.add("viewerId", query.viewer().id().toString());
        VisibilityRule.appendTo(viewerId, query.candidateGender(), query.requireVerifiedEmail(), where);
        if (!query.filters().includeHidden()) {
            where.add("NOT EXISTS (SELECT 1 FROM dashboard_hidden_members h WHERE h.viewer_id = " + viewerId
                    + " AND h.hidden_id = p.user_id)");
        }
    }

    private static void filters(DiscoveryQuery query, LocalDate today, SqlParams params, List<String> where) {
        DiscoveryQuery.Filters f = query.filters();
        if (f.memberIds() != null) {
            where.add(f.memberIds().isEmpty() ? "FALSE"
                    : "p.user_id IN (" + params.add("memberIds", f.memberIds().stream().map(UUID::toString).toList()) + ")");
        }
        if (f.excludeId() != null) {
            where.add("p.user_id <> " + params.add("excludeId", f.excludeId().toString()));
        }
        if (f.bornOnOrBefore() != null) {
            where.add("p.date_of_birth <= " + params.add("bornOnOrBefore", f.bornOnOrBefore()));
        }
        if (f.bornAfter() != null) {
            where.add("p.date_of_birth > " + params.add("bornAfter", f.bornAfter()));
        }
        if (f.countryCode() != null) {
            where.add("p.country_code = " + params.add("country", f.countryCode()));
        }
        if (f.city() != null) {
            String city = params.add("city", f.city());
            where.add("(p.city = " + city + " OR p.region = " + city + ")");
        }
        if (f.activeSince() != null) {
            where.add("u.last_active_at >= " + params.add("activeSince", f.activeSince()));
        }
        anyOf("p.marital_status", f.maritalStatuses(), params, where);
        if (f.minEducation() != null) {
            where.add(EducationLevel.sqlRank("p.education") + " >= "
                    + params.add("minEducation", EducationLevel.rankOf(f.minEducation())));
        }
        anyOf("p.sect", f.sects(), params, where);
        anyOf("p.smoking", f.smoking(), params, where);
        if (f.children() != null) {
            where.add("p.children = " + params.add("children", f.children()));
        }
        if (f.nationality() != null) {
            where.add("p.nationality = " + params.add("nationality", f.nationality()));
        }
        if (f.notLiked()) {
            where.add("NOT EXISTS (SELECT 1 FROM dashboard_likes lk WHERE lk.liker_id = "
                    + params.add("likerId", query.viewer().id().toString()) + " AND lk.liked_id = p.user_id)");
        }
        if (f.notPickedSince() != null) {
            where.add("NOT EXISTS (SELECT 1 FROM dashboard_daily_picks dp WHERE dp.member_id = "
                    + params.add("pickedFor", query.viewer().id().toString()) + " AND dp.picked_id = p.user_id"
                    + " AND dp.pick_date >= " + params.add("pickedSince", f.notPickedSince()) + ")");
        }
        if (f.withPhoto()) {
            where.add("EXISTS (SELECT 1 FROM profile_photos ph WHERE ph.user_id = p.user_id AND ph.status = 'APPROVED')");
        }
    }

    /** {@code column IN (...)}; NULL (not filled in) never matches. */
    private static void anyOf(String column, Set<String> values, SqlParams params, List<String> where) {
        if (!values.isEmpty()) {
            where.add(column + " IN (" + params.add("in", values.stream().sorted().toList()) + ")");
        }
    }

    /**
     * "الشركاء": no attribute contradicts a preference, in either direction. An attribute a member has not filled in is
     * not a contradiction: several of them have no input screen yet, and treating them as one would empty the tab.
     */
    private static void mutual(ProfileSnapshot viewer, LocalDate today, SqlParams params, List<String> where) {
        ProfileSnapshot.Preferences wanted = viewer.preferences();
        // the candidate is what the viewer looks for
        String ageRange = ageRange(wanted.ageMin(), wanted.ageMax(), today, params, "mutualAge");
        if (ageRange != null) {
            where.add(ageRange);
        }
        equalOrUnknown("p.country_code", wanted.countryCode(), params, where);
        if (wanted.minEducation() != null) {
            where.add("(p.education IS NULL OR " + EducationLevel.sqlRank("p.education") + " >= "
                    + params.add("mutualEdu", EducationLevel.rankOf(wanted.minEducation())) + ")");
        }
        equalOrUnknown("p.children", wanted.children(), params, where);
        equalOrUnknown("p.smoking", wanted.smoking(), params, where);
        equalOrUnknown("p.sect", wanted.sect(), params, where);
        equalOrUnknown("p.employment_sector", wanted.employmentSector(), params, where);
        equalOrUnknown("p.appearance", wanted.appearance(), params, where);
        equalOrUnknown("p.body_shape", wanted.bodyShape(), params, where);

        // the viewer is what the candidate looks for
        Integer age = viewer.ageOn(today);
        if (age != null) {
            String a = params.add("mutualViewerAge", age);
            where.add("(p.pref_age_min IS NULL OR p.pref_age_min <= " + a + ")");
            where.add("(p.pref_age_max IS NULL OR p.pref_age_max >= " + a + ")");
        }
        ProfileSnapshot.Traits mine = viewer.traits();
        preferenceAccepts("p.pref_country_code", viewer.countryCode(), params, where);
        if (mine.education() != null) {
            where.add("(p.pref_min_education IS NULL OR " + EducationLevel.sqlRank("p.pref_min_education") + " <= "
                    + params.add("mutualViewerEdu", EducationLevel.rankOf(mine.education())) + ")");
        }
        preferenceAccepts("p.pref_children", mine.children(), params, where);
        preferenceAccepts("p.pref_smoking", mine.smoking(), params, where);
        preferenceAccepts("p.pref_sect", mine.sect(), params, where);
        preferenceAccepts("p.pref_employment_sector", mine.employmentSector(), params, where);
        preferenceAccepts("p.pref_appearance", mine.appearance(), params, where);
        preferenceAccepts("p.pref_body_shape", mine.bodyShape(), params, where);
    }

    private static void equalOrUnknown(String column, String wanted, SqlParams params, List<String> where) {
        if (wanted != null) {
            where.add("(" + column + " IS NULL OR " + column + " = " + params.add("m", wanted) + ")");
        }
    }

    private static void preferenceAccepts(String prefColumn, String viewerValue, SqlParams params, List<String> where) {
        if (viewerValue != null) {
            where.add("(" + prefColumn + " IS NULL OR " + prefColumn + " = " + params.add("m", viewerValue) + ")");
        }
    }

    // ------------------------------------------------------------------------------------------------ distance

    /**
     * Haversine from the search centre to the candidate's search position ({@code dashboard_search_locations}), in km to
     * three decimals so keyset comparisons are exact.
     */
    private static String distance(GeoPoint center, SqlParams params) {
        String lat = params.add("centerLat", center.latitude());
        String lon = params.add("centerLon", center.longitude());
        return "ROUND(" + EARTH_RADIUS_KM + " * 2 * ASIN(LEAST(1, SQRT("
                + "POWER(SIN(RADIANS(s.latitude - " + lat + ") / 2), 2) + COS(RADIANS(" + lat + ")) * "
                + "COS(RADIANS(s.latitude)) * POWER(SIN(RADIANS(s.longitude - " + lon + ") / 2), 2)))), 3)";
    }

    /** Whether a row's distance is approximate: measured from a place centre, or to a member's place centre. */
    private static String approximate(GeoPoint center) {
        return center.source().isApproximate() ? "TRUE" : "s.source <> 'DEVICE'";
    }

    /**
     * The distance a card shows (README "Nearby"): whole km, at least 1, between two device positions; else rounded to
     * {@value #APPROXIMATE_STEP_KM} km, at least {@value #APPROXIMATE_STEP_KM}.
     */
    private static String shownDistance(GeoPoint center, String distance) {
        String step = Integer.toString(APPROXIMATE_STEP_KM);
        String approximateKm = "GREATEST(" + step + ", ROUND(" + distance + " / " + step + ") * " + step + ")";
        if (center.source().isApproximate()) {
            return approximateKm;
        }
        return "CASE WHEN s.source = 'DEVICE' THEN GREATEST(1, ROUND(" + distance + ")) ELSE " + approximateKm + " END";
    }

    /**
     * Index-friendly pre-filter ({@code ix_dashboard_search_locations_lat_lon}): the circle lies inside this box, so the exact
     * distance is only computed for members already close by. The longitude band is skipped near the poles and across
     * the antimeridian, where it would not be a single range.
     */
    private static void boundingBox(GeoPoint center, int radiusKm, SqlParams params, List<String> where) {
        double lat = center.latitude().doubleValue();
        double lon = center.longitude().doubleValue();
        double dLat = radiusKm / KM_PER_DEGREE;
        where.add("s.latitude BETWEEN " + params.add("minLat", lat - dLat) + " AND " + params.add("maxLat", lat + dLat));
        double cos = Math.cos(Math.toRadians(lat));
        if (cos > 0.01) {
            double dLon = radiusKm / (KM_PER_DEGREE * cos);
            if (lon - dLon >= -180 && lon + dLon <= 180) {
                where.add("s.longitude BETWEEN " + params.add("minLon", lon - dLon) + " AND "
                        + params.add("maxLon", lon + dLon));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ relevance

    private String score(ProfileSnapshot viewer, String distance, LocalDate today, LocalDateTime asOf, SqlParams params) {
        if (weights.total() == 0) {
            return "0";
        }
        return "ROUND(1000 * ("
                + weights.preferenceFit() + " * " + preferenceFit(viewer, today, params) + " + "
                + weights.reciprocalFit() + " * " + reciprocalFit(viewer, today, params) + " + "
                + weights.activity() + " * " + activity(asOf, params) + " + "
                + weights.proximity() + " * " + proximity(viewer, distance, params)
                + ") / " + weights.total() + ")";
    }

    /** Candidate vs. what the viewer looks for; the viewer's preferences are constants, so only stated ones count. */
    private static String preferenceFit(ProfileSnapshot viewer, LocalDate today, SqlParams params) {
        ProfileSnapshot.Preferences wanted = viewer.preferences();
        List<String> terms = new ArrayList<>();
        String ageRange = ageRange(wanted.ageMin(), wanted.ageMax(), today, params, "fitAge");
        if (ageRange != null) {
            terms.add("CASE WHEN " + ageRange + " THEN 1 ELSE 0 END");
        }
        fit("p.country_code", wanted.countryCode(), params, terms);
        if (wanted.minEducation() != null) {
            terms.add("CASE WHEN p.education IS NULL THEN 0.5 WHEN " + EducationLevel.sqlRank("p.education") + " >= "
                    + params.add("fitEdu", EducationLevel.rankOf(wanted.minEducation())) + " THEN 1 ELSE 0 END");
        }
        fit("p.children", wanted.children(), params, terms);
        fit("p.smoking", wanted.smoking(), params, terms);
        fit("p.sect", wanted.sect(), params, terms);
        fit("p.employment_sector", wanted.employmentSector(), params, terms);
        fit("p.appearance", wanted.appearance(), params, terms);
        fit("p.body_shape", wanted.bodyShape(), params, terms);
        if (terms.isEmpty()) {
            return "1";
        }
        return "((" + String.join(" + ", terms) + ") / " + terms.size() + ")";
    }

    private static void fit(String column, String wanted, SqlParams params, List<String> terms) {
        if (wanted != null) {
            terms.add("CASE WHEN " + column + " IS NULL THEN 0.5 WHEN " + column + " = " + params.add("fit", wanted)
                    + " THEN 1 ELSE 0 END");
        }
    }

    /**
     * Viewer vs. what the candidate looks for. The candidate's preferences vary per row, so each term is 0 when the
     * candidate stated nothing and the divisor counts, per row, the preferences the candidate did state.
     */
    private static String reciprocalFit(ProfileSnapshot viewer, LocalDate today, SqlParams params) {
        ProfileSnapshot.Traits mine = viewer.traits();
        List<String> terms = new ArrayList<>();
        List<String> stated = new ArrayList<>();

        Integer age = viewer.ageOn(today);
        String ageStated = "(p.pref_age_min IS NOT NULL OR p.pref_age_max IS NOT NULL)";
        stated.add(ageStated);
        if (age == null) {
            terms.add("CASE WHEN " + ageStated + " THEN 0.5 ELSE 0 END");
        } else {
            String a = params.add("recAge", age);
            terms.add("CASE WHEN NOT " + ageStated + " THEN 0 WHEN (p.pref_age_min IS NULL OR p.pref_age_min <= " + a
                    + ") AND (p.pref_age_max IS NULL OR p.pref_age_max >= " + a + ") THEN 1 ELSE 0 END");
        }
        reciprocal("p.pref_country_code", viewer.countryCode(), params, terms, stated);
        stated.add("(p.pref_min_education IS NOT NULL)");
        if (mine.education() == null) {
            terms.add("CASE WHEN p.pref_min_education IS NULL THEN 0 ELSE 0.5 END");
        } else {
            terms.add("CASE WHEN p.pref_min_education IS NULL THEN 0 WHEN "
                    + EducationLevel.sqlRank("p.pref_min_education") + " <= "
                    + params.add("recEdu", EducationLevel.rankOf(mine.education())) + " THEN 1 ELSE 0 END");
        }
        reciprocal("p.pref_children", mine.children(), params, terms, stated);
        reciprocal("p.pref_smoking", mine.smoking(), params, terms, stated);
        reciprocal("p.pref_sect", mine.sect(), params, terms, stated);
        reciprocal("p.pref_employment_sector", mine.employmentSector(), params, terms, stated);
        reciprocal("p.pref_appearance", mine.appearance(), params, terms, stated);
        reciprocal("p.pref_body_shape", mine.bodyShape(), params, terms, stated);

        String count = "(" + String.join(" + ", stated) + ")";
        return "(CASE WHEN " + count + " = 0 THEN 1 ELSE (" + String.join(" + ", terms) + ") / " + count + " END)";
    }

    private static void reciprocal(String prefColumn, String viewerValue, SqlParams params, List<String> terms,
            List<String> stated) {
        stated.add("(" + prefColumn + " IS NOT NULL)");
        if (viewerValue == null) {
            terms.add("CASE WHEN " + prefColumn + " IS NULL THEN 0 ELSE 0.5 END");
        } else {
            terms.add("CASE WHEN " + prefColumn + " IS NULL THEN 0 WHEN " + prefColumn + " = "
                    + params.add("rec", viewerValue) + " THEN 1 ELSE 0 END");
        }
    }

    private String activity(LocalDateTime asOf, SqlParams params) {
        return "CASE WHEN u.last_active_at >= " + params.add("actOnline", asOf.minus(onlineWindow))
                + " THEN 1 WHEN u.last_active_at >= " + params.add("actDay", asOf.minusDays(1))
                + " THEN 0.7 WHEN u.last_active_at >= " + params.add("actWeek", asOf.minusDays(7))
                + " THEN 0.4 WHEN u.last_active_at >= " + params.add("actMonth", asOf.minusDays(30))
                + " THEN 0.1 ELSE 0 END";
    }

    /**
     * Distance when both positions are known: within 10 km 1, 25 km 0.8, 50 km 0.6, 100 km 0.4, 300 km 0.2, else 0.
     * Otherwise the profile's place: same governorate 1, same country 0.5.
     */
    private static String proximity(ProfileSnapshot viewer, String distance, SqlParams params) {
        String byPlace = proximityByPlace(viewer, params);
        if ("NULL".equals(distance)) {
            return byPlace;
        }
        return "CASE WHEN s.latitude IS NULL THEN " + byPlace + " WHEN " + distance + " <= 10 THEN 1 WHEN " + distance
                + " <= 25 THEN 0.8 WHEN " + distance + " <= 50 THEN 0.6 WHEN " + distance + " <= 100 THEN 0.4 WHEN "
                + distance + " <= 300 THEN 0.2 ELSE 0 END";
    }

    private static String proximityByPlace(ProfileSnapshot viewer, SqlParams params) {
        if (viewer.countryCode() == null) {
            return "0";
        }
        String country = params.add("proxCountry", viewer.countryCode());
        if (viewer.region() == null) {
            return "CASE WHEN p.country_code = " + country + " THEN 0.5 ELSE 0 END";
        }
        return "CASE WHEN p.country_code = " + country + " AND p.region = " + params.add("proxRegion", viewer.region())
                + " THEN 1 WHEN p.country_code = " + country + " THEN 0.5 ELSE 0 END";
    }

    /** "اعضاء مشابهون": how many of the reference member's attributes the candidate shares. */
    private static String similarity(ProfileSnapshot reference, SqlParams params) {
        ProfileSnapshot.Traits t = reference.traits();
        List<String> terms = new ArrayList<>();
        same("p.region", reference.region(), params, terms);
        same("p.marital_status", reference.maritalStatus(), params, terms);
        same("p.education", t.education(), params, terms);
        same("p.children", t.children(), params, terms);
        same("p.smoking", t.smoking(), params, terms);
        same("p.sect", t.sect(), params, terms);
        same("p.employment_sector", t.employmentSector(), params, terms);
        same("p.appearance", t.appearance(), params, terms);
        same("p.body_shape", t.bodyShape(), params, terms);
        return terms.isEmpty() ? "0" : "(" + String.join(" + ", terms) + ")";
    }

    private static void same(String column, String value, SqlParams params, List<String> terms) {
        if (value != null) {
            terms.add("COALESCE(" + column + " = " + params.add("sim", value) + ", 0)");
        }
    }

    /** Age between min and max (either may be open) as a date-of-birth range, or {@code null} when both are open. */
    private static String ageRange(Integer min, Integer max, LocalDate today, SqlParams params, String name) {
        List<String> parts = new ArrayList<>();
        if (min != null) {
            parts.add("p.date_of_birth <= " + params.add(name + "Min", today.minusYears(min)));
        }
        if (max != null) {
            parts.add("p.date_of_birth > " + params.add(name + "Max", today.minusYears(max + 1L)));
        }
        return parts.isEmpty() ? null : "(" + String.join(" AND ", parts) + ")";
    }

    // ------------------------------------------------------------------------------------------------ ordering

    /** Primary and secondary sort keys (column, descending?); the member id always closes the order, ascending. */
    record Order(String primary, boolean primaryDesc, String secondary, boolean secondaryDesc, String tertiary) {

        String sql() {
            return primary + (primaryDesc ? " DESC, " : " ASC, ") + secondary + (secondaryDesc ? " DESC, " : " ASC, ")
                    + (tertiary == null ? "" : tertiary + " DESC, ") + "c.id ASC";
        }
    }

    static Order order(DiscoveryQuery query) {
        if (query.similarTo() != null) {
            return new Order("c.similarity", true, "c.score", true, "c.active_key");
        }
        return switch (query.sort()) {
            case RELEVANCE -> new Order("c.score", true, "c.active_key", true, null);
            case RECENTLY_ACTIVE -> new Order("c.active_key", true, "c.score", true, null);
            case NEAREST -> new Order("c.distance_km", false, "c.score", true, null);
            case NEWEST -> new Order("c.created_key", true, "c.score", true, null);
        };
    }

    /** Rows strictly after the given position in {@code order}: (k1, k2, id) compared lexicographically. */
    private static String keyset(Order order, DiscoveryQuery.Keyset after, SqlParams params) {
        String k1 = params.add("afterPrimary", after.primary());
        String k2 = params.add("afterSecondary", after.secondary());
        String id = params.add("afterId", after.id());
        String op1 = order.primaryDesc() ? " < " : " > ";
        String op2 = order.secondaryDesc() ? " < " : " > ";
        return "(" + order.primary() + op1 + k1
                + " OR (" + order.primary() + " = " + k1 + " AND " + order.secondary() + op2 + k2 + ")"
                + " OR (" + order.primary() + " = " + k1 + " AND " + order.secondary() + " = " + k2
                + " AND c.id > " + id + "))";
    }
}

package com.muslimafree.dashboard.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes members straight into user-profile-managment's tables, in the shape that service produces (an account, its
 * 1:1 profile, photos, blocks, sessions). Defaults describe a fully onboarded, visible, verified, active member.
 */
public class Members {

    private final JdbcTemplate jdbc;
    private final MutableClock clock;
    private final Consumer<UUID> reindex;

    /** @param reindex refreshes one member's search position, as the location endpoint does */
    public Members(JdbcTemplate jdbc, MutableClock clock, Consumer<UUID> reindex) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.reindex = reindex;
    }

    /** All attributes of one member; tests change what matters to them. */
    public static class Spec {
        public String firstName = "هند";
        public String lastName = "محمد";
        public String gender = "FEMALE";
        public int age = 30;
        public String status = "ACTIVE";
        /** Approved by default: most tests are about other rules; "new user" names are tested on their own. */
        public String reviewStatus = "APPROVED";
        public boolean emailVerified = true;
        public String onboardingStep = "COMPLETED";
        public String visibility = "VISIBLE";
        public String presenceMode = "ONLINE";
        /** null = never active */
        public Instant lastActiveAt;
        /** account creation; null = now */
        public Instant createdAt;
        public String nationality = "EG";
        public String countryCode = "EG";
        public String region = "القاهرة";
        public String city = "المعادي";
        public String maritalStatus = "SINGLE";
        public String education;
        public String children;
        public String smoking;
        public String sect;
        public String employmentSector;
        public String appearance;
        public String bodyShape;
        public Integer prefAgeMin;
        public Integer prefAgeMax;
        public String prefCountryCode;
        public String prefRegion;
        public String prefMinEducation;
        public String prefChildren;
        public String prefSmoking;
        public String prefSect;
        public String prefEmploymentSector;
        public String prefAppearance;
        public String prefBodyShape;
    }

    /** A created member and the session its tokens are bound to. */
    public record Member(UUID id, UUID sessionId) {

        public String token() {
            return TestTokens.accessToken(id, sessionId);
        }
    }

    public Member create(Consumer<Spec> customizer) {
        Spec spec = new Spec();
        spec.lastActiveAt = clock.instant().minusSeconds(60);
        customizer.accept(spec);
        UUID id = UUID.randomUUID();
        Timestamp now = ts(clock.instant());
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, email_verified, status, review_status, last_active_at,
                                   language, time_zone, date_time_locale, created_at, updated_at)
                VALUES (?, ?, 'x', ?, ?, ?, ?, 'ar', 'Africa/Cairo', 'ar-EG', ?, ?)
                """, id.toString(), id + "@example.com", spec.emailVerified, spec.status, spec.reviewStatus,
                spec.lastActiveAt == null ? null : ts(spec.lastActiveAt),
                spec.createdAt == null ? now : ts(spec.createdAt), now);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'MEMBER')", id.toString());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        // born 100 days before the birthday that gives exactly `age` today
        LocalDate dateOfBirth = today.minusYears(spec.age).minusDays(100);
        jdbc.update("""
                INSERT INTO user_profiles (user_id, first_name, last_name, gender, date_of_birth, nationality, country_code,
                    region, city, marital_status, education, children, sect, employment_sector, smoking, appearance,
                    body_shape, pref_age_min, pref_age_max, pref_country_code, pref_region, pref_min_education,
                    pref_children, pref_smoking, pref_sect, pref_employment_sector, pref_appearance, pref_body_shape,
                    visibility, presence_mode, onboarding_step, completeness, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 60, ?, ?)
                """, id.toString(), spec.firstName, spec.lastName, spec.gender, dateOfBirth, spec.nationality,
                spec.countryCode, spec.region, spec.city, spec.maritalStatus, spec.education, spec.children, spec.sect,
                spec.employmentSector, spec.smoking, spec.appearance, spec.bodyShape, spec.prefAgeMin, spec.prefAgeMax,
                spec.prefCountryCode, spec.prefRegion, spec.prefMinEducation, spec.prefChildren, spec.prefSmoking,
                spec.prefSect, spec.prefEmploymentSector, spec.prefAppearance, spec.prefBodyShape, spec.visibility,
                spec.presenceMode, spec.onboardingStep, now, now);
        UUID sessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_sessions (id, user_id, refresh_token_hash, remember_me, created_at, last_used_at, expires_at)
                VALUES (?, ?, ?, FALSE, ?, ?, ?)
                """, sessionId.toString(), id.toString(), UUID.randomUUID().toString().replace("-", ""), now, now,
                ts(clock.instant().plusSeconds(60L * 86_400)));
        return new Member(id, sessionId);
    }

    public UUID addPhoto(UUID memberId, String status, boolean primary, Instant createdAt) {
        UUID photoId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO profile_photos (id, user_id, storage_key, content_type, size_bytes, width, height, is_primary,
                                            status, created_at, updated_at)
                VALUES (?, ?, ?, 'image/jpeg', 1000, 100, 100, ?, ?, ?, ?)
                """, photoId.toString(), memberId.toString(), "users/" + memberId + "/" + photoId + ".jpg", primary,
                status, ts(createdAt), ts(createdAt));
        return photoId;
    }

    public void block(UUID blocker, UUID blocked) {
        jdbc.update("INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                blocker.toString(), blocked.toString(), ts(clock.instant()));
    }

    /** Exactly the statement user-profile-managment's ProfileViewRecorder runs (the write side of this contract). */
    public void recordView(UUID viewerId, UUID viewedId, Instant at) {
        jdbc.update("""
                INSERT INTO dashboard_profile_views (viewer_id, viewed_id, first_viewed_at, last_viewed_at, view_count)
                SELECT ?, ?, ?, ?, 1 FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM dashboard_member_settings s WHERE s.user_id = ? AND s.incognito)
                ON DUPLICATE KEY UPDATE last_viewed_at = VALUES(last_viewed_at), view_count = view_count + 1
                """, viewerId.toString(), viewedId.toString(), ts(at), ts(at), viewerId.toString());
    }

    public void revokeSession(UUID sessionId) {
        jdbc.update("UPDATE user_sessions SET revoked_at = ? WHERE id = ?", ts(clock.instant()), sessionId.toString());
    }

    public void locate(UUID memberId, double latitude, double longitude) {
        jdbc.update("""
                INSERT INTO dashboard_member_locations (user_id, latitude, longitude, updated_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE latitude = VALUES(latitude), longitude = VALUES(longitude),
                    updated_at = VALUES(updated_at)
                """, memberId.toString(), latitude, longitude, ts(clock.instant()));
        reindex.accept(memberId);
    }

    /** Empties every table between tests (children first). */
    public void deleteAll() {
        for (String table : new String[] {"dashboard_daily_picks", "dashboard_reports", "dashboard_notifications",
                "dashboard_member_settings", "dashboard_search_locations", "dashboard_messages",
                "dashboard_conversation_members",
                "dashboard_conversations", "dashboard_likes", "dashboard_favourites", "dashboard_profile_views",
                "dashboard_hidden_members", "dashboard_member_locations", "dashboard_location_changes", "user_blocks", "profile_photos",
                "user_sessions", "user_roles", "user_profiles", "users"}) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}

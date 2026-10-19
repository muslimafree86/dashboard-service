package com.muslimafree.dashboard.member;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads one member's profile from user-profile-managment's tables (read-only), plus their shared location. */
@Repository
public class ProfileSnapshotRepository {

    private static final String SQL = """
            SELECT p.user_id, u.status, p.onboarding_step, p.gender, p.date_of_birth, p.country_code, p.region, p.city,
                   p.marital_status, p.education, p.children, p.smoking, p.sect, p.employment_sector, p.appearance,
                   p.body_shape,
                   p.pref_age_min, p.pref_age_max, p.pref_country_code, p.pref_region, p.pref_min_education,
                   p.pref_children, p.pref_smoking, p.pref_sect, p.pref_employment_sector, p.pref_appearance,
                   p.pref_body_shape,
                   l.latitude, l.longitude, l.updated_at AS location_updated_at
            FROM user_profiles p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN dashboard_member_locations l ON l.user_id = p.user_id
            WHERE p.user_id = ?
            """;

    private final JdbcTemplate jdbc;

    public ProfileSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProfileSnapshot> find(UUID memberId) {
        List<ProfileSnapshot> rows = jdbc.query(SQL, ProfileSnapshotRepository::map, memberId.toString());
        return rows.stream().findFirst();
    }

    private static ProfileSnapshot map(ResultSet rs, int row) throws SQLException {
        String gender = rs.getString("gender");
        return new ProfileSnapshot(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("status"),
                "COMPLETED".equals(rs.getString("onboarding_step")),
                gender == null ? null : Gender.valueOf(gender),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("country_code"),
                rs.getString("region"),
                rs.getString("city"),
                rs.getString("marital_status"),
                new ProfileSnapshot.Traits(
                        rs.getString("education"),
                        rs.getString("children"),
                        rs.getString("smoking"),
                        rs.getString("sect"),
                        rs.getString("employment_sector"),
                        rs.getString("appearance"),
                        rs.getString("body_shape")),
                new ProfileSnapshot.Preferences(
                        (Integer) rs.getObject("pref_age_min", Integer.class),
                        (Integer) rs.getObject("pref_age_max", Integer.class),
                        rs.getString("pref_country_code"),
                        rs.getString("pref_region"),
                        rs.getString("pref_min_education"),
                        rs.getString("pref_children"),
                        rs.getString("pref_smoking"),
                        rs.getString("pref_sect"),
                        rs.getString("pref_employment_sector"),
                        rs.getString("pref_appearance"),
                        rs.getString("pref_body_shape")),
                rs.getBigDecimal("latitude"),
                rs.getBigDecimal("longitude"),
                rs.getObject("location_updated_at", LocalDateTime.class));
    }
}

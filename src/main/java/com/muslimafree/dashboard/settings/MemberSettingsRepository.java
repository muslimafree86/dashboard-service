package com.muslimafree.dashboard.settings;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code dashboard_member_settings}. user-profile-managment reads {@code incognito} when it records a profile view
 * (ProfileViewRecorder), so the column name and meaning are a contract between the two services.
 */
@Repository
public class MemberSettingsRepository {

    private final JdbcTemplate jdbc;

    public MemberSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean incognito(UUID memberId) {
        List<Boolean> rows = jdbc.query("SELECT incognito FROM dashboard_member_settings WHERE user_id = ?",
                (rs, i) -> rs.getBoolean("incognito"), memberId.toString());
        return !rows.isEmpty() && rows.getFirst();
    }

    public void setIncognito(UUID memberId, boolean incognito, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO dashboard_member_settings (user_id, incognito, updated_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE incognito = VALUES(incognito), updated_at = VALUES(updated_at)
                """, memberId.toString(), incognito, Timestamp.valueOf(now));
    }
}

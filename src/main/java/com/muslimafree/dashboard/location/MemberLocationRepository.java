package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code dashboard_member_locations}: a member's own shared device location (search uses the derived index). */
@Repository
public class MemberLocationRepository {

    private final JdbcTemplate jdbc;

    public MemberLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param changesDay / changesCount how many times the position moved on that UTC day (anti-trilateration) */
    public record StoredLocation(BigDecimal latitude, BigDecimal longitude, LocalDateTime updatedAt, LocalDate changesDay,
            int changesCount) {
    }

    /**
     * Moves an existing location in ONE statement, so that parallel requests cannot all pass the daily cap: the row lock
     * serialises them and each one sees the count the previous one left. Re-sending the current position is free.
     * Assignments run left to right (MariaDB's default), so {@code changes_count} is computed from the old position.
     *
     * @return false when there is no location yet, or the member already moved {@code maxChanges} times today
     */
    public boolean move(UUID memberId, BigDecimal latitude, BigDecimal longitude, LocalDateTime now, LocalDate today,
            int maxChanges) {
        Date day = Date.valueOf(today);
        return jdbc.update("""
                UPDATE dashboard_member_locations SET
                    changes_count = CASE
                        WHEN latitude = ? AND longitude = ? THEN CASE WHEN changes_day = ? THEN changes_count ELSE 0 END
                        WHEN changes_day = ? THEN changes_count + 1
                        ELSE 1 END,
                    changes_day = ?, latitude = ?, longitude = ?, updated_at = ?
                WHERE user_id = ?
                  AND ((latitude = ? AND longitude = ?) OR changes_day IS NULL OR changes_day <> ? OR changes_count < ?)
                """, latitude, longitude, day, day, day, latitude, longitude, Timestamp.valueOf(now),
                memberId.toString(), latitude, longitude, day, maxChanges) > 0;
    }

    /** The first location of a member (one move today); false when a row exists already. */
    public boolean insertIfAbsent(UUID memberId, BigDecimal latitude, BigDecimal longitude, LocalDateTime now,
            LocalDate today) {
        return jdbc.update("""
                INSERT IGNORE INTO dashboard_member_locations
                    (user_id, latitude, longitude, updated_at, changes_day, changes_count)
                VALUES (?, ?, ?, ?, ?, 1)
                """, memberId.toString(), latitude, longitude, Timestamp.valueOf(now), Date.valueOf(today)) > 0;
    }

    public Optional<StoredLocation> find(UUID memberId) {
        List<StoredLocation> rows = jdbc.query("""
                SELECT latitude, longitude, updated_at, changes_day, changes_count
                FROM dashboard_member_locations WHERE user_id = ?
                """, (rs, i) -> new StoredLocation(rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getObject("updated_at", LocalDateTime.class), rs.getObject("changes_day", LocalDate.class),
                rs.getInt("changes_count")), memberId.toString());
        return rows.stream().findFirst();
    }

    public boolean delete(UUID memberId) {
        return jdbc.update("DELETE FROM dashboard_member_locations WHERE user_id = ?", memberId.toString()) > 0;
    }
}

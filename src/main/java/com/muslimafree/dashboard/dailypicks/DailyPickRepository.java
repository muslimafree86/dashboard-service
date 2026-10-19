package com.muslimafree.dashboard.dailypicks;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code dashboard_daily_picks}. */
@Repository
public class DailyPickRepository {

    private final JdbcTemplate jdbc;

    public DailyPickRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> picks(UUID member, LocalDate day) {
        return jdbc.query("""
                SELECT picked_id FROM dashboard_daily_picks WHERE member_id = ? AND pick_date = ? ORDER BY position
                """, (rs, i) -> UUID.fromString(rs.getString("picked_id")), member.toString(), Date.valueOf(day));
    }

    /** Idempotent: when two first requests of the day race, the first set of picks wins. */
    public void save(UUID member, LocalDate day, List<UUID> picked) {
        for (int i = 0; i < picked.size(); i++) {
            jdbc.update("""
                    INSERT INTO dashboard_daily_picks (member_id, pick_date, position, picked_id) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE picked_id = picked_id
                    """, member.toString(), Date.valueOf(day), i, picked.get(i).toString());
        }
    }

    /** Removes picks older than {@code before} in short batches ({@code ix_dashboard_daily_picks_date}). */
    public int deleteBefore(LocalDate before) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("DELETE FROM dashboard_daily_picks WHERE pick_date < ? LIMIT 5000",
                    Date.valueOf(before));
            total += deleted;
        } while (deleted == 5000);
        return total;
    }
}

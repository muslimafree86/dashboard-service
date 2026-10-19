package com.muslimafree.dashboard.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code dashboard_reports}. */
@Repository
public class ReportRepository {

    private static final String SELECT = """
            SELECT r.*, (SELECT COUNT(*) FROM dashboard_reports o WHERE o.reported_id = r.reported_id
                         AND o.status = 'OPEN') AS open_against
            FROM dashboard_reports r
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean profileExists(UUID memberId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_profiles WHERE user_id = :id",
                Map.of("id", memberId.toString()), Integer.class);
        return count != null && count > 0;
    }

    public int reportsSince(UUID reporter, LocalDateTime since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dashboard_reports WHERE reporter_id = :reporter AND created_at >= :since",
                Map.of("reporter", reporter.toString(), "since", Timestamp.valueOf(since)), Integer.class);
        return count == null ? 0 : count;
    }

    public void insert(UUID id, UUID reporter, UUID reported, ReportReason reason, ReportContext context, String details,
            LocalDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id.toString());
        params.put("reporter", reporter.toString());
        params.put("reported", reported.toString());
        params.put("reason", reason.name());
        params.put("context", context.name());
        params.put("details", details);
        params.put("at", Timestamp.valueOf(at));
        jdbc.update("""
                INSERT INTO dashboard_reports (id, reporter_id, reported_id, reason, context, details, status, created_at)
                VALUES (:id, :reporter, :reported, :reason, :context, :details, 'OPEN', :at)
                """, params);
    }

    /** The moderation queue: by status, oldest first (first come, first served). */
    public List<ReportResponse> page(ReportStatus status, UUID reportedId, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status.name());
        params.put("limit", limit);
        params.put("offset", offset);
        String member = "";
        if (reportedId != null) {
            params.put("reported", reportedId.toString());
            member = " AND r.reported_id = :reported";
        }
        return jdbc.query(SELECT + " WHERE r.status = :status" + member
                + " ORDER BY r.created_at ASC, r.id ASC LIMIT :limit OFFSET :offset", params, (rs, i) -> map(rs));
    }

    public int resolve(UUID id, ReportStatus status, UUID moderator, String note, LocalDateTime at) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id.toString());
        params.put("status", status.name());
        params.put("moderator", moderator.toString());
        params.put("note", note);
        params.put("at", Timestamp.valueOf(at));
        return jdbc.update("""
                UPDATE dashboard_reports SET status = :status, reviewed_by = :moderator, reviewed_at = :at,
                    resolution_note = :note
                WHERE id = :id
                """, params);
    }

    public List<ReportResponse> find(UUID id) {
        return jdbc.query(SELECT + " WHERE r.id = :id", Map.of("id", id.toString()), (rs, i) -> map(rs));
    }

    private static ReportResponse map(ResultSet rs) throws SQLException {
        String reporter = rs.getString("reporter_id");
        String reviewer = rs.getString("reviewed_by");
        LocalDateTime reviewedAt = rs.getObject("reviewed_at", LocalDateTime.class);
        return new ReportResponse(UUID.fromString(rs.getString("id")),
                reporter == null ? null : UUID.fromString(reporter), UUID.fromString(rs.getString("reported_id")),
                ReportReason.valueOf(rs.getString("reason")), ReportContext.valueOf(rs.getString("context")),
                rs.getString("details"), ReportStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getLong("open_against"), reviewer == null ? null : UUID.fromString(reviewer),
                reviewedAt == null ? null : reviewedAt.toInstant(ZoneOffset.UTC),
                rs.getString("resolution_note"));
    }
}

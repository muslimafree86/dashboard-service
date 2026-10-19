package com.muslimafree.dashboard.hidden;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code dashboard_hidden_members}: who each member marked "not interested". */
@Repository
public class HiddenMemberRepository {

    private final JdbcTemplate jdbc;

    public HiddenMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Hidden(UUID memberId, LocalDateTime hiddenAt) {
    }

    /** Idempotent: hiding twice keeps the first time. */
    public void hide(UUID viewerId, UUID memberId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO dashboard_hidden_members (viewer_id, hidden_id, created_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = created_at
                """, viewerId.toString(), memberId.toString(), Timestamp.valueOf(now));
    }

    public void unhide(UUID viewerId, UUID memberId) {
        jdbc.update("DELETE FROM dashboard_hidden_members WHERE viewer_id = ? AND hidden_id = ?",
                viewerId.toString(), memberId.toString());
    }

    /** Which of {@code members} the viewer marked "not interested" (primary-key lookups). */
    public Set<UUID> hiddenAmong(UUID viewerId, Collection<UUID> members) {
        if (members.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(members.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(viewerId.toString());
        members.forEach(m -> args.add(m.toString()));
        return new HashSet<>(jdbc.query("SELECT hidden_id FROM dashboard_hidden_members"
                + " WHERE viewer_id = ? AND hidden_id IN (" + placeholders + ")", (rs, i) -> UUID.fromString(rs.getString("hidden_id")), args.toArray()));
    }

    /** Newest first, {@code ix_dashboard_hidden_viewer_created}; the member id breaks ties. */
    public List<Hidden> page(UUID viewerId, int offset, int limit) {
        return jdbc.query("""
                SELECT hidden_id, created_at FROM dashboard_hidden_members WHERE viewer_id = ?
                ORDER BY created_at DESC, hidden_id DESC LIMIT ? OFFSET ?
                """, (rs, i) -> new Hidden(UUID.fromString(rs.getString("hidden_id")),
                rs.getObject("created_at", LocalDateTime.class)), viewerId.toString(), limit, offset);
    }
}

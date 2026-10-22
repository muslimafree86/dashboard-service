package com.muslimafree.dashboard.notification;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muslimafree.dashboard.common.util.SqlParams;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.VisibilityRule;

/** {@code dashboard_notifications}. */
@Repository
public class NotificationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Notification(UUID id, NotificationType type, UUID actorId, LocalDateTime createdAt,
            LocalDateTime readAt) {
    }

    /** Creates the notification, or moves the existing one of the same kind and actor to the top, unread again. */
    public void notify(UUID id, UUID member, NotificationType type, UUID actor, LocalDateTime at) {
        jdbc.update("""
                INSERT INTO dashboard_notifications (id, member_id, type, actor_id, created_at)
                VALUES (:id, :member, :type, :actor, :at)
                ON DUPLICATE KEY UPDATE created_at = VALUES(created_at), read_at = NULL
                """, Map.of("id", id.toString(), "member", member.toString(), "type", type.name(),
                "actor", actor.toString(), "at", Timestamp.valueOf(at)));
    }

    /** At most one row per (member, type, actor): a repeated event refreshes it (see {@link #notify}). */
    public Optional<Notification> find(UUID member, NotificationType type, UUID actor) {
        List<Notification> rows = jdbc.query("""
                SELECT id, type, actor_id, created_at, read_at FROM dashboard_notifications
                WHERE member_id = :member AND type = :type AND actor_id = :actor
                """, Map.of("member", member.toString(), "type", type.name(), "actor", actor.toString()),
                (rs, i) -> new Notification(UUID.fromString(rs.getString("id")),
                        NotificationType.valueOf(rs.getString("type")), UUID.fromString(rs.getString("actor_id")),
                        rs.getObject("created_at", LocalDateTime.class), rs.getObject("read_at", LocalDateTime.class)));
        return rows.stream().findFirst();
    }

    public void remove(UUID member, NotificationType type, UUID actor) {
        jdbc.update("DELETE FROM dashboard_notifications WHERE member_id = :member AND type = :type AND actor_id = :actor",
                Map.of("member", member.toString(), "type", type.name(), "actor", actor.toString()));
    }

    public void markRead(UUID member, NotificationType type, UUID actor, LocalDateTime at) {
        jdbc.update("""
                UPDATE dashboard_notifications SET read_at = :at
                WHERE member_id = :member AND type = :type AND actor_id = :actor AND read_at IS NULL
                """, Map.of("member", member.toString(), "type", type.name(), "actor", actor.toString(),
                "at", Timestamp.valueOf(at)));
    }

    public int markRead(UUID member, UUID id, LocalDateTime at) {
        return jdbc.update("""
                UPDATE dashboard_notifications SET read_at = COALESCE(read_at, :at) WHERE id = :id AND member_id = :member
                """, Map.of("member", member.toString(), "id", id.toString(), "at", Timestamp.valueOf(at)));
    }

    public void markAllRead(UUID member, LocalDateTime at) {
        jdbc.update("UPDATE dashboard_notifications SET read_at = :at WHERE member_id = :member AND read_at IS NULL",
                Map.of("member", member.toString(), "at", Timestamp.valueOf(at)));
    }

    /** Newest first; only notifications whose actor the member may currently see (same rule as discovery). */
    public List<Notification> page(UUID member, Gender gender, boolean requireVerifiedEmail, int offset, int limit) {
        SqlParams params = new SqlParams();
        String sql = "SELECT n.id, n.type, n.actor_id, n.created_at, n.read_at"
                + from(member, gender, requireVerifiedEmail, params)
                + " ORDER BY n.created_at DESC, n.id DESC LIMIT " + params.add("limit", limit)
                + " OFFSET " + params.add("offset", offset);
        return jdbc.query(sql, params.values(), (rs, i) -> new Notification(UUID.fromString(rs.getString("id")),
                NotificationType.valueOf(rs.getString("type")), UUID.fromString(rs.getString("actor_id")),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("read_at", LocalDateTime.class)));
    }

    public long unread(UUID member, Gender gender, boolean requireVerifiedEmail) {
        SqlParams params = new SqlParams();
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + from(member, gender, requireVerifiedEmail, params)
                + " AND n.read_at IS NULL", params.values(), Long.class);
        return count == null ? 0 : count;
    }

    private static String from(UUID member, Gender gender, boolean requireVerifiedEmail,
            SqlParams params) {
        String me = params.add("viewerId", member.toString());
        List<String> where = new ArrayList<>();
        where.add("n.member_id = " + me);
        VisibilityRule.appendTo(me, gender, requireVerifiedEmail, where);
        return " FROM dashboard_notifications n JOIN user_profiles p ON p.user_id = n.actor_id"
                + " JOIN users u ON u.id = p.user_id WHERE " + String.join(" AND ", where);
    }
}

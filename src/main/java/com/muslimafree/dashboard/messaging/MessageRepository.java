package com.muslimafree.dashboard.messaging;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muslimafree.dashboard.common.util.SqlParams;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.VisibilityRule;

/** {@code dashboard_conversations}, {@code dashboard_conversation_members} and {@code dashboard_messages}. */
@Repository
public class MessageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public MessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Message(UUID id, UUID senderId, String body, LocalDateTime createdAt) {
    }

    /** One member's side of a conversation, as their inbox shows it. */
    public record Side(UUID otherId, LocalDateTime lastMessageAt, String lastMessagePreview, UUID lastSenderId,
            int unreadCount) {
    }

    public record Unread(long messages, long conversations) {
    }

    // ------------------------------------------------------------------------------------------------ conversations

    public Optional<UUID> conversationId(UUID one, UUID other) {
        List<UUID> ids = jdbc.query("SELECT id FROM dashboard_conversations WHERE member_a = :a AND member_b = :b",
                pair(one, other), (rs, i) -> UUID.fromString(rs.getString("id")));
        return ids.stream().findFirst();
    }

    /** The pair's conversation, created on first use (safe when both members write their first message at once). */
    public UUID ensureConversation(UUID one, UUID other, UUID newId, LocalDateTime now) {
        Map<String, Object> params = new HashMap<>(pair(one, other));
        params.put("id", newId.toString());
        params.put("now", Timestamp.valueOf(now));
        jdbc.update("""
                INSERT INTO dashboard_conversations (id, member_a, member_b, created_at) VALUES (:id, :a, :b, :now)
                ON DUPLICATE KEY UPDATE id = id
                """, params);
        return conversationId(one, other).orElseThrow();
    }

    /** member_a is the smaller id, so each pair has exactly one row. */
    private static Map<String, Object> pair(UUID one, UUID other) {
        String x = one.toString();
        String y = other.toString();
        return x.compareTo(y) < 0 ? Map.of("a", x, "b", y) : Map.of("a", y, "b", x);
    }

    // ------------------------------------------------------------------------------------------------ messages

    public void insert(UUID conversationId, Message message) {
        jdbc.update("""
                INSERT INTO dashboard_messages (id, conversation_id, sender_id, body, created_at)
                VALUES (:id, :conversation, :sender, :body, :at)
                """, Map.of("id", message.id().toString(), "conversation", conversationId.toString(),
                "sender", message.senderId().toString(), "body", message.body(),
                "at", Timestamp.valueOf(message.createdAt())));
    }

    /** The sender's side: last message is theirs, and they have obviously read everything up to it. */
    public void recordSent(UUID sender, UUID recipient, UUID conversationId, LocalDateTime at, String preview) {
        jdbc.update("""
                INSERT INTO dashboard_conversation_members (member_id, other_id, conversation_id, last_message_at,
                    last_message_preview, last_sender_id, unread_count, last_read_at)
                VALUES (:me, :other, :conversation, :at, :preview, :me, 0, :at)
                ON DUPLICATE KEY UPDATE last_message_at = VALUES(last_message_at),
                    last_message_preview = VALUES(last_message_preview), last_sender_id = VALUES(last_sender_id),
                    unread_count = 0, last_read_at = VALUES(last_read_at)
                """, Map.of("me", sender.toString(), "other", recipient.toString(),
                "conversation", conversationId.toString(), "at", Timestamp.valueOf(at), "preview", preview));
    }

    /** The recipient's side: one more unread message (an atomic increment, safe under concurrent sends). */
    public void recordReceived(UUID recipient, UUID sender, UUID conversationId, LocalDateTime at, String preview) {
        jdbc.update("""
                INSERT INTO dashboard_conversation_members (member_id, other_id, conversation_id, last_message_at,
                    last_message_preview, last_sender_id, unread_count)
                VALUES (:me, :other, :conversation, :at, :preview, :other, 1)
                ON DUPLICATE KEY UPDATE last_message_at = VALUES(last_message_at),
                    last_message_preview = VALUES(last_message_preview), last_sender_id = VALUES(last_sender_id),
                    unread_count = unread_count + 1
                """, Map.of("me", recipient.toString(), "other", sender.toString(),
                "conversation", conversationId.toString(), "at", Timestamp.valueOf(at), "preview", preview));
    }

    public void markRead(UUID member, UUID other, LocalDateTime now) {
        jdbc.update("""
                UPDATE dashboard_conversation_members SET unread_count = 0, last_read_at = :now
                WHERE member_id = :me AND other_id = :other
                """, Map.of("me", member.toString(), "other", other.toString(), "now", Timestamp.valueOf(now)));
    }

    /**
     * Messages sent strictly after {@code since} -
     * the per-minute send limit ({@code ix_dashboard_messages_sender_created}).
     */
    public int sentAfter(UUID sender, LocalDateTime since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dashboard_messages WHERE sender_id = :sender AND created_at > :since",
                Map.of("sender", sender.toString(), "since", Timestamp.valueOf(since)), Integer.class);
        return count == null ? 0 : count;
    }

    /** Where a message sits in its thread; empty when it is not a message of this conversation. */
    public Optional<Message> find(UUID conversationId, UUID messageId) {
        return jdbc.query("""
                SELECT id, sender_id, body, created_at FROM dashboard_messages
                WHERE id = :id AND conversation_id = :conversation
                """, Map.of("id", messageId.toString(), "conversation", conversationId.toString()),
                (rs, i) -> map(rs)).stream().findFirst();
    }

    /**
     * Up to {@code limit} messages strictly older than {@code before} (or the newest ones when {@code null}), returned
     * oldest first as a chat displays them.
     */
    public List<Message> older(UUID conversationId, Message before, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("conversation", conversationId.toString());
        params.put("limit", limit);
        String position = "";
        if (before != null) {
            params.put("at", Timestamp.valueOf(before.createdAt()));
            params.put("id", before.id().toString());
            position = " AND (created_at < :at OR (created_at = :at AND id < :id))";
        }
        List<Message> newestFirst = new ArrayList<>(jdbc.query("SELECT id, sender_id, body, created_at "
                + "FROM dashboard_messages WHERE conversation_id = :conversation" + position
                + " ORDER BY created_at DESC, id DESC LIMIT :limit", params, (rs, i) -> map(rs)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** Up to {@code limit} messages strictly newer than {@code after}, oldest first (polling an open chat). */
    public List<Message> newer(UUID conversationId, Message after, int limit) {
        return jdbc.query("""
                SELECT id, sender_id, body, created_at FROM dashboard_messages
                WHERE conversation_id = :conversation AND (created_at > :at OR (created_at = :at AND id > :id))
                ORDER BY created_at ASC, id ASC LIMIT :limit
                """, Map.of("conversation", conversationId.toString(), "at", Timestamp.valueOf(after.createdAt()),
                "id", after.id().toString(), "limit", limit), (rs, i) -> map(rs));
    }

    private static Message map(ResultSet rs) throws SQLException {
        return new Message(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("sender_id")),
                rs.getString("body"), rs.getObject("created_at", LocalDateTime.class));
    }

    // ------------------------------------------------------------------------------------------------ inbox

    /**
     * One page of the member's conversations, most recent first, with members they may currently see only (blocked,
     * hidden-profile, suspended ... members drop out, as everywhere in the dashboard).
     */
    public List<Side> page(UUID member, ConversationBox box, Gender gender, boolean requireVerifiedEmail, int offset,
            int limit) {
        SqlParams params = new SqlParams();
        String sql = "SELECT cm.other_id, cm.last_message_at, cm.last_message_preview, cm.last_sender_id, "
                + "cm.unread_count" + inbox(member, box, gender, requireVerifiedEmail, params)
                + " ORDER BY cm.last_message_at DESC, cm.other_id DESC LIMIT " + params.add("limit", limit)
                + " OFFSET " + params.add("offset", offset);
        return jdbc.query(sql, params.values(), (rs, i) -> new Side(UUID.fromString(rs.getString("other_id")),
                rs.getObject("last_message_at", LocalDateTime.class), rs.getString("last_message_preview"),
                UUID.fromString(rs.getString("last_sender_id")), rs.getInt("unread_count")));
    }

    public Unread unread(UUID member, Gender gender, boolean requireVerifiedEmail) {
        SqlParams params = new SqlParams();
        return jdbc.queryForObject("SELECT COALESCE(SUM(cm.unread_count), 0) AS messages, "
                + "COALESCE(SUM(cm.unread_count > 0), 0) AS conversations"
                + inbox(member, ConversationBox.ALL, gender, requireVerifiedEmail, params), params.values(),
                (rs, i) -> new Unread(rs.getLong("messages"), rs.getLong("conversations")));
    }

    private static String inbox(UUID member, ConversationBox box, Gender gender, boolean requireVerifiedEmail,
            SqlParams params) {
        String me = params.add("viewerId", member.toString());
        List<String> where = new ArrayList<>();
        where.add("cm.member_id = " + me);
        if (box == ConversationBox.INBOX) {
            where.add("cm.last_sender_id <> " + me);
        } else if (box == ConversationBox.SENT) {
            where.add("cm.last_sender_id = " + me);
        }
        VisibilityRule.appendTo(me, gender, requireVerifiedEmail, where);
        return " FROM dashboard_conversation_members cm JOIN user_profiles p ON p.user_id = cm.other_id"
                + " JOIN users u ON u.id = p.user_id WHERE " + String.join(" AND ", where);
    }
}

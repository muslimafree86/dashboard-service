package com.muslimafree.dashboard.interaction;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muslimafree.dashboard.common.util.SqlParams;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.VisibilityRule;

/** {@code dashboard_likes}, {@code dashboard_favourites} and {@code dashboard_profile_views}. */
@Repository
public class InteractionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InteractionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One entry of an Activity list: the other member and when it happened. */
    public record Entry(UUID memberId, LocalDateTime at) {
    }

    /** What the viewer and one other member did towards each other. */
    public record Status(boolean liked, boolean likedMe, boolean favourite) {
    }

    // ------------------------------------------------------------------------------------------------ writes

    /** Idempotent: liking twice keeps the first time. */
    public void like(UUID liker, UUID liked, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO dashboard_likes (liker_id, liked_id, created_at) VALUES (:liker, :liked, :now)
                ON DUPLICATE KEY UPDATE created_at = created_at
                """, Map.of("liker", liker.toString(), "liked", liked.toString(),
                "now", Timestamp.valueOf(now)));
    }

    public void unlike(UUID liker, UUID liked) {
        jdbc.update("DELETE FROM dashboard_likes WHERE liker_id = :liker AND liked_id = :liked",
                Map.of("liker", liker.toString(), "liked", liked.toString()));
    }

    /** Likes given since the start of the day ({@code ix_dashboard_likes_liker_created}). */
    public int likesSince(UUID liker, LocalDateTime since) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dashboard_likes WHERE liker_id = :liker AND created_at >= :since
                """, Map.of("liker", liker.toString(), "since", Timestamp.valueOf(since)), Integer.class);
        return count == null ? 0 : count;
    }

    public void favourite(UUID owner, UUID member, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO dashboard_favourites (owner_id, member_id, created_at) VALUES (:owner, :member, :now)
                ON DUPLICATE KEY UPDATE created_at = created_at
                """, Map.of("owner", owner.toString(), "member", member.toString(), "now",
                Timestamp.valueOf(now)));
    }

    public void unfavourite(UUID owner, UUID member) {
        jdbc.update("DELETE FROM dashboard_favourites WHERE owner_id = :owner AND member_id = :member",
                Map.of("owner", owner.toString(), "member", member.toString()));
    }

    // ------------------------------------------------------------------------------------------------ reads

    public Status status(UUID viewer, UUID member) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM dashboard_likes WHERE liker_id = :viewer AND liked_id = :member) AS liked,
                       EXISTS (SELECT 1 FROM dashboard_likes WHERE liker_id = :member AND liked_id = :viewer) AS liked_me,
                       EXISTS (SELECT 1 FROM dashboard_favourites WHERE owner_id = :viewer AND member_id = :member)
                           AS favourite
                """, Map.of("viewer", viewer.toString(), "member", member.toString()),
                (rs, i) -> new Status(rs.getBoolean("liked"), rs.getBoolean("liked_me"), rs.getBoolean("favourite")));
    }

    /** Which of {@code members} are mutual likes with {@code viewer} (one query for a whole inbox page). */
    public Set<UUID> matchedAmong(UUID viewer, Collection<UUID> members) {
        if (members.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("""
                SELECT x.liked_id FROM dashboard_likes x
                JOIN dashboard_likes y ON y.liker_id = x.liked_id AND y.liked_id = x.liker_id
                WHERE x.liker_id = :viewer AND x.liked_id IN (:members)
                """, Map.of("viewer", viewer.toString(), "members", members.stream().map(UUID::toString).toList()),
                (rs, i) -> UUID.fromString(rs.getString("liked_id"))));
    }

    /**
     * One page of an Activity list, most recent first. Only members the viewer may currently see are listed (same rule
     * as discovery: blocked, hidden-profile, suspended ... members drop out), so pages and counts agree with the cards.
     */
    public List<Entry> page(ActivityList list, UUID viewer, Gender gender, boolean requireVerifiedEmail, int offset,
            int limit) {
        SqlParams params = new SqlParams();
        Shape shape = shape(list);
        String sql = "SELECT " + shape.counterpart() + " AS member_id, " + shape.at() + " AS at"
                + from(shape, viewer, gender, requireVerifiedEmail, params)
                + " ORDER BY at DESC, member_id DESC LIMIT " + params.add("limit", limit)
                + " OFFSET " + params.add("offset", offset);
        return jdbc.query(sql, params.values(), (rs, i) -> new Entry(UUID.fromString(rs.getString("member_id")),
                rs.getObject("at", LocalDateTime.class)));
    }

    /** Size of an Activity list, with the same visibility rule as {@link #page}. */
    public long count(ActivityList list, UUID viewer, Gender gender, boolean requireVerifiedEmail) {
        SqlParams params = new SqlParams();
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + from(shape(list), viewer, gender, requireVerifiedEmail,
                params), params.values(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * @param table       the interaction rows {@code x} (and, for matches, the like in the other direction)
     * @param owner       the column that must be the viewer
     * @param counterpart the column holding the other member
     * @param at          when the entry happened
     */
    private record Shape(String table, String owner, String counterpart, String at) {
    }

    private static Shape shape(ActivityList list) {
        return switch (list) {
            case LIKES_RECEIVED -> new Shape("dashboard_likes x", "x.liked_id", "x.liker_id", "x.created_at");
            case LIKES_SENT -> new Shape("dashboard_likes x", "x.liker_id", "x.liked_id", "x.created_at");
            case MATCHES -> new Shape("dashboard_likes x JOIN dashboard_likes y "
                    + "ON y.liker_id = x.liked_id AND y.liked_id = x.liker_id", "x.liker_id", "x.liked_id",
                    "GREATEST(x.created_at, y.created_at)");
            case FAVOURITES -> new Shape("dashboard_favourites x", "x.owner_id", "x.member_id", "x.created_at");
            case VIEWERS -> new Shape("dashboard_profile_views x", "x.viewed_id", "x.viewer_id", "x.last_viewed_at");
        };
    }

    private static String from(Shape shape, UUID viewer, Gender gender, boolean requireVerifiedEmail,
            SqlParams params) {
        String viewerId = params.add("viewerId", viewer.toString());
        List<String> where = new ArrayList<>();
        where.add(shape.owner() + " = " + viewerId);
        VisibilityRule.appendTo(viewerId, gender, requireVerifiedEmail, where);
        return " FROM " + shape.table() + " JOIN user_profiles p ON p.user_id = " + shape.counterpart()
                + " JOIN users u ON u.id = p.user_id WHERE " + String.join(" AND ", where);
    }
}

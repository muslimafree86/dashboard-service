package com.muslimafree.dashboard.member;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Card photos for one page of members in a single query ({@code ix_photos_user}). Only APPROVED photos, ordered as
 * user-profile-managment orders them for other members: main photo first, then oldest first.
 */
@Repository
public class MemberPhotoRepository {

    private static final String SQL = """
            SELECT id, user_id, storage_key FROM profile_photos
            WHERE status = 'APPROVED' AND user_id IN (:ids)
            ORDER BY user_id, is_primary DESC, created_at ASC, id ASC
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MemberPhotoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Approved photos per member, card photo first. Members without one are absent from the map. */
    public Map<UUID, List<CardPhoto>> approvedPhotos(Collection<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CardPhoto>> byMember = new LinkedHashMap<>();
        jdbc.query(SQL, Map.of("ids", memberIds.stream().map(UUID::toString).toList()), rs -> {
            CardPhoto photo = new CardPhoto(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("user_id")),
                    rs.getString("storage_key"));
            byMember.computeIfAbsent(photo.userId(), id -> new ArrayList<>()).add(photo);
        });
        return byMember;
    }
}

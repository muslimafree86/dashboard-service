package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code dashboard_search_locations}: every member's effective search position (see {@code LocationIndexer}). */
@Repository
public class SearchLocationRepository {

    private final JdbcTemplate jdbc;

    public SearchLocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What the indexer needs about one member: profile place fields, their device location (if any) and what is
     * indexed now.
     */
    public record Source(UUID memberId, String countryCode, String region, String city, BigDecimal deviceLatitude,
            BigDecimal deviceLongitude, LocalDateTime deviceUpdatedAt, GeoPoint indexed) {
    }

    private static final String SOURCE_SQL = """
            SELECT p.user_id, p.country_code, p.region, p.city,
                   d.latitude AS d_lat, d.longitude AS d_lon, d.updated_at AS d_at,
                   s.latitude AS s_lat, s.longitude AS s_lon, s.source AS s_source, s.place_id AS s_place
            FROM user_profiles p
            LEFT JOIN dashboard_member_locations d ON d.user_id = p.user_id
            LEFT JOIN dashboard_search_locations s ON s.user_id = p.user_id
            """;

    public List<Source> sources(UUID memberId) {
        return jdbc.query(SOURCE_SQL + " WHERE p.user_id = ?", (rs, i) -> map(rs), memberId.toString());
    }

    /** One batch of the full reconciliation, in primary-key order. */
    public List<Source> sourcesAfter(String afterUserId, int batch) {
        return jdbc.query(SOURCE_SQL + " WHERE p.user_id > ? ORDER BY p.user_id LIMIT ?", (rs, i) -> map(rs),
                afterUserId, batch);
    }

    private static Source map(ResultSet rs) throws SQLException {
        String source = rs.getString("s_source");
        GeoPoint indexed = source == null ? null : new GeoPoint(rs.getBigDecimal("s_lat"), rs.getBigDecimal("s_lon"),
                LocationSource.valueOf(source), (Integer) rs.getObject("s_place", Integer.class));
        return new Source(UUID.fromString(rs.getString("user_id")), rs.getString("country_code"), rs.getString("region"),
                rs.getString("city"), rs.getBigDecimal("d_lat"), rs.getBigDecimal("d_lon"),
                rs.getObject("d_at", LocalDateTime.class), indexed);
    }

    /** A place change queued by user-profile-managment ({@code dashboard_location_changes}). */
    public record QueuedChange(UUID memberId, LocalDateTime changedAt) {
    }

    public List<QueuedChange> queuedChanges(int batch) {
        return jdbc.query("SELECT user_id, changed_at FROM dashboard_location_changes ORDER BY changed_at LIMIT ?",
                (rs, i) -> new QueuedChange(UUID.fromString(rs.getString("user_id")),
                        rs.getObject("changed_at", LocalDateTime.class)), batch);
    }

    /** Removes a queued change unless the profile changed again meanwhile (then the next pass picks it up). */
    public void dequeue(QueuedChange change) {
        jdbc.update("DELETE FROM dashboard_location_changes WHERE user_id = ? AND changed_at = ?",
                change.memberId().toString(), Timestamp.valueOf(change.changedAt()));
    }

    /** Members still indexed at a device location that is now older than {@code cutoff} (ix_dashboard_locations_updated). */
    public List<UUID> staleDevices(LocalDateTime cutoff, int batch) {
        return jdbc.query("""
                SELECT d.user_id FROM dashboard_member_locations d
                JOIN dashboard_search_locations s ON s.user_id = d.user_id AND s.source = 'DEVICE'
                WHERE d.updated_at < ?
                LIMIT ?
                """, (rs, i) -> UUID.fromString(rs.getString("user_id")), Timestamp.valueOf(cutoff), batch);
    }

    public void upsert(UUID memberId, GeoPoint point, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO dashboard_search_locations (user_id, latitude, longitude, source, place_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE latitude = VALUES(latitude), longitude = VALUES(longitude),
                    source = VALUES(source), place_id = VALUES(place_id), updated_at = VALUES(updated_at)
                """, memberId.toString(), point.latitude(), point.longitude(), point.source().name(), point.placeId(),
                Timestamp.valueOf(now));
    }

    public void delete(UUID memberId) {
        jdbc.update("DELETE FROM dashboard_search_locations WHERE user_id = ?", memberId.toString());
    }
}

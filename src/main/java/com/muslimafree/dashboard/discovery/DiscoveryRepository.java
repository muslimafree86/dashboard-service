package com.muslimafree.dashboard.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.muslimafree.dashboard.common.config.AppProperties;

/** Runs the discovery statement built by {@link DiscoveryQueryBuilder}: one round trip per page. */
@Repository
public class DiscoveryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final DiscoveryQueryBuilder builder;

    public DiscoveryRepository(NamedParameterJdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.builder = new DiscoveryQueryBuilder(properties.discovery().weights(), properties.discovery().onlineWindow(),
                properties.discovery().candidatePool());
    }

    public List<CandidateRow> find(DiscoveryQuery query) {
        DiscoveryQueryBuilder.Sql sql = builder.build(query);
        return jdbc.query(sql.sql(), sql.params(), DiscoveryRepository::map);
    }

    private static CandidateRow map(ResultSet rs, int row) throws SQLException {
        return new CandidateRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getObject("date_of_birth", LocalDate.class),
                rs.getString("country_code"),
                rs.getString("region"),
                rs.getString("city"),
                rs.getString("gender"),
                rs.getObject("pref_age_min", Integer.class),
                rs.getObject("pref_age_max", Integer.class),
                rs.getString("presence_mode"),
                rs.getObject("last_active_at", LocalDateTime.class),
                rs.getString("review_status"),
                rs.getBigDecimal("active_key"),
                rs.getBigDecimal("created_key"),
                rs.getBigDecimal("distance_km"),
                rs.getString("location_source"),
                rs.getBigDecimal("score"));
    }
}

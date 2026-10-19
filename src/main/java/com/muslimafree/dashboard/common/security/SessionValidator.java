package com.muslimafree.dashboard.common.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muslimafree.dashboard.common.config.AppProperties;

/**
 * The same check user-profile-managment applies to every request: the token's session ({@code sid}) still exists, is
 * not revoked or expired, belongs to the token's subject, and the account is ACTIVE. Without it a signed-out, suspended
 * or banned member could keep browsing members until the access token expires. Results are cached for
 * {@code app.security.session-cache-ttl}, which bounds how late a revocation is seen here.
 */
@Component
public class SessionValidator {

    private static final String SQL = """
            SELECT COUNT(*) FROM user_sessions s JOIN users u ON u.id = s.user_id
            WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND u.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Cache<CurrentUser, Boolean> cache;

    public SessionValidator(JdbcTemplate jdbc, Clock clock, AppProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.security().sessionCacheTtl())
                .maximumSize(200_000)
                .build();
    }

    public boolean isUsable(CurrentUser user) {
        if (user.sessionId() == null) {
            return false;
        }
        return cache.get(user, this::query);
    }

    private boolean query(CurrentUser user) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Integer count = jdbc.queryForObject(SQL, Integer.class,
                user.sessionId().toString(), user.userId().toString(), Timestamp.valueOf(now));
        return count != null && count > 0;
    }
}

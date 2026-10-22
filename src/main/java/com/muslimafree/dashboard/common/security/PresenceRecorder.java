package com.muslimafree.dashboard.common.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muslimafree.dashboard.common.config.AppProperties;

/**
 * Maintains {@code users.last_active_at} ("متصل الان" / "قبل 10 ساعات") for members using the dashboard, exactly like
 * user-profile-managment's {@code LastActiveFilter}: at most one write per {@code app.security.last-active-write-interval}
 * per member (in-memory throttle + the same conditional UPDATE), so two services touching the column never write it
 * more often than one would. The only write this service makes to a profile table.
 */
@Component
public class PresenceRecorder {

    private static final Logger log = LoggerFactory.getLogger(PresenceRecorder.class);
    private static final String SQL =
            "UPDATE users SET last_active_at = ? WHERE id = ? AND (last_active_at IS NULL OR last_active_at < ?)";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration interval;
    private final Cache<UUID, Instant> lastWrites;

    public PresenceRecorder(JdbcTemplate jdbc, Clock clock, AppProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.interval = properties.security().lastActiveWriteInterval();
        this.lastWrites = Caffeine.newBuilder().expireAfterWrite(interval).maximumSize(500_000).build();
    }

    /** Best effort: presence must never fail the request or the socket that caused it. */
    public void touch(UUID memberId) {
        if (lastWrites.getIfPresent(memberId) != null) {
            return;
        }
        Instant now = clock.instant();
        lastWrites.put(memberId, now);
        try {
            jdbc.update(SQL, Timestamp.valueOf(LocalDateTime.ofInstant(now, ZoneOffset.UTC)), memberId.toString(),
                    Timestamp.valueOf(LocalDateTime.ofInstant(now.minus(interval), ZoneOffset.UTC)));
        } catch (RuntimeException e) {
            log.debug("Could not update last activity", e);
        }
    }
}

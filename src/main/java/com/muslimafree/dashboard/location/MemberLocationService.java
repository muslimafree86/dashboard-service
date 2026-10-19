package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;

/**
 * The opt-in device location behind "علي مسافة __ كم". Only the owner can set, read or remove it; other members only ever
 * see a rounded distance computed in the database.
 * <ul>
 * <li>Snapped to a ~1 km grid before storage: the exact device position is never persisted.</li>
 * <li>At most {@code app.location.max-changes-per-day} moves a day. Moving one's own position around and reading the
 * distances shown is how someone else's position is triangulated; refreshing the same position costs nothing.</li>
 * <li>Every change is applied to the search index at once ({@link LocationIndexer}); removing it falls back to the
 * member's city / governorate.</li>
 * </ul>
 */
@Service
public class MemberLocationService {

    private static final int UPDATE_ATTEMPTS = 3;

    private final MemberLocationRepository locations;
    private final LocationIndexer indexer;
    private final int gridDecimals;
    private final int maxChangesPerDay;
    private final Duration deviceMaxAge;
    private final Clock clock;
    private final TransactionTemplate updates;

    public MemberLocationService(MemberLocationRepository locations, LocationIndexer indexer, AppProperties properties,
            Clock clock, PlatformTransactionManager transactions) {
        this.locations = locations;
        this.indexer = indexer;
        this.gridDecimals = properties.location().gridDecimals();
        this.maxChangesPerDay = properties.location().maxChangesPerDay();
        this.deviceMaxAge = properties.location().deviceMaxAge();
        this.clock = clock;
        // READ COMMITTED: the cap's UPDATE then locks the member's row only, not the gap before it, so parallel first
        // locations of one member queue up instead of deadlocking
        this.updates = new TransactionTemplate(transactions);
        this.updates.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Retried when parallel requests of the same member still collide on a lock (the loser is rolled back whole). */
    public MemberLocationResponse update(CurrentUser user, UpdateLocationRequest request) {
        for (int attempt = 1;; attempt++) {
            try {
                return updates.execute(status -> save(user, request));
            } catch (PessimisticLockingFailureException e) {
                if (attempt == UPDATE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private MemberLocationResponse save(CurrentUser user, UpdateLocationRequest request) {
        BigDecimal latitude = snap(request.latitude());
        BigDecimal longitude = snap(request.longitude());
        Instant instant = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        LocalDate today = now.toLocalDate();
        // move, else first location, else a parallel request created it meanwhile: move again (now counted)
        boolean saved = locations.move(user.userId(), latitude, longitude, now, today, maxChangesPerDay)
                || locations.insertIfAbsent(user.userId(), latitude, longitude, now, today)
                || locations.move(user.userId(), latitude, longitude, now, today, maxChangesPerDay);
        if (!saved) {
            long retryAfter = instant.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).getEpochSecond()
                    - instant.getEpochSecond();
            throw new ApiException(ErrorCode.LOCATION_CHANGE_LIMIT_REACHED, Map.of("retryAfterSeconds", retryAfter));
        }
        indexer.refresh(user.userId());
        return response(latitude, longitude, now, instant);
    }

    @Transactional(readOnly = true)
    public Optional<MemberLocationResponse> find(CurrentUser user) {
        return locations.find(user.userId()).map(l -> response(
                l.latitude().setScale(gridDecimals, RoundingMode.HALF_UP),
                l.longitude().setScale(gridDecimals, RoundingMode.HALF_UP),
                l.updatedAt(), clock.instant()));
    }

    /** After {@code app.location.device-max-age} the location no longer counts; the app should ask for a fresh one. */
    private MemberLocationResponse response(BigDecimal latitude, BigDecimal longitude, LocalDateTime updatedAt,
            Instant now) {
        Instant updated = updatedAt.toInstant(ZoneOffset.UTC);
        Instant expiresAt = updated.plus(deviceMaxAge);
        return new MemberLocationResponse(latitude, longitude, updated, expiresAt.isAfter(now), expiresAt);
    }

    /** Stop sharing: distances fall back to the member's city / governorate at once. */
    @Transactional
    public void delete(CurrentUser user) {
        locations.delete(user.userId());
        indexer.refresh(user.userId());
    }

    BigDecimal snap(BigDecimal coordinate) {
        return coordinate.setScale(gridDecimals, RoundingMode.HALF_UP);
    }
}

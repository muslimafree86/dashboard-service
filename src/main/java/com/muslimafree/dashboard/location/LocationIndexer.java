package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;

/**
 * Keeps {@code dashboard_search_locations} - one search position per member - equal to the best position known:
 * <ol>
 * <li>the device location, while it is fresher than {@code app.location.device-max-age};</li>
 * <li>else the centre of the profile's city / district;</li>
 * <li>else the centre of the profile's governorate;</li>
 * <li>else none (the member is not found by distance, but still by every other search).</li>
 * </ol>
 * Updated immediately when a member shares or removes a device location, and by a periodic full pass
 * ({@code LocationJobs}) for what changes elsewhere: profile city / governorate edits in user-profile-managment, new
 * members, device locations going stale.
 */
@Service
public class LocationIndexer {

    private static final Logger log = LoggerFactory.getLogger(LocationIndexer.class);
    private static final int BATCH = 500;

    private final SearchLocationRepository locations;
    private final PlaceDirectory places;
    private final Duration deviceMaxAge;
    private final Clock clock;

    public LocationIndexer(SearchLocationRepository locations, PlaceDirectory places, AppProperties properties,
            Clock clock) {
        this.locations = locations;
        this.places = places;
        this.deviceMaxAge = properties.location().deviceMaxAge();
        this.clock = clock;
    }

    /** The best position for a member right now (also used for the viewer's own search centre). */
    public Optional<GeoPoint> best(BigDecimal deviceLatitude, BigDecimal deviceLongitude, LocalDateTime deviceUpdatedAt,
            String countryCode, String region, String city) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (deviceLatitude != null && deviceUpdatedAt != null && deviceUpdatedAt.isAfter(now.minus(deviceMaxAge))) {
            return Optional.of(new GeoPoint(deviceLatitude, deviceLongitude, LocationSource.DEVICE, null));
        }
        return places.resolve(countryCode, region, city);
    }

    @Transactional
    public void refresh(UUID memberId) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        locations.sources(memberId).forEach(source -> apply(source, now));
    }

    /**
     * Applies the place changes user-profile-managment queued ({@code dashboard_location_changes}: onboarding, profile
     * edits of country / governorate / city); returns how many members were refreshed.
     */
    public int applyQueuedChanges() {
        int applied = 0;
        List<SearchLocationRepository.QueuedChange> batch;
        do {
            batch = locations.queuedChanges(BATCH);
            for (SearchLocationRepository.QueuedChange change : batch) {
                refresh(change.memberId());
                locations.dequeue(change);
                applied++;
            }
        } while (batch.size() == BATCH);
        return applied;
    }

    /** Device locations that just went stale fall back to the city / governorate; returns how many. */
    public int expireStaleDevices() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minus(deviceMaxAge);
        int expired = 0;
        List<UUID> batch;
        do {
            batch = locations.staleDevices(cutoff, BATCH);
            batch.forEach(this::refresh);
            expired += batch.size();
        } while (batch.size() == BATCH);
        return expired;
    }

    /** Full pass over all profiles in short batches; returns the number of positions changed. */
    public int reconcileAll() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String after = "";
        int changed = 0;
        List<SearchLocationRepository.Source> batch;
        do {
            batch = locations.sourcesAfter(after, BATCH);
            for (SearchLocationRepository.Source source : batch) {
                if (apply(source, now)) {
                    changed++;
                }
            }
            if (!batch.isEmpty()) {
                after = batch.getLast().memberId().toString();
            }
        } while (batch.size() == BATCH);
        log.debug("Search locations reconciled, {} changed", changed);
        return changed;
    }

    private boolean apply(SearchLocationRepository.Source source, LocalDateTime now) {
        GeoPoint wanted = best(source.deviceLatitude(), source.deviceLongitude(), source.deviceUpdatedAt(),
                source.countryCode(), source.region(), source.city()).orElse(null);
        if (same(wanted, source.indexed())) {
            return false;
        }
        if (wanted == null) {
            locations.delete(source.memberId());
        } else {
            locations.upsert(source.memberId(), wanted, now);
        }
        return true;
    }

    private static boolean same(GeoPoint a, GeoPoint b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.source() == b.source() && Objects.equals(a.placeId(), b.placeId())
                && a.latitude().compareTo(b.latitude()) == 0 && a.longitude().compareTo(b.longitude()) == 0;
    }
}

package com.muslimafree.dashboard.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.muslimafree.dashboard.common.config.AppProperties;

/**
 * Per-member request budget for discovery: {@code per-minute} and {@code per-day} fixed windows. Discovery hands out
 * names, ages, places and signed photo links of other members, so an unthrottled account could copy the whole member
 * base - including women's photos - with a script. A human scrolling the Home page stays far below both limits.
 * <p>
 * Counters are kept in memory, per instance: with N instances behind a load balancer a member gets up to N times the
 * budget. That still caps scraping; a shared counter (Redis) is only worth it when exact limits matter.
 */
@Component
public class DiscoveryRateLimiter {

    private final AppProperties.RateLimit limits;
    private final Clock clock;
    private final Cache<String, AtomicInteger> minuteWindows;
    private final Cache<String, AtomicInteger> dayWindows;

    public DiscoveryRateLimiter(AppProperties properties, Clock clock) {
        this.limits = properties.rateLimit();
        this.clock = clock;
        this.minuteWindows = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(2)).maximumSize(500_000).build();
        this.dayWindows = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(25)).maximumSize(500_000).build();
    }

    /** Counts one request; returns the seconds to wait when a limit is exceeded, empty when the request may proceed. */
    public OptionalLong acquire(UUID memberId) {
        if (!limits.enabled()) {
            return OptionalLong.empty();
        }
        long now = clock.instant().getEpochSecond();
        long minute = now / 60;
        long day = now / 86_400;
        if (minuteWindows.get(memberId + ":" + minute, k -> new AtomicInteger()).incrementAndGet() > limits.perMinute()) {
            // a request turned away by the minute limit does not use up the daily budget
            return OptionalLong.of((minute + 1) * 60 - now);
        }
        if (dayWindows.get(memberId + ":" + day, k -> new AtomicInteger()).incrementAndGet() > limits.perDay()) {
            return OptionalLong.of((day + 1) * 86_400 - now);
        }
        return OptionalLong.empty();
    }
}

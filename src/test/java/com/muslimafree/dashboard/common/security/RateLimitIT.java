package com.muslimafree.dashboard.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Per-member discovery budget: 3 requests a minute, 5 a day in this test. */
class RateLimitIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        registry.add("app.rate-limit.per-minute", () -> "3");
        registry.add("app.rate-limit.per-day", () -> "5");
    }

    @Test
    void tooManyRequestsInAMinuteAreRejectedUntilTheNextMinute() throws Exception {
        clock.set(Instant.parse("2026-10-06T12:00:15Z"));
        Member viewer = members.create(s -> s.gender = "FEMALE");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/dashboard/members").with(as(viewer))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/dashboard/filters").with(as(viewer)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(45));

        // other members have their own budget
        Member other = members.create(s -> s.gender = "MALE");
        mvc.perform(get("/api/v1/dashboard/members").with(as(other))).andExpect(status().isOk());

        // the rejected request did not use up daily budget: 3 used, 2 left
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(get("/api/v1/dashboard/members").with(as(viewer))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/dashboard/members").with(as(viewer))).andExpect(status().isOk());
        // the daily budget (5) is now used up: wait until midnight UTC
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(get("/api/v1/dashboard/members").with(as(viewer)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", String.valueOf(
                        Duration.between(clock.instant(), Instant.parse("2026-10-07T00:00:00Z")).toSeconds())));
    }

    @Test
    void anonymousRequestsAreRejectedBeforeAnyBudgetIsUsed() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/dashboard/members")).andExpect(status().isUnauthorized());
        }
    }
}

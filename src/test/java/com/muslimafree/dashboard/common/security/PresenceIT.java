package com.muslimafree.dashboard.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Using the dashboard keeps a member "online now", with the profile service's write throttle. */
class PresenceIT extends AbstractIntegrationTest {

    @Test
    void aDashboardRequestRefreshesPresence() throws Exception {
        Member hind = members.create(s -> {
            s.gender = "FEMALE";
            s.lastActiveAt = NOW.minus(Duration.ofHours(3));
        });
        Member abdullah = members.create(s -> s.gender = "MALE");

        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind))).andExpect(status().isOk());

        assertThat(lastActive(hind)).isEqualTo(NOW);
        mvc.perform(get("/api/v1/dashboard/members?online=true").with(as(abdullah)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(hind.id().toString()))
                .andExpect(jsonPath("$.items[0].presence.status").value("ONLINE"));
    }

    @Test
    void writesAreThrottledPerMember() throws Exception {
        Member hind = members.create(s -> {
            s.gender = "FEMALE";
            s.lastActiveAt = NOW.minus(Duration.ofHours(3));
        });
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind))).andExpect(status().isOk());
        assertThat(lastActive(hind)).isEqualTo(NOW);

        clock.advance(Duration.ofSeconds(30));
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind))).andExpect(status().isOk());
        assertThat(lastActive(hind)).isEqualTo(NOW);
    }

    @Test
    void rejectedTokensDoNotCount() throws Exception {
        Member hind = members.create(s -> {
            s.gender = "FEMALE";
            s.lastActiveAt = NOW.minus(Duration.ofHours(3));
        });
        members.revokeSession(hind.sessionId());
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind)))
                .andExpect(status().isUnauthorized());
        assertThat(lastActive(hind)).isEqualTo(NOW.minus(Duration.ofHours(3)));
    }

    private Instant lastActive(Member member) {
        return jdbc.queryForObject("SELECT last_active_at FROM users WHERE id = ?", Timestamp.class,
                member.id().toString()).toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}

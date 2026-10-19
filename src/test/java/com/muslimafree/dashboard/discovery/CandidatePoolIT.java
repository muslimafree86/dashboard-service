package com.muslimafree.dashboard.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/**
 * Ranking works on the {@code candidate-pool} most recently active matching members (here 20, the minimum), so that the cost of a
 * search does not grow with the number of members.
 */
@TestPropertySource(properties = "app.discovery.candidate-pool=20")
class CandidatePoolIT extends AbstractIntegrationTest {

    @Test
    void ranksTheMostRecentlyActiveMatchesAndEndsTheFeedThere() throws Exception {
        Member viewer = members.create(s -> { s.gender = "FEMALE"; s.prefSect = "SUNNI"; });
        List<String> recent = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            long idleMinutes = i * 60L;
            String sect = i % 2 == 0 ? "SHIA" : "SUNNI";
            String id = members.create(s -> { s.gender = "MALE"; s.sect = sect;
                s.lastActiveAt = NOW.minusSeconds(idleMinutes * 60); }).id().toString();
            if (i < 20) {
                recent.add(id);
            }
        }

        String body = mvc.perform(get("/api/v1/dashboard/members?size=50").with(as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.items[*].id");

        assertThat(ids).containsExactlyInAnyOrderElementsOf(recent);
        // ranked inside the pool: the ten Sunni members (what she looks for) first
        assertThat(ids.subList(0, 10)).allMatch(id -> recent.indexOf(id) % 2 == 1);
        assertThat((Boolean) JsonPath.read(body, "$.hasMore")).isFalse();
    }

    @Test
    void filtersApplyBeforeThePoolIsCut() throws Exception {
        Member viewer = members.create(s -> s.gender = "FEMALE");
        for (int i = 0; i < 21; i++) {
            members.create(s -> { s.gender = "MALE"; s.countryCode = "EG"; s.lastActiveAt = NOW; });
        }
        Member saudi = members.create(s -> { s.gender = "MALE"; s.countryCode = "SA";
            s.lastActiveAt = NOW.minusSeconds(86_400 * 20); });

        String body = mvc.perform(get("/api/v1/dashboard/members?country=SA").with(as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(body, "$.items[*].id")).containsExactly(saudi.id().toString());
    }
}

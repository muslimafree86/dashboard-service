package com.muslimafree.dashboard.dailypicks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

@TestPropertySource(properties = {"app.daily-picks.size=3", "app.daily-picks.history=2"})
/** Today's picks: stable for the day, mutual first, no repeats within the history window. */
class DailyPicksIT extends AbstractIntegrationTest {
    private List<String> picks(Member as) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/dashboard/daily-picks").with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
    }

    @Test
    void picksAreStableForTheDayAndMutualMatchesComeFirst() throws Exception {
        Member hind = members.create(s -> { s.gender = "FEMALE"; s.age = 30; s.sect = "SUNNI"; s.prefSect = "SUNNI"; });
        Member mutual = members.create(s -> { s.gender = "MALE"; s.sect = "SUNNI"; s.prefAgeMin = 25; s.prefAgeMax = 35; });
        for (int i = 0; i < 4; i++) {
            members.create(s -> { s.gender = "MALE"; s.sect = "SHIA"; });
        }

        List<String> first = picks(hind);
        assertThat(first).hasSize(3).startsWith(mutual.id().toString());
        members.create(s -> { s.gender = "MALE"; s.sect = "SUNNI"; }); // a better candidate joins later today
        assertThat(picks(hind)).isEqualTo(first);
        mvc.perform(get("/api/v1/dashboard/daily-picks").with(as(hind)))
                .andExpect(jsonPath("$.day").value("2026-10-06"))
                .andExpect(jsonPath("$.nextPicksAt").value("2026-10-07T00:00:00Z"));
    }

    @Test
    void picksDoNotRepeatAndSkipLikedOrHiddenMembers() throws Exception {
        Member hind = members.create(s -> s.gender = "FEMALE");
        Set<String> men = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            men.add(members.create(s -> s.gender = "MALE").id().toString());
        }
        List<String> day1 = picks(hind);
        mvc.perform(put("/api/v1/dashboard/likes/" + day1.get(0)).with(as(hind)));

        clock.advance(Duration.ofDays(1));
        List<String> day2 = picks(hind);
        assertThat(day2).hasSize(3).doesNotContainAnyElementsOf(day1);
        mvc.perform(put("/api/v1/dashboard/hidden-members/" + day2.get(0)).with(as(hind)));
        // the hidden member is gone from today's list at once
        assertThat(picks(hind)).doesNotContain(day2.get(0));

        clock.advance(Duration.ofDays(1));
        List<String> day3 = picks(hind);
        // two days of history: day 1 and day 2 are still excluded, so only 2 men are left
        assertThat(day3).doesNotContainAnyElementsOf(day1).doesNotContainAnyElementsOf(day2).hasSize(2);
        assertThat(men).containsAll(day3);

        clock.advance(Duration.ofDays(2));
        // day 1 is out of the history window again, but the man she liked never comes back
        assertThat(picks(hind)).doesNotContain(day1.get(0)).isNotEmpty();
    }
}

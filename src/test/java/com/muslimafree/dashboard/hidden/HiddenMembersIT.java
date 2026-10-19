package com.muslimafree.dashboard.hidden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** "Not interested": one-way, silent, reversible removal from the caller's own discovery. */
class HiddenMembersIT extends AbstractIntegrationTest {

    private static final String HIDDEN = "/api/v1/dashboard/hidden-members/";

    private Member viewer;
    private Member ahmed;
    private Member omar;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> { s.gender = "FEMALE"; s.age = 30; });
        ahmed = members.create(s -> { s.gender = "MALE"; s.age = 33; });
        omar = members.create(s -> { s.gender = "MALE"; s.age = 34; });
    }

    private List<String> feed(Member as, String query) throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard/members" + query).with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].id");
    }

    @Test
    void aHiddenMemberLeavesFeedTabsAndSimilarButOnlyForTheCaller() throws Exception {
        mvc.perform(put(HIDDEN + ahmed.id()).with(as(viewer))).andExpect(status().isNoContent());

        assertThat(feed(viewer, "")).containsExactly(omar.id().toString());
        assertThat(feed(viewer, "?online=true")).doesNotContain(ahmed.id().toString());
        assertThat(feed(viewer, "?ageMin=33&ageMax=33")).isEmpty();
        String similar = mvc.perform(get("/api/v1/dashboard/members/" + omar.id() + "/similar").with(as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(similar, "$[*].id")).doesNotContain(ahmed.id().toString());
        // one-way: Ahmed still sees her
        assertThat(feed(ahmed, "")).containsExactly(viewer.id().toString());
        // and she can still open the similar strip of the member she hid
        mvc.perform(get("/api/v1/dashboard/members/" + ahmed.id() + "/similar").with(as(viewer)))
                .andExpect(status().isOk());
    }

    @Test
    void hidingIsIdempotentAndReversible() throws Exception {
        mvc.perform(put(HIDDEN + ahmed.id()).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(put(HIDDEN + ahmed.id()).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(delete(HIDDEN + ahmed.id()).with(as(viewer))).andExpect(status().isNoContent());
        mvc.perform(delete(HIDDEN + ahmed.id()).with(as(viewer))).andExpect(status().isNoContent());

        assertThat(feed(viewer, "")).contains(ahmed.id().toString());
    }

    @Test
    void cannotHideMembersOneCannotSee() throws Exception {
        Member hiddenProfile = members.create(s -> { s.gender = "MALE"; s.visibility = "HIDDEN"; });
        Member woman = members.create(s -> s.gender = "FEMALE");

        for (UUID id : List.of(hiddenProfile.id(), woman.id(), viewer.id(), UUID.randomUUID())) {
            mvc.perform(put(HIDDEN + id).with(as(viewer))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_hidden_members", Integer.class)).isZero();
    }

    @Test
    void listShowsHiddenMembersMostRecentFirstAndOnlyThoseStillVisible() throws Exception {
        Member later = members.create(s -> s.gender = "MALE");
        mvc.perform(put(HIDDEN + ahmed.id()).with(as(viewer)));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(put(HIDDEN + omar.id()).with(as(viewer)));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(put(HIDDEN + later.id()).with(as(viewer)));
        jdbc.update("UPDATE user_profiles SET visibility = 'HIDDEN' WHERE user_id = ?", later.id().toString());

        mvc.perform(get("/api/v1/dashboard/hidden-members?size=1").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()) // the newest one hid their profile meanwhile
                .andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/v1/dashboard/hidden-members").with(as(viewer))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].member.id").value(omar.id().toString()))
                .andExpect(jsonPath("$.items[0].hiddenAt").value("2026-10-06T12:01:00Z"))
                .andExpect(jsonPath("$.items[1].member.id").value(ahmed.id().toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/dashboard/hidden-members?size=51").with(as(viewer)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/hidden-members?page=500&size=50").with(as(viewer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_OUT_OF_RANGE"));
    }

    @Test
    void rowsDisappearWithEitherProfile() throws Exception {
        mvc.perform(put(HIDDEN + ahmed.id()).with(as(viewer)));
        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", ahmed.id().toString());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_hidden_members", Integer.class)).isZero();
    }
}

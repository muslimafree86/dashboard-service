package com.muslimafree.dashboard.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Incognito browsing: views by an incognito member are not recorded by user-profile-managment. */
class IncognitoIT extends AbstractIntegrationTest {

    @Test
    void incognitoViewersAreNotRecorded() throws Exception {
        Member hind = members.create(s -> s.gender = "FEMALE");
        Member abdullah = members.create(s -> s.gender = "MALE");
        Member omar = members.create(s -> s.gender = "MALE");
        mvc.perform(get("/api/v1/dashboard/me/settings").with(as(abdullah)))
                .andExpect(jsonPath("$.incognito").value(false));
        mvc.perform(put("/api/v1/dashboard/me/settings").with(as(abdullah)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"incognito\":true}")).andExpect(jsonPath("$.incognito").value(true));

        // what user-profile-managment's recorder does when each of them opens her profile
        members.recordView(abdullah.id(), hind.id(), NOW);
        members.recordView(omar.id(), hind.id(), NOW);

        String viewers = mvc.perform(get("/api/v1/dashboard/activity/viewers").with(as(hind)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(viewers, "$.items[*].member.id")).containsExactly(omar.id().toString());

        mvc.perform(put("/api/v1/dashboard/me/settings").with(as(abdullah)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"incognito\":false}"));
        members.recordView(abdullah.id(), hind.id(), NOW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_profile_views", Integer.class)).isEqualTo(2);
        mvc.perform(put("/api/v1/dashboard/me/settings").with(as(abdullah)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }
}

package com.muslimafree.dashboard.interaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Likes, matches, favourites, profile views, the Home counters and the Activity tab. */
@TestPropertySource(properties = "app.interactions.daily-like-limit=3")
class InteractionsIT extends AbstractIntegrationTest {

    private Member hind;
    private Member abdullah;
    private Member omar;

    @BeforeEach
    void cast() {
        hind = members.create(s -> { s.gender = "FEMALE"; s.firstName = "هند"; });
        abdullah = members.create(s -> { s.gender = "MALE"; s.firstName = "عبدالله"; });
        omar = members.create(s -> { s.gender = "MALE"; s.firstName = "عمر"; });
    }

    private static String like(Member target) {
        return "/api/v1/dashboard/likes/" + target.id();
    }

    private List<String> activity(Member as, String list) throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard/activity/" + list).with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].member.id");
    }

    @Test
    void aLikeIsSeenByTheLikedMemberAndTwoLikesMakeAMatch() throws Exception {
        mvc.perform(put(like(abdullah)).with(as(hind))).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false));

        assertThat(activity(abdullah, "likes-received")).containsExactly(hind.id().toString());
        assertThat(activity(hind, "likes-sent")).containsExactly(abdullah.id().toString());
        assertThat(activity(hind, "matches")).isEmpty();
        mvc.perform(get("/api/v1/dashboard/members/" + hind.id() + "/interactions").with(as(abdullah)))
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.likedYou").value(true))
                .andExpect(jsonPath("$.matched").value(false));

        clock.advance(Duration.ofMinutes(5));
        mvc.perform(put(like(hind)).with(as(abdullah))).andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));

        assertThat(activity(hind, "matches")).containsExactly(abdullah.id().toString());
        mvc.perform(get("/api/v1/dashboard/activity/matches").with(as(abdullah)))
                .andExpect(jsonPath("$.items[0].member.id").value(hind.id().toString()))
                .andExpect(jsonPath("$.items[0].at").value("2026-10-06T12:05:00Z"));
        mvc.perform(get("/api/v1/dashboard/members/" + abdullah.id() + "/interactions").with(as(hind)))
                .andExpect(jsonPath("$.matched").value(true));
    }

    @Test
    void likingIsIdempotentAndUnlikingEndsTheMatch() throws Exception {
        mvc.perform(put(like(abdullah)).with(as(hind)));
        mvc.perform(put(like(abdullah)).with(as(hind))).andExpect(status().isOk());
        mvc.perform(put(like(hind)).with(as(abdullah)));
        assertThat(activity(hind, "matches")).hasSize(1);

        mvc.perform(delete(like(abdullah)).with(as(hind))).andExpect(status().isNoContent());
        mvc.perform(delete(like(abdullah)).with(as(hind))).andExpect(status().isNoContent());

        assertThat(activity(hind, "matches")).isEmpty();
        assertThat(activity(hind, "likes-received")).containsExactly(abdullah.id().toString());
    }

    @Test
    void favouritesArePrivate() throws Exception {
        mvc.perform(put("/api/v1/dashboard/favourites/" + abdullah.id()).with(as(hind)))
                .andExpect(status().isNoContent());

        assertThat(activity(hind, "favourites")).containsExactly(abdullah.id().toString());
        assertThat(activity(abdullah, "likes-received")).isEmpty();
        mvc.perform(get("/api/v1/dashboard/members/" + hind.id() + "/interactions").with(as(abdullah)))
                .andExpect(jsonPath("$.favourite").value(false));
        mvc.perform(get("/api/v1/dashboard/activity").with(as(abdullah)))
                .andExpect(jsonPath("$.favourites").value(0));

        mvc.perform(delete("/api/v1/dashboard/favourites/" + abdullah.id()).with(as(hind)))
                .andExpect(status().isNoContent());
        assertThat(activity(hind, "favourites")).isEmpty();
    }

    @Test
    void profileViewsRecordedByTheProfileServiceAppearAsWhoViewedMe() throws Exception {
        members.recordView(abdullah.id(), hind.id(), NOW.minusSeconds(3600));
        members.recordView(omar.id(), hind.id(), NOW.minusSeconds(600));
        members.recordView(abdullah.id(), hind.id(), NOW.minusSeconds(60)); // viewed again: one row, moved up

        mvc.perform(get("/api/v1/dashboard/activity/viewers").with(as(hind))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].member.id").value(abdullah.id().toString()))
                .andExpect(jsonPath("$.items[0].at").value("2026-10-06T11:59:00Z"))
                .andExpect(jsonPath("$.items[1].member.id").value(omar.id().toString()));
        assertThat(jdbc.queryForObject("SELECT view_count FROM dashboard_profile_views WHERE viewer_id = ?",
                Integer.class, abdullah.id().toString())).isEqualTo(2);
    }

    @Test
    void homeCountersCountOnlyMembersOneMayStillSee() throws Exception {
        mvc.perform(put(like(hind)).with(as(abdullah)));
        mvc.perform(put(like(hind)).with(as(omar)));
        mvc.perform(put(like(abdullah)).with(as(hind)));
        mvc.perform(put("/api/v1/dashboard/favourites/" + omar.id()).with(as(hind)));
        members.recordView(abdullah.id(), hind.id(), NOW);
        members.recordView(omar.id(), hind.id(), NOW);

        mvc.perform(get("/api/v1/dashboard/activity").with(as(hind))).andExpect(status().isOk())
                .andExpect(jsonPath("$.likesReceived").value(2))
                .andExpect(jsonPath("$.matches").value(1))
                .andExpect(jsonPath("$.favourites").value(1))
                .andExpect(jsonPath("$.profileViews").value(2));

        // Omar hides his profile, then blocks her: he disappears from every count and list, nothing is deleted
        jdbc.update("UPDATE user_profiles SET visibility = 'HIDDEN' WHERE user_id = ?", omar.id().toString());
        mvc.perform(get("/api/v1/dashboard/activity").with(as(hind)))
                .andExpect(jsonPath("$.likesReceived").value(1))
                .andExpect(jsonPath("$.favourites").value(0))
                .andExpect(jsonPath("$.profileViews").value(1));
        assertThat(activity(hind, "likes-received")).containsExactly(abdullah.id().toString());
        jdbc.update("UPDATE user_profiles SET visibility = 'VISIBLE' WHERE user_id = ?", omar.id().toString());
        members.block(omar.id(), hind.id());
        assertThat(activity(hind, "likes-received")).containsExactly(abdullah.id().toString());
        assertThat(activity(hind, "viewers")).containsExactly(abdullah.id().toString());
    }

    @Test
    void cannotLikeOrFavouriteMembersOneCannotSee() throws Exception {
        Member hiddenProfile = members.create(s -> { s.gender = "MALE"; s.visibility = "HIDDEN"; });
        Member woman = members.create(s -> s.gender = "FEMALE");
        members.block(omar.id(), hind.id());

        for (UUID id : List.of(hiddenProfile.id(), woman.id(), omar.id(), hind.id(), UUID.randomUUID())) {
            mvc.perform(put("/api/v1/dashboard/likes/" + id).with(as(hind))).andExpect(status().isNotFound());
            mvc.perform(put("/api/v1/dashboard/favourites/" + id).with(as(hind))).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/dashboard/members/" + id + "/interactions").with(as(hind)))
                    .andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_likes", Integer.class)).isZero();
    }

    @Test
    void likingAMemberMarkedNotInterestedBringsThemBack() throws Exception {
        mvc.perform(put("/api/v1/dashboard/hidden-members/" + abdullah.id()).with(as(hind)));
        mvc.perform(put(like(abdullah)).with(as(hind))).andExpect(status().isOk());

        String feed = mvc.perform(get("/api/v1/dashboard/members").with(as(hind)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(feed, "$.items[*].id")).contains(abdullah.id().toString());
    }

    @Test
    void dailyLikeLimit() throws Exception {
        List<Member> men = List.of(abdullah, omar, members.create(s -> s.gender = "MALE"),
                members.create(s -> s.gender = "MALE"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(put(like(men.get(i))).with(as(hind))).andExpect(status().isOk());
        }
        // re-liking costs nothing; a new like is refused until midnight UTC
        mvc.perform(put(like(men.get(0))).with(as(hind))).andExpect(status().isOk());
        mvc.perform(put(like(men.get(3))).with(as(hind)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_LIKE_LIMIT_REACHED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(12 * 3600));

        clock.advance(Duration.ofHours(12));
        mvc.perform(put(like(men.get(3))).with(as(hind))).andExpect(status().isOk());
    }

    @Test
    void listsArePagedAndValidated() throws Exception {
        for (int i = 0; i < 5; i++) {
            Member man = members.create(s -> s.gender = "MALE");
            clock.advance(Duration.ofSeconds(1));
            members.recordView(man.id(), hind.id(), clock.instant());
        }
        mvc.perform(get("/api/v1/dashboard/activity/viewers?size=2").with(as(hind)))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasMore").value(true));
        mvc.perform(get("/api/v1/dashboard/activity/viewers?size=2&page=2").with(as(hind)))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/dashboard/activity/admirers").with(as(hind))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dashboard/activity/viewers?size=0").with(as(hind))).andExpect(status().isBadRequest());
    }

    @Test
    void rowsDisappearWithEitherProfile() throws Exception {
        mvc.perform(put(like(abdullah)).with(as(hind)));
        mvc.perform(put("/api/v1/dashboard/favourites/" + abdullah.id()).with(as(hind)));
        members.recordView(abdullah.id(), hind.id(), NOW);
        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", abdullah.id().toString());

        for (String table : List.of("dashboard_likes", "dashboard_favourites", "dashboard_profile_views")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    @Test
    void anonymousCallsAreRejected() throws Exception {
        mvc.perform(put(like(abdullah))).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get("/api/v1/dashboard/activity")).andExpect(status().isUnauthorized());
    }
}

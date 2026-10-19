package com.muslimafree.dashboard.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** "اعضاء مشابهون" under a member's profile. */
class SimilarMembersIT extends AbstractIntegrationTest {

    private Member viewer;
    private Member reference;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> s.gender = "FEMALE");
        reference = members.create(s -> { s.gender = "MALE"; s.age = 35; s.sect = "SUNNI"; s.education = "MASTER";
            s.smoking = "NO"; s.region = "القاهرة"; });
    }

    private List<String> similar(Member as, UUID memberId) throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard/members/" + memberId + "/similar").with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].id");
    }

    @Test
    void mostSimilarFirstAndNeverTheMemberThemselves() throws Exception {
        Member twin = members.create(s -> { s.gender = "MALE"; s.age = 36; s.sect = "SUNNI"; s.education = "MASTER";
            s.smoking = "NO"; s.region = "القاهرة"; });
        Member cousin = members.create(s -> { s.gender = "MALE"; s.age = 33; s.sect = "SUNNI"; s.region = "الجيزة"; });

        assertThat(similar(viewer, reference.id())).containsExactly(twin.id().toString(), cousin.id().toString());
    }

    @Test
    void sameCountryAndSimilarAgeOnly() throws Exception {
        members.create(s -> { s.gender = "MALE"; s.age = 35; s.countryCode = "SA"; });
        members.create(s -> { s.gender = "MALE"; s.age = 41; });
        members.create(s -> { s.gender = "MALE"; s.age = 29; });
        Member edge = members.create(s -> { s.gender = "MALE"; s.age = 40; });

        assertThat(similar(viewer, reference.id())).containsExactly(edge.id().toString());
    }

    @Test
    void respectsTheViewersEligibilityRules() throws Exception {
        Member blocked = members.create(s -> { s.gender = "MALE"; s.age = 35; });
        members.block(blocked.id(), viewer.id());
        members.create(s -> { s.gender = "MALE"; s.age = 35; s.visibility = "HIDDEN"; });

        assertThat(similar(viewer, reference.id())).isEmpty();
    }

    @Test
    void aMemberTheViewerMayNotSeeIsNotFound() throws Exception {
        Member hidden = members.create(s -> { s.gender = "MALE"; s.visibility = "HIDDEN"; });
        members.block(viewer.id(), reference.id());

        for (UUID id : List.of(hidden.id(), reference.id(), UUID.randomUUID(), viewer.id())) {
            mvc.perform(get("/api/v1/dashboard/members/" + id + "/similar").with(as(viewer)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        }
    }

    @Test
    void malformedIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/dashboard/members/not-a-uuid/similar").with(as(viewer)))
                .andExpect(status().isBadRequest());
    }
}

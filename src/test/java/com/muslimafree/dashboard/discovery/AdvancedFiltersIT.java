package com.muslimafree.dashboard.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Advanced search on attributes every profile has, and the "new members" order. */
class AdvancedFiltersIT extends AbstractIntegrationTest {

    private Member viewer;
    private Member single;
    private Member divorced;
    private Member silent;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> s.gender = "FEMALE");
        single = members.create(s -> { s.gender = "MALE"; s.maritalStatus = "SINGLE"; s.education = "MASTER";
            s.sect = "SUNNI"; s.smoking = "NO"; s.children = "NONE"; s.nationality = "EG";
            s.createdAt = NOW.minus(Duration.ofDays(300)); });
        divorced = members.create(s -> { s.gender = "MALE"; s.maritalStatus = "DIVORCED"; s.education = "DIPLOMA";
            s.sect = "SHIA"; s.smoking = "SOMETIMES"; s.children = "HAS_CHILDREN"; s.nationality = "SA";
            s.createdAt = NOW.minus(Duration.ofDays(2)); });
        // filled in nothing optional
        silent = members.create(s -> { s.gender = "MALE"; s.maritalStatus = null; s.nationality = "EG";
            s.createdAt = NOW.minus(Duration.ofDays(30)); });
    }

    private List<String> ids(String query) throws Exception {
        String body = mvc.perform(get("/api/v1/dashboard/members?" + query).with(as(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].id");
    }

    private static String id(Member m) {
        return m.id().toString();
    }

    @Test
    void eachFilterIsStrictAboutAttributesNotFilledIn() throws Exception {
        assertThat(ids("maritalStatus=SINGLE")).containsExactly(id(single));
        assertThat(ids("educationMin=BACHELOR")).containsExactly(id(single));
        assertThat(ids("educationMin=DIPLOMA")).containsExactlyInAnyOrder(id(single), id(divorced));
        assertThat(ids("sect=SHIA")).containsExactly(id(divorced));
        assertThat(ids("smoking=NO")).containsExactly(id(single));
        assertThat(ids("children=HAS_CHILDREN")).containsExactly(id(divorced));
        assertThat(ids("nationality=eg")).containsExactlyInAnyOrder(id(single), id(silent));
    }

    @Test
    void multipleValuesOfOneFilterAreAlternatives() throws Exception {
        assertThat(ids("maritalStatus=SINGLE&maritalStatus=DIVORCED")).containsExactlyInAnyOrder(id(single), id(divorced));
        assertThat(ids("smoking=NO,SOMETIMES")).containsExactlyInAnyOrder(id(single), id(divorced));
    }

    @Test
    void differentFiltersCombine() throws Exception {
        assertThat(ids("nationality=EG&educationMin=MASTER")).containsExactly(id(single));
        assertThat(ids("nationality=EG&sect=SHIA")).isEmpty();
    }

    @Test
    void withPhotoCountsApprovedPhotosOnly() throws Exception {
        members.addPhoto(single.id(), "APPROVED", true, NOW);
        members.addPhoto(divorced.id(), "PENDING_REVIEW", true, NOW);

        assertThat(ids("withPhoto=true")).containsExactly(id(single));
    }

    @Test
    void newestMembersFirst() throws Exception {
        assertThat(ids("sort=NEWEST")).containsExactly(id(divorced), id(silent), id(single));
        String first = mvc.perform(get("/api/v1/dashboard/members?sort=NEWEST&size=1").with(as(viewer)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(ids("sort=NEWEST&size=2&cursor=" + cursor)).containsExactly(id(silent), id(single));
    }

    @Test
    void invalidValuesAreRejectedAndCursorsBoundToFilters() throws Exception {
        for (String query : List.of("maritalStatus=ENGAGED", "sect=sunni2", "educationMin=PHD", "children=MAYBE",
                "nationality=EGY", "smoking=NO&smoking=YES&smoking=SOMETIMES&smoking=NO")) {
            mvc.perform(get("/api/v1/dashboard/members?" + query).with(as(viewer)))
                    .andExpect(status().isBadRequest());
        }
        String first = mvc.perform(get("/api/v1/dashboard/members?size=1&sect=SUNNI,SHIA").with(as(viewer)))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        // the same values in another order are the same search; another value is not
        mvc.perform(get("/api/v1/dashboard/members?size=1&sect=SHIA,SUNNI&cursor=" + cursor).with(as(viewer)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/dashboard/members?size=1&sect=SHIA&cursor=" + cursor).with(as(viewer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }
}

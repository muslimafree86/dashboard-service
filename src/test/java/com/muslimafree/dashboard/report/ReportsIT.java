package com.muslimafree.dashboard.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;
import com.muslimafree.dashboard.support.TestTokens;

/** Reporting a member, and the moderators' queue. */
@TestPropertySource(properties = "app.reports.max-per-day=3")
class ReportsIT extends AbstractIntegrationTest {

    private Member hind;
    private Member abdullah;
    private Member moderator;

    @BeforeEach
    void cast() {
        hind = members.create(s -> s.gender = "FEMALE");
        abdullah = members.create(s -> s.gender = "MALE");
        moderator = members.create(s -> { s.gender = "MALE"; s.onboardingStep = "BASIC_INFO"; });
    }

    private ResultActions report(Member from, UUID target, String body) throws Exception {
        return mvc.perform(post("/api/v1/dashboard/reports/" + target).with(as(from))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String staff(String roles) {
        return TestTokens.withRoles(moderator.id(), moderator.sessionId(), List.of(roles.split(",")));
    }

    @Test
    void aReportReachesTheModeratorsAndHidesTheMemberFromTheReporter() throws Exception {
        report(hind, abdullah.id(), "{\"reason\":\"HARASSMENT\",\"context\":\"MESSAGE\",\"details\":\"رسائل مزعجة\"}")
                .andExpect(status().isAccepted());

        String feed = mvc.perform(get("/api/v1/dashboard/members").with(as(hind)))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(feed, "$.items")).isEmpty();

        mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(staff("MEMBER,MODERATOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reporterId").value(hind.id().toString()))
                .andExpect(jsonPath("$[0].reportedId").value(abdullah.id().toString()))
                .andExpect(jsonPath("$[0].reason").value("HARASSMENT"))
                .andExpect(jsonPath("$[0].context").value("MESSAGE"))
                .andExpect(jsonPath("$[0].details").value("رسائل مزعجة"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].openReportsAgainstMember").value(1));
    }

    @Test
    void moderatorsCloseReports() throws Exception {
        report(hind, abdullah.id(), "{\"reason\":\"FAKE_PROFILE\"}");
        String admin = staff("MEMBER,ADMIN");
        String id = JsonPath.read(mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(admin)))
                .andReturn().getResponse().getContentAsString(), "$[0].id");

        mvc.perform(patch("/api/v1/dashboard/admin/reports/" + id).with(bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIONED\",\"note\":\"account suspended\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIONED"))
                .andExpect(jsonPath("$.reviewedBy").value(moderator.id().toString()))
                .andExpect(jsonPath("$.resolutionNote").value("account suspended"));
        mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(admin))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/dashboard/admin/reports?status=ACTIONED&reportedId=" + abdullah.id())
                .with(bearer(admin))).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(patch("/api/v1/dashboard/admin/reports/" + UUID.randomUUID()).with(bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void managersAndAdminsWorkTheQueueToo() throws Exception {
        report(hind, abdullah.id(), "{\"reason\":\"FAKE_PROFILE\"}");
        // user-profile-managment issues each staff member one staff role besides MEMBER
        for (String roles : List.of("MEMBER,MANAGER", "MEMBER,ADMIN")) {
            mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(staff(roles))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].reportedId").value(abdullah.id().toString()));
        }
        String id = JsonPath.read(mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(staff("MEMBER,MANAGER"))))
                .andReturn().getResponse().getContentAsString(), "$[0].id");
        mvc.perform(patch("/api/v1/dashboard/admin/reports/" + id).with(bearer(staff("MEMBER,MANAGER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void membersCannotSeeTheQueue() throws Exception {
        mvc.perform(get("/api/v1/dashboard/admin/reports").with(as(hind))).andExpect(status().isForbidden());
    }

    @Test
    void theAnswerNeverRevealsWhetherAMemberExists() throws Exception {
        report(hind, UUID.randomUUID(), "{\"reason\":\"SCAM\"}").andExpect(status().isAccepted());
        report(hind, hind.id(), "{\"reason\":\"SCAM\"}").andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_reports", Integer.class)).isZero();
    }

    @Test
    void validationAndDailyLimit() throws Exception {
        report(hind, abdullah.id(), "{}").andExpect(status().isBadRequest());
        report(hind, abdullah.id(), "{\"reason\":\"BORING\"}").andExpect(status().isBadRequest());
        report(hind, abdullah.id(), "{\"reason\":\"OTHER\",\"details\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());
        for (int i = 0; i < 3; i++) {
            report(hind, abdullah.id(), "{\"reason\":\"OTHER\"}").andExpect(status().isAccepted());
        }
        report(hind, abdullah.id(), "{\"reason\":\"OTHER\"}").andExpect(status().isTooManyRequests());
    }

    @Test
    void reportsSurviveTheReportersAccountDeletion() throws Exception {
        report(hind, abdullah.id(), "{\"reason\":\"SCAM\"}");
        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", hind.id().toString());

        mvc.perform(get("/api/v1/dashboard/admin/reports").with(bearer(staff("MEMBER,MODERATOR"))))
                .andExpect(jsonPath("$[0].reportedId").value(abdullah.id().toString()))
                .andExpect(jsonPath("$[0].reporterId").doesNotExist());
    }
}

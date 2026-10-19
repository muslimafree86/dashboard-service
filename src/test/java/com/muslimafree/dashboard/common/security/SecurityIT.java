package com.muslimafree.dashboard.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;
import com.muslimafree.dashboard.support.TestTokens;

/** Only a signed-in member with a live session gets a dashboard, and it is always their own. */
class SecurityIT extends AbstractIntegrationTest {

    private static final String FEED = "/api/v1/dashboard/members";

    private Member viewer;
    private Member man;

    @BeforeEach
    void cast() {
        viewer = members.create(s -> s.gender = "FEMALE");
        man = members.create(s -> s.gender = "MALE");
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get(FEED)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/dashboard/filters")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard/members/" + man.id() + "/similar")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/dashboard/me/location").contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":30,\"longitude\":31}")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenIsAccepted() throws Exception {
        mvc.perform(get(FEED).with(as(viewer))).andExpect(status().isOk());
    }

    @Test
    void tokensFromAnotherIssuerOrKeyOrExpiredAreRejected() throws Exception {
        mvc.perform(get(FEED).with(bearer(TestTokens.withIssuer("https://evil.example", viewer.id(), viewer.sessionId()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(FEED).with(bearer(TestTokens.signedByAnotherKey(viewer.id(), viewer.sessionId()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(FEED).with(bearer(TestTokens.expired(viewer.id(), viewer.sessionId()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(FEED).with(bearer("not.a.jwt"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aSignedOutSessionStopsWorkingImmediately() throws Exception {
        members.revokeSession(viewer.sessionId());

        mvc.perform(get(FEED).with(as(viewer))).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountCannotBrowse() throws Exception {
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", viewer.id().toString());

        mvc.perform(get(FEED).with(as(viewer))).andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionOfAnotherMemberCannotBeBorrowed() throws Exception {
        // valid signature, but sub and sid do not belong together
        mvc.perform(get(FEED).with(bearer(TestTokens.accessToken(viewer.id(), man.sessionId()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(FEED).with(bearer(TestTokens.accessToken(viewer.id(), UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutTheMemberRoleIsForbidden() throws Exception {
        mvc.perform(get(FEED).with(bearer(TestTokens.withoutRoles(viewer.id(), viewer.sessionId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void theDashboardOwnerCannotBeChosenByTheCaller() throws Exception {
        // a userId parameter is simply not part of the API: the man's own feed is returned, not the viewer's
        mvc.perform(get(FEED + "?userId=" + viewer.id()).with(as(man))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(viewer.id().toString()));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/api/v1/members/" + man.id()).with(as(viewer))).andExpect(status().isForbidden());
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }
}

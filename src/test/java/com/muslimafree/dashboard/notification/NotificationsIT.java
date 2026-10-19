package com.muslimafree.dashboard.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** The header bell: likes, matches and messages, read state, visibility. */
class NotificationsIT extends AbstractIntegrationTest {

    private Member hind;
    private Member abdullah;

    @BeforeEach
    void cast() {
        hind = members.create(s -> s.gender = "FEMALE");
        abdullah = members.create(s -> s.gender = "MALE");
    }

    private String bell(Member as) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/notifications").with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void like(Member from, Member to) throws Exception {
        mvc.perform(put("/api/v1/dashboard/likes/" + to.id()).with(as(from))).andExpect(status().isOk());
    }

    @Test
    void aLikeThenAMatch() throws Exception {
        like(abdullah, hind);
        String forHind = bell(hind);
        assertThat(JsonPath.<List<String>>read(forHind, "$.items[*].type")).containsExactly("LIKE_RECEIVED");
        assertThat(JsonPath.<String>read(forHind, "$.items[0].actor.id")).isEqualTo(abdullah.id().toString());
        assertThat(JsonPath.<Integer>read(forHind, "$.unread")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(bell(abdullah), "$.items")).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        like(hind, abdullah);
        assertThat(JsonPath.<List<String>>read(bell(hind), "$.items[*].type")).containsExactly("MATCH", "LIKE_RECEIVED");
        assertThat(JsonPath.<List<String>>read(bell(abdullah), "$.items[*].type"))
                .containsExactly("MATCH", "LIKE_RECEIVED");
    }

    @Test
    void takingBackALikeRemovesItsNotifications() throws Exception {
        like(abdullah, hind);
        like(hind, abdullah);
        mvc.perform(delete("/api/v1/dashboard/likes/" + hind.id()).with(as(abdullah)));

        assertThat(JsonPath.<List<String>>read(bell(hind), "$.items[*].type")).isEmpty();
        assertThat(JsonPath.<List<String>>read(bell(abdullah), "$.items[*].type")).containsExactly("LIKE_RECEIVED");
    }

    @Test
    void repeatedEventsDoNotPileUp() throws Exception {
        like(abdullah, hind);
        mvc.perform(delete("/api/v1/dashboard/likes/" + hind.id()).with(as(abdullah)));
        like(abdullah, hind);
        like(abdullah, hind);

        assertThat(JsonPath.<List<String>>read(bell(hind), "$.items")).hasSize(1);
    }

    @Test
    void messagesNotifyOncePerConversationUntilRead() throws Exception {
        like(abdullah, hind);
        like(hind, abdullah);
        mvc.perform(put("/api/v1/dashboard/notifications/read").with(as(hind))).andExpect(status().isNoContent());
        for (String text : List.of("السلام عليكم", "كيف حالك؟")) {
            mvc.perform(post("/api/v1/dashboard/conversations/" + hind.id() + "/messages").with(as(abdullah))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + text + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/v1/dashboard/notifications/unread").with(as(hind)))
                .andExpect(jsonPath("$.unread").value(1));
        assertThat(JsonPath.<List<String>>read(bell(hind), "$.items[?(@.read == false)].type"))
                .containsExactly("NEW_MESSAGE");

        mvc.perform(put("/api/v1/dashboard/conversations/" + abdullah.id() + "/read").with(as(hind)));
        mvc.perform(get("/api/v1/dashboard/notifications/unread").with(as(hind)))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void readingOneOrAll() throws Exception {
        like(abdullah, hind);
        Member omar = members.create(s -> s.gender = "MALE");
        like(omar, hind);
        String id = JsonPath.read(bell(hind), "$.items[0].id");

        mvc.perform(put("/api/v1/dashboard/notifications/" + id + "/read").with(as(hind)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/dashboard/notifications/unread").with(as(hind))).andExpect(jsonPath("$.unread").value(1));
        // someone else's notification id is not found
        mvc.perform(put("/api/v1/dashboard/notifications/" + id + "/read").with(as(abdullah)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/dashboard/notifications/" + UUID.randomUUID() + "/read").with(as(hind)))
                .andExpect(status().isNotFound());

        mvc.perform(put("/api/v1/dashboard/notifications/read").with(as(hind)));
        mvc.perform(get("/api/v1/dashboard/notifications/unread").with(as(hind))).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void notificationsFromMembersOneCannotSeeAreHidden() throws Exception {
        like(abdullah, hind);
        members.block(abdullah.id(), hind.id());

        assertThat(JsonPath.<List<String>>read(bell(hind), "$.items")).isEmpty();
        mvc.perform(get("/api/v1/dashboard/notifications/unread").with(as(hind))).andExpect(jsonPath("$.unread").value(0));
    }
}

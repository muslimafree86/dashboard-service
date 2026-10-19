package com.muslimafree.dashboard.messaging;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;

/** Messaging between matched members: inbox / sent, chat thread, polling, unread, gating, visibility. */
@TestPropertySource(properties = {"app.messaging.max-per-minute=5", "app.rate-limit.per-minute=3"})
class MessagingIT extends AbstractIntegrationTest {

    private Member hind;
    private Member abdullah;
    private Member omar;

    @BeforeEach
    void cast() throws Exception {
        hind = members.create(s -> { s.gender = "FEMALE"; s.firstName = "هند"; });
        abdullah = members.create(s -> { s.gender = "MALE"; s.firstName = "عبدالله"; });
        omar = members.create(s -> s.gender = "MALE");
        match(hind, abdullah);
    }

    private void match(Member a, Member b) throws Exception {
        mvc.perform(put("/api/v1/dashboard/likes/" + b.id()).with(as(a))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/dashboard/likes/" + a.id()).with(as(b))).andExpect(status().isOk());
    }

    private ResultActions send(Member from, Member to, String body) throws Exception {
        return mvc.perform(post("/api/v1/dashboard/conversations/" + to.id() + "/messages").with(as(from))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":" + json(body) + "}"));
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String thread(Member as, Member with, String query) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/conversations/" + with.id() + "/messages" + query).with(as(as)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<String> bodies(String thread) {
        return JsonPath.read(thread, "$.messages[*].body");
    }

    @Test
    void matchedMembersExchangeMessages() throws Exception {
        send(hind, abdullah, "السلام عليكم و رحمة الله").andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromMe").value(true))
                .andExpect(jsonPath("$.sentAt").value("2026-10-06T12:00:00Z"));
        clock.advance(Duration.ofMinutes(3));
        send(abdullah, hind, "  عليكم السلام و رحمة الله  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("عليكم السلام و رحمة الله"));

        String forHind = thread(hind, abdullah, "");
        assertThat(bodies(forHind)).containsExactly("السلام عليكم و رحمة الله", "عليكم السلام و رحمة الله");
        assertThat(JsonPath.<List<Boolean>>read(forHind, "$.messages[*].fromMe")).containsExactly(true, false);
        assertThat(JsonPath.<String>read(forHind, "$.member.firstName")).isEqualTo("عبدالله");
        assertThat(JsonPath.<Boolean>read(forHind, "$.canSend")).isTrue();
        // the other side sees the same thread from its own point of view
        assertThat(JsonPath.<List<Boolean>>read(thread(abdullah, hind, ""), "$.messages[*].fromMe"))
                .containsExactly(false, true);
    }

    @Test
    void onlyMatchesMayWrite() throws Exception {
        send(hind, omar, "مرحبا").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_MATCHED"));
        // a one-way like is not enough
        mvc.perform(put("/api/v1/dashboard/likes/" + omar.id()).with(as(hind)));
        send(hind, omar, "مرحبا").andExpect(status().isForbidden());
        send(omar, hind, "مرحبا").andExpect(status().isForbidden());

        thread(hind, omar, ""); // an empty thread can still be opened from a profile
        assertThat(JsonPath.<Boolean>read(thread(hind, omar, ""), "$.canSend")).isFalse();
    }

    @Test
    void unlikingClosesTheConversationButKeepsTheHistory() throws Exception {
        send(hind, abdullah, "السلام عليكم");
        mvc.perform(delete("/api/v1/dashboard/likes/" + hind.id()).with(as(abdullah)));

        send(hind, abdullah, "هل انت هنا؟").andExpect(status().isForbidden());
        String history = thread(hind, abdullah, "");
        assertThat(bodies(history)).containsExactly("السلام عليكم");
        assertThat(JsonPath.<Boolean>read(history, "$.canSend")).isFalse();
        mvc.perform(get("/api/v1/dashboard/conversations?box=SENT").with(as(hind)))
                .andExpect(jsonPath("$.items[0].canSend").value(false));
    }

    @Test
    void aBlockHidesTheConversationForBoth() throws Exception {
        send(hind, abdullah, "السلام عليكم");
        members.block(abdullah.id(), hind.id());

        for (Member[] pair : new Member[][] {{hind, abdullah}, {abdullah, hind}}) {
            mvc.perform(get("/api/v1/dashboard/conversations/" + pair[1].id() + "/messages").with(as(pair[0])))
                    .andExpect(status().isNotFound());
            send(pair[0], pair[1], "...").andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/dashboard/conversations?box=ALL").with(as(pair[0])))
                    .andExpect(jsonPath("$.items").isEmpty());
        }
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(abdullah)))
                .andExpect(jsonPath("$.unreadMessages").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_messages", Integer.class)).isEqualTo(1);
    }

    @Test
    void inboxAndSentTabsFollowWhoWroteLast() throws Exception {
        match(hind, omar);
        send(hind, abdullah, "أنا كتبت آخر رسالة هنا");
        clock.advance(Duration.ofMinutes(1));
        send(omar, hind, "وعمر كتب آخر رسالة هنا");

        mvc.perform(get("/api/v1/dashboard/conversations").with(as(hind))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].member.id").value(omar.id().toString()))
                .andExpect(jsonPath("$.items[0].lastMessagePreview").value("وعمر كتب آخر رسالة هنا"))
                .andExpect(jsonPath("$.items[0].lastMessageFromMe").value(false))
                .andExpect(jsonPath("$.items[0].unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].canSend").value(true));
        mvc.perform(get("/api/v1/dashboard/conversations?box=SENT").with(as(hind)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].member.id").value(abdullah.id().toString()))
                .andExpect(jsonPath("$.items[0].lastMessageFromMe").value(true))
                .andExpect(jsonPath("$.items[0].unreadCount").value(0));
        mvc.perform(get("/api/v1/dashboard/conversations?box=ALL").with(as(hind)))
                .andExpect(jsonPath("$.items[0].member.id").value(omar.id().toString()))
                .andExpect(jsonPath("$.items[1].member.id").value(abdullah.id().toString()));
        mvc.perform(get("/api/v1/dashboard/conversations?box=SPAM").with(as(hind))).andExpect(status().isBadRequest());
    }

    @Test
    void unreadCountsAndReading() throws Exception {
        send(abdullah, hind, "1");
        send(abdullah, hind, "2");
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind)))
                .andExpect(jsonPath("$.unreadMessages").value(2))
                .andExpect(jsonPath("$.unreadConversations").value(1));
        mvc.perform(get("/api/v1/dashboard/activity").with(as(hind)))
                .andExpect(jsonPath("$.unreadMessages").value(2));
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(abdullah)))
                .andExpect(jsonPath("$.unreadMessages").value(0));

        mvc.perform(put("/api/v1/dashboard/conversations/" + abdullah.id() + "/read").with(as(hind)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind)))
                .andExpect(jsonPath("$.unreadMessages").value(0));
        // replying also counts as having read
        send(abdullah, hind, "3");
        send(hind, abdullah, "رد");
        mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind)))
                .andExpect(jsonPath("$.unreadMessages").value(0));
    }

    @Test
    void historyPagingAndPollingForNewMessages() throws Exception {
        for (int i = 1; i <= 4; i++) {
            send(i % 2 == 0 ? abdullah : hind, i % 2 == 0 ? hind : abdullah, "m" + i);
            clock.advance(Duration.ofSeconds(61)); // stay under the per-minute send limit
        }
        String latest = thread(hind, abdullah, "?limit=2");
        assertThat(bodies(latest)).containsExactly("m3", "m4");
        assertThat(JsonPath.<Boolean>read(latest, "$.hasMore")).isTrue();
        String firstId = JsonPath.read(latest, "$.messages[0].id");
        String older = thread(hind, abdullah, "?limit=2&before=" + firstId);
        assertThat(bodies(older)).containsExactly("m1", "m2");
        assertThat(JsonPath.<Boolean>read(older, "$.hasMore")).isFalse();

        String lastId = JsonPath.read(latest, "$.messages[1].id");
        assertThat(bodies(thread(hind, abdullah, "?after=" + lastId))).isEmpty();
        send(abdullah, hind, "m5");
        assertThat(bodies(thread(hind, abdullah, "?after=" + lastId))).containsExactly("m5");

        // a cursor from another conversation is refused
        match(hind, omar);
        send(omar, hind, "x");
        mvc.perform(get("/api/v1/dashboard/conversations/" + omar.id() + "/messages?after=" + lastId).with(as(hind)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void sendLimitAndPollingIsNotThrottledByTheBrowsingBudget() throws Exception {
        for (int i = 0; i < 5; i++) {
            send(hind, abdullah, "رسالة " + i).andExpect(status().isCreated());
        }
        send(hind, abdullah, "واحدة أكثر").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("MESSAGE_RATE_LIMITED"));
        // browsing budget is 3/minute in this test, polling is far above it and still served
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/dashboard/conversations/unread").with(as(hind))).andExpect(status().isOk());
        }
        clock.advance(Duration.ofMinutes(1));
        send(hind, abdullah, "واحدة أكثر").andExpect(status().isCreated());
    }

    @Test
    void messageValidationAndPreview() throws Exception {
        send(hind, abdullah, "   ").andExpect(status().isBadRequest());
        send(hind, abdullah, "x".repeat(2001)).andExpect(status().isBadRequest());
        String long_ = "سطر\nثان " + "ك".repeat(200);
        send(hind, abdullah, long_).andExpect(status().isCreated());
        String preview = JsonPath.read(mvc.perform(get("/api/v1/dashboard/conversations?box=SENT").with(as(hind)))
                .andReturn().getResponse().getContentAsString(), "$.items[0].lastMessagePreview");
        assertThat(preview).startsWith("سطر ثان ").endsWith("…").hasSize(120);
        assertThat(bodies(thread(abdullah, hind, ""))).containsExactly(long_);
    }

    @Test
    void membersOneCannotSeeAreNotFound() throws Exception {
        Member hiddenProfile = members.create(s -> { s.gender = "MALE"; s.visibility = "HIDDEN"; });
        for (UUID id : List.of(hiddenProfile.id(), UUID.randomUUID(), hind.id())) {
            mvc.perform(get("/api/v1/dashboard/conversations/" + id + "/messages").with(as(hind)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/dashboard/conversations/" + id + "/messages").with(as(hind))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void conversationsDisappearWithEitherProfile() throws Exception {
        send(hind, abdullah, "السلام عليكم");
        jdbc.update("DELETE FROM user_profiles WHERE user_id = ?", abdullah.id().toString());

        for (String table : List.of("dashboard_conversations", "dashboard_conversation_members", "dashboard_messages")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }

    @Test
    void anonymousCallsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/dashboard/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dashboard/conversations/" + hind.id() + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}

package com.muslimafree.dashboard.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.jayway.jsonpath.JsonPath;
import com.muslimafree.dashboard.DashboardApplication;
import com.muslimafree.dashboard.support.AbstractIntegrationTest;
import com.muslimafree.dashboard.support.Members.Member;
import com.muslimafree.dashboard.support.TestTokens;

/**
 * Real-time push over a real server: authentication by first frame, messages and bell notifications delivered after
 * commit, presence kept by an open socket, sweep of dead tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DashboardApplication.class, AbstractIntegrationTest.TestClockConfig.class})
class LiveIT extends AbstractIntegrationTest {

    private static final long WAIT_MS = 5_000;
    /** How long a socket that should hear nothing is listened to. */
    private static final long QUIET_MS = 500;

    @LocalServerPort
    private int port;

    @Autowired
    private LiveSessions sessions;

    private final List<Client> clients = new ArrayList<>();
    private Member hind;
    private Member abdullah;
    private Member omar;

    @BeforeEach
    void cast() throws Exception {
        hind = members.create(s -> s.gender = "FEMALE");
        abdullah = members.create(s -> s.gender = "MALE");
        omar = members.create(s -> s.gender = "MALE");
        mvc.perform(put("/api/v1/dashboard/likes/" + abdullah.id()).with(as(hind))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/dashboard/likes/" + hind.id()).with(as(abdullah))).andExpect(status().isOk());
    }

    @AfterEach
    void disconnect() {
        for (Client client : clients) {
            if (client.session.isOpen()) {
                try {
                    client.session.close();
                } catch (Exception ignored) {
                    // closing anyway
                }
            }
        }
    }

    @Test
    void bothMembersHearAMessageTheMomentItIsSent() throws Exception {
        Client hindSocket = signedIn(hind);
        Client abdullahSocket = signedIn(abdullah);
        Client abdullahPhone = signedIn(abdullah);

        send(abdullah, hind, "السلام عليكم");

        String received = hindSocket.next("message");
        assertThat(JsonPath.<String>read(received, "$.memberId")).isEqualTo(abdullah.id().toString());
        assertThat(JsonPath.<String>read(received, "$.message.body")).isEqualTo("السلام عليكم");
        assertThat(JsonPath.<Boolean>read(received, "$.message.fromMe")).isFalse();
        assertThat(JsonPath.<String>read(received, "$.message.id")).isNotBlank();
        assertThat(JsonPath.<String>read(received, "$.message.sentAt")).isNotBlank();

        for (Client mine : List.of(abdullahSocket, abdullahPhone)) {
            String echo = mine.next("message");
            assertThat(JsonPath.<String>read(echo, "$.memberId")).isEqualTo(hind.id().toString());
            assertThat(JsonPath.<Boolean>read(echo, "$.message.fromMe")).isTrue();
        }
    }

    @Test
    void otherMembersHearNothing() throws Exception {
        Client omarSocket = signedIn(omar);
        send(abdullah, hind, "hello");
        assertThat(omarSocket.quiet()).isTrue();
    }

    @Test
    void refusedMessagesAreNeverPushed() throws Exception {
        Client hindSocket = signedIn(hind);
        mvc.perform(post("/api/v1/dashboard/conversations/" + hind.id() + "/messages").with(as(omar))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"not a match\"}"))
                .andExpect(status().isForbidden());
        assertThat(hindSocket.quiet()).isTrue();
    }

    @Test
    void readingAChatClearsItOnTheReadersOtherDevices() throws Exception {
        send(abdullah, hind, "hello");
        Client hindPhone = signedIn(hind);
        Client abdullahSocket = signedIn(abdullah);

        mvc.perform(put("/api/v1/dashboard/conversations/" + abdullah.id() + "/read").with(as(hind)))
                .andExpect(status().isNoContent());

        String read = hindPhone.next();
        assertThat(JsonPath.<String>read(read, "$.type")).isEqualTo("read");
        assertThat(JsonPath.<String>read(read, "$.memberId")).isEqualTo(abdullah.id().toString());
        // no read receipts: the writer is not told
        assertThat(abdullahSocket.quiet()).isTrue();
    }

    @Test
    void aLikeIsPushedToTheLikedMemberWithTheBellCount() throws Exception {
        Client omarSocket = signedIn(omar);
        mvc.perform(put("/api/v1/dashboard/likes/" + omar.id()).with(as(hind))).andExpect(status().isOk());

        String frame = omarSocket.next("notification");
        assertThat(JsonPath.<String>read(frame, "$.notification.type")).isEqualTo("LIKE_RECEIVED");
        assertThat(JsonPath.<String>read(frame, "$.notification.actor.id")).isEqualTo(hind.id().toString());
        assertThat(JsonPath.<String>read(frame, "$.notification.id")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(frame, "$.notification.read")).isFalse();
        assertThat(JsonPath.<Integer>read(frame, "$.unreadNotifications")).isEqualTo(1);
    }

    @Test
    void aMatchIsPushedToBothMembers() throws Exception {
        mvc.perform(put("/api/v1/dashboard/likes/" + omar.id()).with(as(hind))).andExpect(status().isOk());
        Client hindSocket = signedIn(hind);
        Client omarSocket = signedIn(omar);

        mvc.perform(put("/api/v1/dashboard/likes/" + hind.id()).with(as(omar))).andExpect(status().isOk());

        String hindFrame = hindSocket.next("notification");
        assertThat(JsonPath.<String>read(hindFrame, "$.notification.type")).isEqualTo("LIKE_RECEIVED");
        hindFrame = hindSocket.next("notification");
        assertThat(JsonPath.<String>read(hindFrame, "$.notification.type")).isEqualTo("MATCH");
        assertThat(JsonPath.<String>read(hindFrame, "$.notification.actor.id")).isEqualTo(omar.id().toString());

        String omarFrame = omarSocket.next("notification");
        assertThat(JsonPath.<String>read(omarFrame, "$.notification.type")).isEqualTo("MATCH");
        assertThat(JsonPath.<String>read(omarFrame, "$.notification.actor.id")).isEqualTo(hind.id().toString());
    }

    @Test
    void aNewMessageAlsoRingsTheBellOncePerConversation() throws Exception {
        Client hindSocket = signedIn(hind);
        send(abdullah, hind, "one");
        String first = hindSocket.next("notification");
        send(abdullah, hind, "two");
        String second = hindSocket.next("notification");

        assertThat(JsonPath.<String>read(first, "$.notification.type")).isEqualTo("NEW_MESSAGE");
        // the same bell row, refreshed: the id stays, the count does not grow (like + match + this message)
        assertThat(JsonPath.<String>read(second, "$.notification.id"))
                .isEqualTo(JsonPath.<String>read(first, "$.notification.id"));
        assertThat(JsonPath.<Integer>read(first, "$.unreadNotifications")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(second, "$.unreadNotifications")).isEqualTo(3);
    }

    @Test
    void anOpenSocketKeepsTheMemberOnline() throws Exception {
        Member away = members.create(s -> {
            s.gender = "MALE";
            s.lastActiveAt = NOW.minus(Duration.ofHours(3));
        });
        signedIn(away);
        assertThat(lastActive(away)).isEqualTo(NOW);
    }

    @Test
    void pingIsAnswered() throws Exception {
        Client socket = signedIn(hind);
        socket.write("{\"type\":\"ping\"}");
        assertThat(JsonPath.<String>read(socket.next(), "$.type")).isEqualTo("pong");
    }

    @Test
    void badTokensAreClosedAsUnauthenticated() throws Exception {
        for (String token : List.of("not-a-jwt", TestTokens.expired(hind.id(), hind.sessionId()),
                TestTokens.signedByAnotherKey(hind.id(), hind.sessionId()),
                TestTokens.withIssuer("https://evil.test", hind.id(), hind.sessionId()))) {
            Client socket = connect();
            socket.write(auth(token));
            assertThat(socket.closeStatus().getCode()).isEqualTo(4401);
        }
        members.revokeSession(hind.sessionId());
        Client revoked = connect();
        revoked.write(auth(hind.token()));
        assertThat(revoked.closeStatus().getCode()).isEqualTo(4401);
    }

    @Test
    void tokensWithoutTheMemberRoleAreForbidden() throws Exception {
        Client socket = connect();
        socket.write(auth(TestTokens.withoutRoles(hind.id(), hind.sessionId())));
        assertThat(socket.closeStatus().getCode()).isEqualTo(4403);
    }

    @Test
    void framesBeforeAuthenticationCloseTheSocket() throws Exception {
        Client socket = connect();
        socket.write("{\"type\":\"ping\"}");
        assertThat(socket.closeStatus().getCode()).isEqualTo(4401);

        Client garbage = connect();
        garbage.write("not json");
        assertThat(garbage.closeStatus()).isEqualTo(CloseStatus.BAD_DATA);
    }

    @Test
    void aSocketCannotSwitchMember() throws Exception {
        Client socket = signedIn(hind);
        socket.write(auth(abdullah.token()));
        assertThat(socket.closeStatus()).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void aFreshTokenKeepsTheSocketOpen() throws Exception {
        Client socket = signedIn(hind);
        socket.write(auth(hind.token()));
        assertThat(JsonPath.<String>read(socket.next(), "$.type")).isEqualTo("ready");
    }

    @Test
    void socketsThatNeverAuthenticateAreClosed() throws Exception {
        Client socket = connect();
        clock.advance(LiveSessions.AUTH_TIMEOUT);
        sessions.sweep();
        assertThat(socket.closeStatus().getCode()).isEqualTo(4401);
    }

    @Test
    void theSweepClosesRevokedSessionsAndExpiredTokens() throws Exception {
        Client revoked = signedIn(hind);
        Client stillValid = signedIn(abdullah);
        members.revokeSession(hind.sessionId());
        sessions.sweep();
        assertThat(revoked.closeStatus().getCode()).isEqualTo(4401);
        assertThat(stillValid.session.isOpen()).isTrue();

        clock.set(Instant.now().plus(Duration.ofMinutes(16)));
        sessions.sweep();
        assertThat(stillValid.closeStatus().getCode()).isEqualTo(4401);
    }

    @Test
    void oneMemberHasAtMostFiveSockets() throws Exception {
        for (int i = 0; i < LiveSessions.MAX_PER_MEMBER; i++) {
            signedIn(hind);
        }
        Client sixth = connect();
        sixth.write(auth(hind.token()));
        assertThat(sixth.closeStatus().getCode()).isEqualTo(4429);

        clients.get(0).session.close();
        Thread.sleep(200);
        signedIn(hind);
    }

    @Test
    void theHandshakeNeedsNoToken() throws Exception {
        assertThat(connect().session.isOpen()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void send(Member from, Member to, String body) throws Exception {
        mvc.perform(post("/api/v1/dashboard/conversations/" + to.id() + "/messages").with(as(from))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());
    }

    private Instant lastActive(Member member) {
        return jdbc.queryForObject("SELECT last_active_at FROM users WHERE id = ?", java.sql.Timestamp.class,
                member.id().toString()).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    private static String auth(String token) {
        return "{\"type\":\"auth\",\"token\":\"" + token + "\"}";
    }

    private Client signedIn(Member member) throws Exception {
        Client client = connect();
        client.write(auth(member.token()));
        assertThat(JsonPath.<String>read(client.next(), "$.type")).isEqualTo("ready");
        return client;
    }

    private Client connect() throws Exception {
        Client client = new Client();
        client.session = new StandardWebSocketClient()
                .execute(client, "ws://localhost:" + port + LiveConfig.PATH)
                .get(WAIT_MS, TimeUnit.MILLISECONDS);
        clients.add(client);
        return client;
    }

    private static final class Client extends TextWebSocketHandler {

        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private WebSocketSession session;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        void write(String frame) throws Exception {
            session.sendMessage(new TextMessage(frame));
        }

        String next() throws InterruptedException {
            String frame = frames.poll(WAIT_MS, TimeUnit.MILLISECONDS);
            assertThat(frame).as("frame within %d ms", WAIT_MS).isNotNull();
            return frame;
        }

        /** The next frame of this type; frames of other types (a message's bell notification...) are skipped. */
        String next(String type) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                String frame = frames.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(JsonPath.read(frame, "$.type"))) {
                    return frame;
                }
            }
            throw new AssertionError("no " + type + " frame within " + WAIT_MS + " ms");
        }

        boolean quiet() throws InterruptedException {
            return frames.poll(QUIET_MS, TimeUnit.MILLISECONDS) == null;
        }

        CloseStatus closeStatus() throws Exception {
            return closed.get(WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }
}

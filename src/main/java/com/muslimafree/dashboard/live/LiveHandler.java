package com.muslimafree.dashboard.live;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.security.PresenceRecorder;

/**
 * Client frames: {@code {"type":"auth","token":"<access token>"}} first (and again with a fresh token before it
 * expires), then {@code {"type":"ping"}} as a keep-alive. The token travels in a frame, not in the URL, so it never
 * lands in access logs; it is checked by the same decoder as REST (signature, issuer, expiry, live session). An open
 * socket counts as activity ("متصل الان"): its auth and ping frames refresh the member's presence, as requests do.
 */
@Component
public class LiveHandler extends TextWebSocketHandler {

    /** Client frames are tiny; a token is ~1 KB. */
    private static final int MAX_FRAME = 8 * 1024;

    private final LiveSessions sessions;
    private final JwtDecoder jwtDecoder;
    private final PresenceRecorder presence;
    private final ObjectMapper objectMapper;

    public LiveHandler(LiveSessions sessions, JwtDecoder jwtDecoder, PresenceRecorder presence,
            ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.jwtDecoder = jwtDecoder;
        this.presence = presence;
        this.objectMapper = objectMapper;
    }

    record ServerFrame(String type) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_FRAME);
        sessions.opened(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException e) {
            sessions.close(session, CloseStatus.BAD_DATA);
            return;
        }
        String type = frame.path("type").asText("");
        if ("auth".equals(type)) {
            authenticate(session, frame.path("token").asText(""));
        } else if (!sessions.isAuthenticated(session)) {
            sessions.close(session, LiveSessions.UNAUTHENTICATED);
        } else if ("ping".equals(type)) {
            sessions.member(session).ifPresent(presence::touch);
            sessions.reply(session, new ServerFrame("pong"));
        }
        // other types are ignored, so newer clients keep working against this server
    }

    private void authenticate(WebSocketSession session, String token) throws Exception {
        Jwt jwt;
        CurrentUser user;
        try {
            jwt = jwtDecoder.decode(token);
            user = CurrentUser.from(jwt);
        } catch (JwtException | IllegalArgumentException e) {
            sessions.close(session, LiveSessions.UNAUTHENTICATED);
            return;
        }
        List<String> roles = jwt.getClaimAsStringList(CurrentUser.ROLES_CLAIM);
        if (roles == null || !roles.contains("MEMBER")) {
            sessions.close(session, LiveSessions.FORBIDDEN);
            return;
        }
        Instant expiresAt = jwt.getExpiresAt() == null ? Instant.MIN : jwt.getExpiresAt();
        CloseStatus refused = sessions.authenticate(session, user, expiresAt);
        if (refused != null) {
            sessions.close(session, refused);
            return;
        }
        presence.touch(user.userId());
        sessions.reply(session, new ServerFrame("ready"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.closed(session);
    }
}

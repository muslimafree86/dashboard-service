package com.muslimafree.dashboard.live;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.security.SessionValidator;

import jakarta.annotation.PreDestroy;

/**
 * The open sockets of this instance, by member. A socket counts only once its first frame authenticated it; until then
 * it receives nothing and is closed after {@link #AUTH_TIMEOUT}. Every {@link #SWEEP_INTERVAL} a sweep closes sockets
 * whose token expired or whose session was revoked (logout, suspension), like REST does on every request — so a
 * revocation reaches an open socket within the sweep interval plus {@code app.security.session-cache-ttl}.
 */
@Component
public class LiveSessions {

    static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(10);
    /** Tabs and devices of one member; more are refused (4429) so one account cannot hold an instance's sockets. */
    static final int MAX_PER_MEMBER = 5;

    static final CloseStatus UNAUTHENTICATED = new CloseStatus(4401, "unauthenticated");
    static final CloseStatus FORBIDDEN = new CloseStatus(4403, "forbidden");
    static final CloseStatus TOO_MANY = new CloseStatus(4429, "too many connections");

    private static final Logger log = LoggerFactory.getLogger(LiveSessions.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 512 * 1024;

    private final SessionValidator sessionValidator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Connection>> byMember = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "live-sessions-sweep");
        thread.setDaemon(true);
        return thread;
    });

    public LiveSessions(SessionValidator sessionValidator, ObjectMapper objectMapper, Clock clock) {
        this.sessionValidator = sessionValidator;
        this.objectMapper = objectMapper;
        this.clock = clock;
        long period = SWEEP_INTERVAL.toMillis();
        sweeper.scheduleWithFixedDelay(this::sweepQuietly, period, period, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
    }

    void opened(WebSocketSession session) {
        connections.put(session.getId(), new Connection(
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT), clock.instant()));
    }

    /**
     * Binds the socket to the token's member, or refreshes its token (same member only). Returns the close status
     * when the socket must be closed instead.
     */
    CloseStatus authenticate(WebSocketSession session, CurrentUser user, Instant expiresAt) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            return CloseStatus.SESSION_NOT_RELIABLE;
        }
        synchronized (connection) {
            if (connection.user != null) {
                if (!connection.user.userId().equals(user.userId())) {
                    return CloseStatus.POLICY_VIOLATION;
                }
            } else {
                boolean[] full = {false};
                // compute is atomic per member, like the removal in closed()
                byMember.compute(user.userId(), (id, mine) -> {
                    Set<Connection> set = mine == null ? ConcurrentHashMap.newKeySet() : mine;
                    if (set.size() >= MAX_PER_MEMBER) {
                        full[0] = true;
                    } else {
                        set.add(connection);
                    }
                    return set.isEmpty() ? null : set;
                });
                if (full[0]) {
                    return TOO_MANY;
                }
            }
            connection.user = user;
            connection.expiresAt = expiresAt;
        }
        return null;
    }

    boolean isAuthenticated(WebSocketSession session) {
        return member(session).isPresent();
    }

    Optional<UUID> member(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        CurrentUser user = connection == null ? null : connection.user;
        return Optional.ofNullable(user).map(CurrentUser::userId);
    }

    void closed(WebSocketSession session) {
        Connection connection = connections.remove(session.getId());
        if (connection == null || connection.user == null) {
            return;
        }
        byMember.computeIfPresent(connection.user.userId(), (id, mine) -> {
            mine.remove(connection);
            return mine.isEmpty() ? null : mine;
        });
    }

    /** The member has an authenticated socket on this instance: worth building a frame for them. */
    public boolean isConnected(UUID memberId) {
        Set<Connection> mine = byMember.get(memberId);
        return mine != null && !mine.isEmpty();
    }

    /** Sends one frame to every authenticated socket of the member on this instance; nothing if they have none. */
    public void send(UUID memberId, Object frame) {
        Set<Connection> mine = byMember.get(memberId);
        if (mine == null || mine.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(toJson(frame));
        for (Connection connection : mine) {
            try {
                connection.session.sendMessage(message);
            } catch (IOException | RuntimeException e) {
                // slow or dead client (send buffer or time limit exceeded): it reconnects and catches up over REST
                close(connection, CloseStatus.SESSION_NOT_RELIABLE);
            }
        }
    }

    /** Replies on one socket (ready, pong). */
    void reply(WebSocketSession session, Object frame) throws IOException {
        Connection connection = connections.get(session.getId());
        if (connection != null) {
            connection.session.sendMessage(new TextMessage(toJson(frame)));
        }
    }

    void close(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.get(session.getId());
        if (connection != null) {
            close(connection, status);
        }
    }

    /** Closes sockets that never authenticated, whose token expired, or whose session is no longer usable. */
    void sweep() {
        Instant now = clock.instant();
        for (Connection connection : connections.values()) {
            CurrentUser user = connection.user;
            if (user == null) {
                if (!now.isBefore(connection.openedAt.plus(AUTH_TIMEOUT))) {
                    close(connection, UNAUTHENTICATED);
                }
            } else if (!now.isBefore(connection.expiresAt) || !sessionValidator.isUsable(user)) {
                close(connection, UNAUTHENTICATED);
            }
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Live session sweep failed", e);
        }
    }

    private static void close(Connection connection, CloseStatus status) {
        try {
            connection.session.close(status);
        } catch (IOException | RuntimeException e) {
            // already gone; afterConnectionClosed unregisters it
        }
    }

    private String toJson(Object frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Connection {

        private final WebSocketSession session;
        private final Instant openedAt;
        private volatile CurrentUser user;
        private volatile Instant expiresAt;

        private Connection(WebSocketSession session, Instant openedAt) {
            this.session = session;
            this.openedAt = openedAt;
        }
    }
}

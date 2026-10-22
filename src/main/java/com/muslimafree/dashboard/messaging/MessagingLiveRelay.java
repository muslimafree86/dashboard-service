package com.muslimafree.dashboard.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.muslimafree.dashboard.live.LiveSessions;

/**
 * Pushes committed messaging events to the members' open sockets on this instance. Frames are hints for the UI, not
 * the record: REST stays the source of truth, and a client that reconnects catches up with {@code after=<last id>}.
 */
@Component
class MessagingLiveRelay {

    private final LiveSessions sessions;

    MessagingLiveRelay(LiveSessions sessions) {
        this.sessions = sessions;
    }

    /** {@code memberId} is the other member of the chat, as in the REST paths; {@code message} as in a thread. */
    record MessageFrame(String type, UUID memberId, MessageResponse message) {
    }

    record ReadFrame(String type, UUID memberId) {
    }

    @TransactionalEventListener
    void on(MessagingEvent.MessageSent event) {
        sessions.send(event.recipientId(), new MessageFrame("message", event.senderId(),
                new MessageResponse(event.messageId(), false, event.body(), event.sentAt())));
        sessions.send(event.senderId(), new MessageFrame("message", event.recipientId(),
                new MessageResponse(event.messageId(), true, event.body(), event.sentAt())));
    }

    @TransactionalEventListener
    void on(MessagingEvent.ConversationRead event) {
        sessions.send(event.readerId(), new ReadFrame("read", event.otherId()));
    }
}

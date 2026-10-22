package com.muslimafree.dashboard.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the messaging transactions; {@link MessagingLiveRelay} pushes them to open WebSockets once they
 * commit, so a frame never announces a message that was rolled back.
 */
public sealed interface MessagingEvent {

    /** A message was stored: both members' open sockets hear about it (the sender's other tabs and devices too). */
    record MessageSent(UUID senderId, UUID recipientId, UUID messageId, String body, Instant sentAt)
            implements MessagingEvent {
    }

    /** A member read their chat with {@code otherId}: their other tabs and devices clear the badge. */
    record ConversationRead(UUID readerId, UUID otherId) implements MessagingEvent {
    }
}

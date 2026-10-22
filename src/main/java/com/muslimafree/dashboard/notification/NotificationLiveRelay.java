package com.muslimafree.dashboard.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.muslimafree.dashboard.live.LiveSessions;

/**
 * Pushes a new bell notification (like received, match, new message) to the member's open sockets on this instance,
 * as the bell would list it, with the new unread count for the badge. Built only for members with a socket open here.
 */
@Component
class NotificationLiveRelay {

    private static final Logger log = LoggerFactory.getLogger(NotificationLiveRelay.class);

    private final LiveSessions sessions;
    private final NotificationService notifications;

    NotificationLiveRelay(LiveSessions sessions, NotificationService notifications) {
        this.sessions = sessions;
        this.notifications = notifications;
    }

    record NotificationFrame(String type, NotificationResponse notification, long unreadNotifications) {
    }

    @TransactionalEventListener
    void on(NotificationEvent event) {
        if (!sessions.isConnected(event.memberId())) {
            return;
        }
        try {
            notifications.forPush(event.memberId(), event.type(), event.actorId()).ifPresent(live ->
                    sessions.send(event.memberId(),
                            new NotificationFrame("notification", live.notification(), live.unreadNotifications())));
        } catch (RuntimeException e) {
            // after commit: the like or message is stored; a failed push must not turn the request into an error
            log.warn("Could not push notification {} to {}", event.type(), event.memberId(), e);
        }
    }
}

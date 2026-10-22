package com.muslimafree.dashboard.notification;

import java.util.UUID;

/** A bell row was written or refreshed inside a transaction; {@link NotificationLiveRelay} pushes it once committed. */
public record NotificationEvent(UUID memberId, NotificationType type, UUID actorId) {
}

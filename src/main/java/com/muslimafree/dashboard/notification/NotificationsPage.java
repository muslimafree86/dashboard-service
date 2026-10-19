package com.muslimafree.dashboard.notification;

import java.util.List;

public record NotificationsPage(List<NotificationResponse> items, int page, int size, boolean hasMore,
        long unread) {
}

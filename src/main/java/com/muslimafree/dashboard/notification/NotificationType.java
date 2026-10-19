package com.muslimafree.dashboard.notification;

/** What the header bell can say. */
public enum NotificationType {
    /** "X liked you" */
    LIKE_RECEIVED,
    /** "You and X like each other" - sent to both */
    MATCH,
    /** "New message from X" - one per conversation, refreshed by every message, cleared when the chat is read */
    NEW_MESSAGE
}

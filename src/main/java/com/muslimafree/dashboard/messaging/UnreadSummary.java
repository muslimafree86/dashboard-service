package com.muslimafree.dashboard.messaging;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Badge on the messages icon; cheap enough to poll")
public record UnreadSummary(long unreadMessages, long unreadConversations) {
}

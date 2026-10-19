package com.muslimafree.dashboard.messaging;

import java.time.Instant;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One row of the messages page: name, \"منذ 3 دقائق\", preview of the last message")
public record ConversationSummary(
        MemberCard member,
        String lastMessagePreview,
        Instant lastMessageAt,
        boolean lastMessageFromMe,
        int unreadCount,
        @Schema(description = "Both members still like each other; false = read-only history") boolean canSend) {
}

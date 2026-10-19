package com.muslimafree.dashboard.messaging;

import java.util.List;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A chat with one member: messages oldest first")
public record ConversationThread(
        MemberCard member,
        @Schema(description = "Both members still like each other; false = read-only history") boolean canSend,
        List<MessageResponse> messages,
        @Schema(description = "More messages exist in the requested direction: older for the latest page and before=, "
                + "newer for after=") boolean hasMore) {
}

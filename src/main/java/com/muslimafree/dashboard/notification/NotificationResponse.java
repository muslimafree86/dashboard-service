package com.muslimafree.dashboard.notification;

import java.time.Instant;
import java.util.UUID;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationResponse(
        UUID id,
        @Schema(description = "LIKE_RECEIVED, MATCH or NEW_MESSAGE; the client words it (ar/en)") NotificationType type,
        @Schema(description = "Who did it") MemberCard actor,
        Instant at,
        boolean read) {
}

package com.muslimafree.dashboard.interaction;

import io.swagger.v3.oas.annotations.media.Schema;

/** The Home page counters (Figma 4:936) and the Activity tab badges. */
public record ActivitySummary(
        @Schema(description = "Members in my favourites") long favourites,
        @Schema(description = "Members who viewed my profile") long profileViews,
        @Schema(description = "Members who liked me") long likesReceived,
        @Schema(description = "Mutual likes") long matches,
        @Schema(description = "Unread messages") long unreadMessages) {
}

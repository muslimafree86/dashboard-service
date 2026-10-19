package com.muslimafree.dashboard.interaction;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "State of the like / favourite buttons on a member's profile, from the caller's side")
public record InteractionStatus(
        @Schema(description = "I liked this member") boolean liked,
        @Schema(description = "This member liked me") boolean likedYou,
        @Schema(description = "Both liked each other") boolean matched,
        @Schema(description = "In my favourites (private)") boolean favourite) {
}

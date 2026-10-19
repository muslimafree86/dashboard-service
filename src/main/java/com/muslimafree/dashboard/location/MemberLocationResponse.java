package com.muslimafree.dashboard.location;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The caller's own stored location, as snapped to the grid. Only ever returned to its owner.")
public record MemberLocationResponse(
        BigDecimal latitude,
        BigDecimal longitude,
        Instant updatedAt,
        @Schema(description = "false once the location is too old to be used: distances are then measured from the "
                + "member's city / governorate until the app shares a fresh one") boolean active,
        @Schema(description = "When the location stops being used unless it is shared again") Instant expiresAt) {
}

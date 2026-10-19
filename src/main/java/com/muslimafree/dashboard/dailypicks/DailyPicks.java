package com.muslimafree.dashboard.dailypicks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

public record DailyPicks(
        @Schema(description = "The UTC day these picks belong to") LocalDate day,
        @Schema(description = "Best matches first; members who became unavailable since the morning are left out")
        List<MemberCard> items,
        @Schema(description = "When the next picks are chosen (midnight UTC)") Instant nextPicksAt) {
}

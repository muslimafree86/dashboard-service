package com.muslimafree.dashboard.interaction;

import java.time.Instant;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

public record ActivityEntry(
        MemberCard member,
        @Schema(description = "When it happened: liked, matched, added to favourites, or last viewed my profile")
        Instant at) {
}

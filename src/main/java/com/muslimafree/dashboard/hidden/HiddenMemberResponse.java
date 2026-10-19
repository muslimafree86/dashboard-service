package com.muslimafree.dashboard.hidden;

import java.time.Instant;

import com.muslimafree.dashboard.member.MemberCard;

public record HiddenMemberResponse(MemberCard member, Instant hiddenAt) {
}

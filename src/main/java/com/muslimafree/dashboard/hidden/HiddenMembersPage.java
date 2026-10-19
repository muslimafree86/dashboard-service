package com.muslimafree.dashboard.hidden;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Members the caller marked \"not interested\", most recent first. Members the caller can no "
        + "longer see (hidden their profile, blocked, left) are left out, so a page may hold fewer than size items.")
public record HiddenMembersPage(List<HiddenMemberResponse> items, int page, int size, boolean hasMore) {
}

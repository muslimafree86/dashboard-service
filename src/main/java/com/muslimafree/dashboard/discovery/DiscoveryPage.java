package com.muslimafree.dashboard.discovery;

import java.util.List;

import com.muslimafree.dashboard.member.MemberCard;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of the feed. Keyset-paginated: pass {@code nextCursor} back unchanged (with the same filters) to get the
 * next page. There is no total count - counting every eligible member on each scroll would cost as much as the search.
 */
public record DiscoveryPage(
        List<MemberCard> items,
        @Schema(description = "Absent on the last page") String nextCursor,
        boolean hasMore) {
}

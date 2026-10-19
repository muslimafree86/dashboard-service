package com.muslimafree.dashboard.hidden;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.discovery.DiscoveryService;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ViewerService;

/**
 * "Not interested" (pass): the member leaves the caller's discovery - feed, tabs, search, similar members - until the
 * caller undoes it. Unlike a block it is one-way and silent: the other member notices nothing and still sees the caller.
 */
@Service
public class HiddenMemberService {

    /** Only this many hidden members can be paged through; there is no reason to scroll deeper. */
    static final int MAX_RESULTS = 10_000;

    private final ViewerService viewers;
    private final DiscoveryService discovery;
    private final HiddenMemberRepository hidden;
    private final Clock clock;

    public HiddenMemberService(ViewerService viewers, DiscoveryService discovery, HiddenMemberRepository hidden,
            Clock clock) {
        this.viewers = viewers;
        this.discovery = discovery;
        this.hidden = hidden;
        this.clock = clock;
    }

    /** 404 for a member the caller may not see: hiding must not reveal whether an account exists. */
    @Transactional
    public void hide(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        if (!discovery.isVisible(viewer, memberId)) {
            throw new ApiException(ErrorCode.MEMBER_NOT_FOUND);
        }
        hidden.hide(viewer.id(), memberId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** Idempotent, and silent about members that do not exist. */
    @Transactional
    public void unhide(CurrentUser user, UUID memberId) {
        hidden.unhide(user.userId(), memberId);
    }

    @Transactional(readOnly = true)
    public HiddenMembersPage list(CurrentUser user, int page, int size) {
        if ((long) page * size + size > MAX_RESULTS) {
            throw new ApiException(ErrorCode.PAGE_OUT_OF_RANGE, Map.of("maxResults", MAX_RESULTS));
        }
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        List<HiddenMemberRepository.Hidden> rows = hidden.page(viewer.id(), page * size, size + 1);
        boolean hasMore = rows.size() > size;
        List<HiddenMemberRepository.Hidden> pageRows = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, MemberCard> cards = discovery.visibleCards(viewer,
                pageRows.stream().map(HiddenMemberRepository.Hidden::memberId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(MemberCard::id, Function.identity()));
        List<HiddenMemberResponse> items = pageRows.stream()
                .filter(row -> cards.containsKey(row.memberId()))
                .map(row -> new HiddenMemberResponse(cards.get(row.memberId()), row.hiddenAt().toInstant(ZoneOffset.UTC)))
                .toList();
        return new HiddenMembersPage(items, page, size, hasMore);
    }
}

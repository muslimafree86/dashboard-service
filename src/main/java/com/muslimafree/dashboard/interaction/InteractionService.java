package com.muslimafree.dashboard.interaction;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.discovery.DiscoveryService;
import com.muslimafree.dashboard.hidden.HiddenMemberRepository;
import com.muslimafree.dashboard.member.Gender;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ViewerService;
import com.muslimafree.dashboard.messaging.MessageRepository;
import com.muslimafree.dashboard.notification.NotificationService;

/**
 * Likes, favourites and the Activity tab.
 * <ul>
 * <li><b>Like</b> - interest the other member sees ("X liked you"). Two likes in opposite directions are a
 * <b>match</b>. Liking a member also takes them out of "not interested".</li>
 * <li><b>Favourite</b> - a private bookmark; the other member never learns about it.</li>
 * <li><b>Profile views</b> - recorded by user-profile-managment, read here.</li>
 * </ul>
 * Every action and list applies the discovery visibility rule: one can only like, favourite or see in a list a member
 * one may currently see. Rows of members who later become invisible (blocked, hidden profile, suspended) stay stored and
 * reappear if they become visible again.
 */
@Service
public class InteractionService {

    static final int MAX_RESULTS = 10_000;

    private final ViewerService viewers;
    private final DiscoveryService discovery;
    private final InteractionRepository interactions;
    private final HiddenMemberRepository hidden;
    private final MessageRepository messages;
    private final NotificationService notifications;
    private final AppProperties.Discovery discoveryProperties;
    private final AppProperties.Interactions properties;
    private final Clock clock;

    public InteractionService(ViewerService viewers, DiscoveryService discovery, InteractionRepository interactions,
            HiddenMemberRepository hidden, MessageRepository messages, NotificationService notifications,
            AppProperties properties, Clock clock) {
        this.viewers = viewers;
        this.discovery = discovery;
        this.interactions = interactions;
        this.hidden = hidden;
        this.messages = messages;
        this.notifications = notifications;
        this.discoveryProperties = properties.discovery();
        this.properties = properties.interactions();
        this.clock = clock;
    }

    @Transactional
    public LikeResponse like(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = requireVisible(user, memberId);
        InteractionRepository.Status status = interactions.status(viewer.id(), memberId);
        if (!status.liked()) {
            Instant now = clock.instant();
            LocalDateTime startOfDay = LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.DAYS), ZoneOffset.UTC);
            if (interactions.likesSince(viewer.id(), startOfDay) >= properties.dailyLikeLimit()) {
                long retryAfter = now.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).getEpochSecond()
                        - now.getEpochSecond();
                throw new ApiException(ErrorCode.DAILY_LIKE_LIMIT_REACHED, Map.of("retryAfterSeconds", retryAfter));
            }
            interactions.like(viewer.id(), memberId, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            hidden.unhide(viewer.id(), memberId);
            notifications.liked(viewer.id(), memberId, status.likedMe());
        }
        return new LikeResponse(status.likedMe());
    }

    /** Idempotent; also undoes a match. */
    @Transactional
    public void unlike(CurrentUser user, UUID memberId) {
        interactions.unlike(user.userId(), memberId);
        notifications.unliked(user.userId(), memberId);
    }

    @Transactional
    public void favourite(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = requireVisible(user, memberId);
        interactions.favourite(viewer.id(), memberId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    @Transactional
    public void unfavourite(CurrentUser user, UUID memberId) {
        interactions.unfavourite(user.userId(), memberId);
    }

    /** Button state on a member's profile page; 404 for a member the caller may not see. */
    @Transactional(readOnly = true)
    public InteractionStatus status(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = requireVisible(user, memberId);
        InteractionRepository.Status status = interactions.status(viewer.id(), memberId);
        return new InteractionStatus(status.liked(), status.likedMe(), status.liked() && status.likedMe(),
                status.favourite());
    }

    /** Home counters (favourites, views, likes, unread messages) and Activity badges. */
    @Transactional(readOnly = true)
    public ActivitySummary summary(CurrentUser user) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        Gender gender = gender(viewer);
        boolean verified = discoveryProperties.requireVerifiedEmail();
        return new ActivitySummary(
                interactions.count(ActivityList.FAVOURITES, viewer.id(), gender, verified),
                interactions.count(ActivityList.VIEWERS, viewer.id(), gender, verified),
                interactions.count(ActivityList.LIKES_RECEIVED, viewer.id(), gender, verified),
                interactions.count(ActivityList.MATCHES, viewer.id(), gender, verified),
                messages.unread(viewer.id(), gender, verified).messages());
    }

    @Transactional(readOnly = true)
    public ActivityPage list(CurrentUser user, ActivityList list, int page, int size) {
        if ((long) page * size + size > MAX_RESULTS) {
            throw new ApiException(ErrorCode.PAGE_OUT_OF_RANGE, Map.of("maxResults", MAX_RESULTS));
        }
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        List<InteractionRepository.Entry> rows = interactions.page(list, viewer.id(), gender(viewer),
                discoveryProperties.requireVerifiedEmail(), page * size, size + 1);
        boolean hasMore = rows.size() > size;
        List<InteractionRepository.Entry> pageRows = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, MemberCard> cards = discovery.visibleCards(viewer,
                pageRows.stream().map(InteractionRepository.Entry::memberId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(MemberCard::id, Function.identity()));
        List<ActivityEntry> items = pageRows.stream()
                .filter(row -> cards.containsKey(row.memberId()))
                .map(row -> new ActivityEntry(cards.get(row.memberId()), row.at().toInstant(ZoneOffset.UTC)))
                .toList();
        return new ActivityPage(items, page, size, hasMore);
    }

    private ProfileSnapshot requireVisible(CurrentUser user, UUID memberId) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        if (!discovery.isVisible(viewer, memberId)) {
            throw new ApiException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return viewer;
    }

    private Gender gender(ProfileSnapshot viewer) {
        return viewers.visibleGender(viewer);
    }
}

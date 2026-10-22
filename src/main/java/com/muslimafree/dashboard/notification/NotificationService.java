package com.muslimafree.dashboard.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.util.TimeOrderedUuid;
import com.muslimafree.dashboard.discovery.DiscoveryService;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ViewerService;

/**
 * The header bell: in-app notifications written when something happens to a member (liked, matched, new message).
 * Pushed to open sockets as they happen ({@link NotificationLiveRelay}), polled otherwise; mobile push / e-mail
 * delivery would read the same rows later.
 */
@Service
public class NotificationService {

    static final int MAX_RESULTS = 10_000;

    private final ViewerService viewers;
    private final DiscoveryService discovery;
    private final NotificationRepository notifications;
    private final AppProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public NotificationService(ViewerService viewers, DiscoveryService discovery, NotificationRepository notifications,
            AppProperties properties, ApplicationEventPublisher events, Clock clock) {
        this.viewers = viewers;
        this.discovery = discovery;
        this.notifications = notifications;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ events
    // Called by the features where things happen (interaction, messaging), inside their transaction.

    public void liked(UUID liker, UUID liked, boolean matched) {
        Instant now = clock.instant();
        notify(liked, NotificationType.LIKE_RECEIVED, liker, now);
        if (matched) {
            // the match is the news: one millisecond later, so it is always listed above the like that caused it
            Instant matchedAt = now.plusMillis(1);
            notify(liked, NotificationType.MATCH, liker, matchedAt);
            notify(liker, NotificationType.MATCH, liked, matchedAt);
        }
    }

    /** A taken-back like leaves no trace in the other member's bell, and ends the match notifications. */
    public void unliked(UUID liker, UUID liked) {
        notifications.remove(liked, NotificationType.LIKE_RECEIVED, liker);
        notifications.remove(liked, NotificationType.MATCH, liker);
        notifications.remove(liker, NotificationType.MATCH, liked);
    }

    public void messageReceived(UUID recipient, UUID sender) {
        notify(recipient, NotificationType.NEW_MESSAGE, sender, clock.instant());
    }

    public void conversationRead(UUID reader, UUID other) {
        notifications.markRead(reader, NotificationType.NEW_MESSAGE, other, now());
    }

    private void notify(UUID member, NotificationType type, UUID actor, Instant at) {
        notifications.notify(TimeOrderedUuid.generate(at.toEpochMilli()), member, type, actor,
                LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        events.publishEvent(new NotificationEvent(member, type, actor));
    }

    /**
     * The notification as the bell lists it, with the member's new unread count, for a push after commit. Empty when
     * the bell would not show it (actor no longer visible, row already gone) or the member cannot browse.
     */
    public Optional<LiveNotification> forPush(UUID member, NotificationType type, UUID actor) {
        Optional<ProfileSnapshot> viewer = viewers.discoverer(member);
        Optional<NotificationRepository.Notification> row = notifications.find(member, type, actor);
        if (viewer.isEmpty() || row.isEmpty()) {
            return Optional.empty();
        }
        Optional<MemberCard> card = discovery.visibleCards(viewer.get(), Set.of(actor)).stream().findFirst();
        if (card.isEmpty()) {
            return Optional.empty();
        }
        NotificationRepository.Notification n = row.get();
        long unread = notifications.unread(member, viewers.visibleGender(viewer.get()),
                properties.discovery().requireVerifiedEmail());
        return Optional.of(new LiveNotification(new NotificationResponse(n.id(), n.type(), card.get(),
                n.createdAt().toInstant(ZoneOffset.UTC), n.readAt() != null), unread));
    }

    public record LiveNotification(NotificationResponse notification, long unreadNotifications) {
    }

    // ------------------------------------------------------------------------------------------------ the bell

    @Transactional(readOnly = true)
    public NotificationsPage list(CurrentUser user, int page, int size) {
        if ((long) page * size + size > MAX_RESULTS) {
            throw new ApiException(ErrorCode.PAGE_OUT_OF_RANGE, Map.of("maxResults", MAX_RESULTS));
        }
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        boolean verified = properties.discovery().requireVerifiedEmail();
        List<NotificationRepository.Notification> rows = notifications.page(viewer.id(),
                viewers.visibleGender(viewer), verified, page * size, size + 1);
        boolean hasMore = rows.size() > size;
        List<NotificationRepository.Notification> pageRows = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, MemberCard> cards = discovery.visibleCards(viewer,
                pageRows.stream().map(NotificationRepository.Notification::actorId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(MemberCard::id, Function.identity()));
        List<NotificationResponse> items = pageRows.stream()
                .filter(n -> cards.containsKey(n.actorId()))
                .map(n -> new NotificationResponse(n.id(), n.type(), cards.get(n.actorId()),
                        n.createdAt().toInstant(ZoneOffset.UTC), n.readAt() != null))
                .toList();
        return new NotificationsPage(items, page, size, hasMore,
                notifications.unread(viewer.id(), viewers.visibleGender(viewer), verified));
    }

    @Transactional(readOnly = true)
    public long unread(CurrentUser user) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        return notifications.unread(viewer.id(), viewers.visibleGender(viewer),
                properties.discovery().requireVerifiedEmail());
    }

    /** 404 for an id that is not one of the caller's notifications. */
    @Transactional
    public void markRead(CurrentUser user, UUID id) {
        if (notifications.markRead(user.userId(), id, now()) == 0) {
            throw new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Transactional
    public void markAllRead(CurrentUser user) {
        notifications.markAllRead(user.userId(), now());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}

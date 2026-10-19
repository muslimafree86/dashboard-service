package com.muslimafree.dashboard.messaging;

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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.muslimafree.dashboard.common.config.AppProperties;
import com.muslimafree.dashboard.common.exception.ApiException;
import com.muslimafree.dashboard.common.exception.ErrorCode;
import com.muslimafree.dashboard.common.security.CurrentUser;
import com.muslimafree.dashboard.common.util.TimeOrderedUuid;
import com.muslimafree.dashboard.discovery.DiscoveryService;
import com.muslimafree.dashboard.interaction.InteractionRepository;
import com.muslimafree.dashboard.member.MemberCard;
import com.muslimafree.dashboard.member.ProfileSnapshot;
import com.muslimafree.dashboard.member.ViewerService;
import com.muslimafree.dashboard.notification.NotificationService;

/**
 * Text messages between matched members (الرسائل).
 * <ul>
 * <li><b>Who</b> - only members who like each other may write; the match is the recipient's consent. When either
 * takes back their like the history stays readable but closed ({@code canSend=false}).</li>
 * <li><b>Visibility</b> - a conversation is shown only while the other member is visible to the caller, under the same
 * rule as discovery: a block (or a hidden profile, suspension ...) makes it disappear for both, without deleting it.</li>
 * <li><b>Delivery</b> - REST polling: an open chat asks for messages {@code after} the last one it has; the rest of the
 * app polls {@link #unread}.</li>
 * </ul>
 */
@Service
public class MessagingService {

    static final int MAX_RESULTS = 10_000;
    private static final int PREVIEW_LENGTH = 120;

    private final ViewerService viewers;
    private final DiscoveryService discovery;
    private final InteractionRepository interactions;
    private final MessageRepository messages;
    private final NotificationService notifications;
    private final AppProperties properties;
    private final Clock clock;

    public MessagingService(ViewerService viewers, DiscoveryService discovery, InteractionRepository interactions,
            MessageRepository messages, NotificationService notifications, AppProperties properties, Clock clock) {
        this.viewers = viewers;
        this.discovery = discovery;
        this.interactions = interactions;
        this.messages = messages;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public MessageResponse send(CurrentUser user, UUID memberId, String text) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        if (!discovery.isVisible(viewer, memberId)) {
            throw new ApiException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if (!isMatch(viewer.id(), memberId)) {
            throw new ApiException(ErrorCode.NOT_MATCHED);
        }
        Instant now = clock.instant();
        LocalDateTime at = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        if (messages.sentAfter(viewer.id(), at.minusMinutes(1)) >= properties.messaging().maxPerMinute()) {
            throw new ApiException(ErrorCode.MESSAGE_RATE_LIMITED, Map.of("retryAfterSeconds", 60));
        }
        String body = text.strip();
        UUID conversation = messages.ensureConversation(viewer.id(), memberId, TimeOrderedUuid.generate(now.toEpochMilli()),
                at);
        MessageRepository.Message message =
                new MessageRepository.Message(TimeOrderedUuid.generate(now.toEpochMilli()), viewer.id(), body, at);
        messages.insert(conversation, message);
        String preview = preview(body);
        messages.recordSent(viewer.id(), memberId, conversation, at, preview);
        messages.recordReceived(memberId, viewer.id(), conversation, at, preview);
        notifications.messageReceived(memberId, viewer.id());
        notifications.conversationRead(viewer.id(), memberId);
        return toResponse(message, viewer.id());
    }

    /**
     * The chat with one member. No cursor: the latest messages. {@code before}: older history. {@code after}: polling
     * for new messages. 404 when the member is not visible to the caller.
     */
    @Transactional(readOnly = true)
    public ConversationThread thread(CurrentUser user, UUID memberId, UUID before, UUID after, int limit) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        MemberCard member = discovery.visibleCards(viewer, Set.of(memberId)).stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
        boolean canSend = isMatch(viewer.id(), memberId);
        Optional<UUID> conversation = messages.conversationId(viewer.id(), memberId);
        if (conversation.isEmpty()) {
            if (before != null || after != null) {
                throw new ApiException(ErrorCode.INVALID_CURSOR);
            }
            return new ConversationThread(member, canSend, List.of(), false);
        }
        List<MessageRepository.Message> rows;
        boolean hasMore;
        if (after != null) {
            rows = messages.newer(conversation.get(), position(conversation.get(), after), limit + 1);
            hasMore = rows.size() > limit;
            rows = hasMore ? rows.subList(0, limit) : rows;
        } else {
            MessageRepository.Message from = before == null ? null : position(conversation.get(), before);
            rows = messages.older(conversation.get(), from, limit + 1);
            hasMore = rows.size() > limit;
            rows = hasMore ? rows.subList(1, rows.size()) : rows;
        }
        List<MessageResponse> items = rows.stream().map(m -> toResponse(m, viewer.id())).toList();
        return new ConversationThread(member, canSend, items, hasMore);
    }

    /** Idempotent; nothing to do when there is no conversation yet. */
    @Transactional
    public void markRead(CurrentUser user, UUID memberId) {
        messages.markRead(user.userId(), memberId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        notifications.conversationRead(user.userId(), memberId);
    }

    @Transactional(readOnly = true)
    public ConversationsPage conversations(CurrentUser user, ConversationBox box, int page, int size) {
        if ((long) page * size + size > MAX_RESULTS) {
            throw new ApiException(ErrorCode.PAGE_OUT_OF_RANGE, Map.of("maxResults", MAX_RESULTS));
        }
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        List<MessageRepository.Side> rows = messages.page(viewer.id(), box, viewers.visibleGender(viewer),
                properties.discovery().requireVerifiedEmail(), page * size, size + 1);
        boolean hasMore = rows.size() > size;
        List<MessageRepository.Side> pageRows = hasMore ? rows.subList(0, size) : rows;
        Set<UUID> others = pageRows.stream().map(MessageRepository.Side::otherId).collect(Collectors.toSet());
        Map<UUID, MemberCard> cards = discovery.visibleCards(viewer, others).stream()
                .collect(Collectors.toMap(MemberCard::id, Function.identity()));
        Set<UUID> matched = interactions.matchedAmong(viewer.id(), others);
        List<ConversationSummary> items = pageRows.stream()
                .filter(row -> cards.containsKey(row.otherId()))
                .map(row -> new ConversationSummary(cards.get(row.otherId()), row.lastMessagePreview(),
                        row.lastMessageAt().toInstant(ZoneOffset.UTC), row.lastSenderId().equals(viewer.id()),
                        row.unreadCount(), matched.contains(row.otherId())))
                .toList();
        return new ConversationsPage(items, page, size, hasMore);
    }

    @Transactional(readOnly = true)
    public UnreadSummary unread(CurrentUser user) {
        ProfileSnapshot viewer = viewers.requireDiscoverer(user);
        MessageRepository.Unread unread = messages.unread(viewer.id(), viewers.visibleGender(viewer),
                properties.discovery().requireVerifiedEmail());
        return new UnreadSummary(unread.messages(), unread.conversations());
    }

    private boolean isMatch(UUID viewer, UUID member) {
        return interactions.matchedAmong(viewer, Set.of(member)).contains(member);
    }

    /** A cursor must be a message of this very conversation. */
    private MessageRepository.Message position(UUID conversation, UUID messageId) {
        return messages.find(conversation, messageId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_CURSOR));
    }

    /** The first {@value #PREVIEW_LENGTH} characters, never cutting an Arabic letter or emoji in half. */
    static String preview(String body) {
        String singleLine = body.replaceAll("\\s+", " ");
        if (singleLine.codePointCount(0, singleLine.length()) <= PREVIEW_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, singleLine.offsetByCodePoints(0, PREVIEW_LENGTH - 1)) + "…";
    }

    private static MessageResponse toResponse(MessageRepository.Message message, UUID viewer) {
        return new MessageResponse(message.id(), message.senderId().equals(viewer), message.body(),
                message.createdAt().toInstant(ZoneOffset.UTC));
    }
}

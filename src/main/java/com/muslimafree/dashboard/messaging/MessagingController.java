package com.muslimafree.dashboard.messaging;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The messages page (Figma 68:1058: الواردة / المرسلة, chat, اكتب رسالتك) and the mini chat (68:1856). */
@RestController
@RequestMapping("/api/v1/dashboard/conversations")
@Tag(name = "Messages")
public class MessagingController {

    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    @GetMapping
    @Operation(summary = "My conversations, most recent first",
            description = "box=INBOX (default, last message received), SENT (last message mine) or ALL")
    public ConversationsPage conversations(@RequestParam(defaultValue = "INBOX") ConversationBox box,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return messaging.conversations(CurrentUser.require(), box, page, size);
    }

    @GetMapping("/unread")
    @Operation(summary = "Unread messages and conversations (badge; poll every ~30 s)")
    public UnreadSummary unread() {
        return messaging.unread(CurrentUser.require());
    }

    @GetMapping("/{memberId}/messages")
    @Operation(summary = "The chat with a member, oldest first",
            description = "No cursor: latest messages. before=<messageId>: older history. after=<messageId>: new "
                    + "messages (poll every few seconds while the chat is open). 404 when the member is not visible.")
    public ConversationThread thread(@PathVariable UUID memberId, @RequestParam(required = false) UUID before,
            @RequestParam(required = false) UUID after,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return messaging.thread(CurrentUser.require(), memberId, before, after, limit);
    }

    @PostMapping("/{memberId}/messages")
    @Operation(summary = "Send a message (matches only)",
            description = "403 NOT_MATCHED unless both members like each other; 404 for a member I cannot see; "
                    + "429 MESSAGE_RATE_LIMITED above app.messaging.max-per-minute.")
    public ResponseEntity<MessageResponse> send(@PathVariable UUID memberId,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(messaging.send(CurrentUser.require(), memberId, request.body()));
    }

    @PutMapping("/{memberId}/read")
    @Operation(summary = "Mark the conversation with a member as read (idempotent)")
    public ResponseEntity<Void> markRead(@PathVariable UUID memberId) {
        messaging.markRead(CurrentUser.require(), memberId);
        return ResponseEntity.noContent().build();
    }
}

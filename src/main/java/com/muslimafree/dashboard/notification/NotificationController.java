package com.muslimafree.dashboard.notification;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.muslimafree.dashboard.common.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** The header bell. */
@RestController
@RequestMapping("/api/v1/dashboard/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "My notifications, newest first, with the unread count")
    public NotificationsPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return notifications.list(CurrentUser.require(), page, size);
    }

    @GetMapping("/unread")
    @Operation(summary = "Unread count for the bell badge (poll every ~30 s)")
    public Map<String, Long> unread() {
        return Map.of("unread", notifications.unread(CurrentUser.require()));
    }

    @PutMapping("/read")
    @Operation(summary = "Mark all my notifications read (opening the bell)")
    public ResponseEntity<Void> markAllRead() {
        notifications.markAllRead(CurrentUser.require());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "Mark one notification read (idempotent)")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        notifications.markRead(CurrentUser.require(), id);
        return ResponseEntity.noContent().build();
    }
}
